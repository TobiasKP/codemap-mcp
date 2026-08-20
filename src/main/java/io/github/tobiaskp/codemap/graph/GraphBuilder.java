package io.github.tobiaskp.codemap.graph;

import io.github.tobiaskp.codemap.detect.DetectedModule;
import io.github.tobiaskp.codemap.detect.ModuleDetector;
import io.github.tobiaskp.codemap.model.CodeGraph;
import io.github.tobiaskp.codemap.model.EdgeKind;
import io.github.tobiaskp.codemap.model.GEdge;
import io.github.tobiaskp.codemap.model.GNode;
import io.github.tobiaskp.codemap.model.Layer;
import io.github.tobiaskp.codemap.model.NodeKind;
import io.github.tobiaskp.codemap.resolve.SymbolIndex;
import io.github.tobiaskp.codemap.scan.FileFacts;
import io.github.tobiaskp.codemap.scan.LangSpec;
import io.github.tobiaskp.codemap.scan.LanguageRegistry;
import io.github.tobiaskp.codemap.scan.ProjectScanner;
import io.github.tobiaskp.codemap.scan.ScanConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns per-file syntax facts into the three-layer graph.
 *
 * <p>Layer 3 is where the real work happens: every reference is resolved against what the
 * project declares, and anything that does not resolve is dropped. Layers 2 and 1 are
 * rollups of layer 3, so a package edge exists exactly when one of its types reaches into
 * the other package, and a module edge exists when a package edge crosses the module
 * boundary or the build file declared the dependency outright.
 */
public final class GraphBuilder {

    /** per-file state derived once, then used for every reference in that file. */
    private static final class FileContext {
        FileFacts facts;
        String moduleName;
        String layer2Qname;
        /** declared package/namespace, "" when the file has none. */
        String symbolNamespace = "";
        String separator = ".";
        /** language family of this file; references never cross it. */
        String family = "";
        /** qname of the node that owns references not inside any declaration. */
        String primaryOwner;
        /** simple name -> fully qualified name, from this file's imports. */
        final Map<String, String> importedTypes = new HashMap<>();
        /** prefixes of wildcard imports such as {@code com.example.foo.*}. */
        final List<String> wildcardPrefixes = new ArrayList<>();
        /** imports that look like file paths (C/C++ includes, JS relative imports). */
        final List<String> pathImports = new ArrayList<>();
        /** types defined here but declared elsewhere; only set for implementation files. */
        List<String> outOfLineOwners = List.of();
        /** callables declared in this file, deferred until the symbol index exists. */
        final List<FileFacts.Decl> memberDecls = new ArrayList<>();
        /** nested name -> {member qname, owning type qname}. */
        final Map<String, String[]> members = new HashMap<>();
        /** kept so an implementation file can still get its own node as a last resort. */
        String moduleName2 = "";
    }

    private final ScanConfig cfg;
    private final ModuleDetector modules;
    private final LanguageRegistry registry;
    private final CodeGraph graph = new CodeGraph();
    private final SymbolIndex index = new SymbolIndex();
    private final List<FileContext> contexts = new ArrayList<>();
    /**
     * type qname -> field name -> the type name written for it. Built while creating type
     * nodes so that a call on a field can be resolved from any file, not just the one that
     * happens to declare the field.
     */
    private final Map<String, Map<String, String>> fieldTypes = new HashMap<>();

    /** counters for the summary line. */
    public int resolvedRefs, unresolvedRefs, ambiguousRefs, groupsCreated;

    public GraphBuilder(ScanConfig cfg, ModuleDetector modules, LanguageRegistry registry) {
        this.cfg = cfg;
        this.modules = modules;
        this.registry = registry;
    }

    public CodeGraph build(ProjectScanner.Result scan) {
        graph.projectRoot = cfg.root.toString();
        graph.projectName = cfg.projectName;
        graph.filesScanned = scan.parsed;
        graph.filesFailed = scan.failed;
        graph.languageHistogram.putAll(scan.languageHistogram);

        createModuleNodes();
        for (FileFacts facts : scan.facts) createFileNodes(facts);
        groupPackagesByName();
        // members need the finished symbol index to find their enclosing type, and every
        // member has to exist before any call between them can be resolved
        for (FileContext ctx : contexts) createMemberNodes(ctx);
        for (FileContext ctx : contexts) resolveFile(ctx);
        pruneOrphanMembers();
        pruneEmptyPackages();
        pruneEmptyModules();
        assignEdgeViews();
        addDeclaredModuleDeps();
        graph.finish();
        return graph;
    }

    // ------------------------------------------------------------- layer 1

    private void createModuleNodes() {
        for (DetectedModule m : modules.modules()) {
            GNode node = new GNode(Layer.MODULE, NodeKind.MODULE, m.name, m.name, m.parentModule);
            node.path = m.rootRel;
            graph.addNode(node);
        }
    }

    // --------------------------------------------------- layers 2 and 3 nodes

    private void createFileNodes(FileFacts facts) {
        DetectedModule module = modules.ownerOf(Path.of(facts.relPath));
        if (module == null) return;

        LangSpec spec = specFor(facts.relPath);
        FileContext ctx = new FileContext();
        ctx.facts = facts;
        ctx.moduleName = module.name;
        ctx.separator = spec == null ? "." : spec.separator;
        ctx.symbolNamespace = facts.container;
        ctx.family = SymbolIndex.family(facts.lang);

        String containerLabel = containerLabel(facts, module, spec);
        ctx.layer2Qname = module.name + '/' + containerLabel;

        boolean declaredPackage = spec != null
                && spec.containerStyle == LangSpec.ContainerStyle.PACKAGE
                && !facts.container.isEmpty();
        GNode pkg = graph.addNode(new GNode(Layer.PACKAGE,
                declaredPackage ? NodeKind.PACKAGE : NodeKind.FOLDER,
                containerLabel, ctx.layer2Qname, module.name));
        if (pkg.path.isEmpty()) pkg.path = directoryOf(facts.relPath);
        if (pkg.lang.isEmpty()) pkg.lang = facts.lang;
        pkg.loc += facts.loc;

        GNode moduleNode = graph.node(Layer.MODULE, module.name);
        if (moduleNode != null) moduleNode.loc += facts.loc;

        /*
         * Callables are collected for every file, whichever branch below applies. They
         * used to be gathered only in the branch that also creates a type node, so every
         * method defined in a .cpp was silently dropped along with its calls.
         */
        for (FileFacts.Decl decl : facts.decls) {
            if (decl.kind.isCallable()) ctx.memberDecls.add(decl);
        }

        boolean declaresType = facts.decls.stream().anyMatch(d -> !d.kind.isCallable());
        if (!declaresType && !facts.outOfLineOwners.isEmpty()) {
            /*
             * An implementation file: it declares nothing but defines members of types
             * declared elsewhere, which is what almost every C++ .cpp looks like. Giving
             * it a node of its own splits a class from its implementation and drops a
             * near-empty duplicate into whichever folder the .cpp happens to live in.
             * Its content is attributed to the types it implements instead, once the
             * symbol index exists - see resolveFile.
             */
            ctx.outOfLineOwners = new ArrayList<>(facts.outOfLineOwners);
        } else if (!declaresType) {
            // no type anywhere: the file itself is the layer-3 node, and any free
            // functions in it become its members
            String fileName = fileName(facts.relPath);
            String qname = ctx.layer2Qname + '#' + fileName;
            GNode node = graph.addNode(new GNode(Layer.TYPE, NodeKind.FILE, fileName, qname, ctx.layer2Qname));
            node.path = facts.relPath;
            node.lang = facts.lang;
            node.loc = facts.loc;
            node.addFile(facts.relPath, GNode.FileRef.FILE, facts.loc);
            ctx.primaryOwner = qname;
            index.addType(qname, SymbolIndex.stripExtension(fileName), null, module.name, facts.lang);
            index.addPathAlias(facts.relPath, qname);
        } else {
            for (FileFacts.Decl decl : facts.decls) {
                if (decl.kind.isCallable()) continue;      // already collected above
                String qname = ctx.layer2Qname + '#' + decl.nestedName;
                GNode node = graph.addNode(new GNode(Layer.TYPE, decl.kind,
                        decl.nestedName, qname, ctx.layer2Qname));
                node.path = facts.relPath;
                node.lang = facts.lang;
                int span = decl.endLine - decl.startLine + 1;
                node.loc = Math.max(node.loc, span);
                node.addFile(facts.relPath, GNode.FileRef.DECLARATION, span);
                if (ctx.primaryOwner == null) ctx.primaryOwner = qname;

                String fqn = facts.container.isEmpty()
                        ? decl.nestedName
                        : facts.container + ctx.separator + decl.nestedName;
                recordFieldTypes(facts, decl.nestedName, qname);
                index.addType(qname, lastSegment(decl.nestedName), fqn, module.name, facts.lang);
                if (!fqn.equals(decl.nestedName)) {
                    index.addType(qname, decl.nestedName, null, module.name, facts.lang);
                }
                index.addPathAlias(facts.relPath, qname);
            }
        }

        classifyImports(ctx);
        contexts.add(ctx);
    }

    /** Package name when the language has packages, otherwise the folder inside the module. */
    private String containerLabel(FileFacts facts, DetectedModule module, LangSpec spec) {
        if (spec != null && spec.containerStyle == LangSpec.ContainerStyle.PACKAGE
                && !facts.container.isEmpty()) {
            return facts.container;
        }
        String dir = directoryOf(facts.relPath);
        String root = module.rootRel;
        if (!root.isEmpty()) {
            if (dir.equals(root)) return ".";
            if (dir.startsWith(root + '/')) return dir.substring(root.length() + 1);
        }
        return dir.isEmpty() ? "." : dir;
    }

    private void classifyImports(FileContext ctx) {
        for (String imp : ctx.facts.imports) {
            if (looksLikePath(imp)) {
                ctx.pathImports.add(imp);
                continue;
            }
            String sep = ctx.separator;
            if (imp.endsWith(sep + "*") || imp.endsWith(".*")) {
                int cut = imp.lastIndexOf('*');
                String prefix = imp.substring(0, cut);
                while (prefix.endsWith(sep) || prefix.endsWith(".")) {
                    prefix = prefix.substring(0, prefix.length() - (prefix.endsWith(sep) ? sep.length() : 1));
                }
                ctx.wildcardPrefixes.add(prefix);
            } else {
                ctx.importedTypes.putIfAbsent(lastSegment(imp), imp);
            }
        }
    }

    private static boolean looksLikePath(String imp) {
        if (imp.startsWith("./") || imp.startsWith("../") || imp.startsWith("/")) return true;
        if (imp.contains("/")) return true;
        int dot = imp.lastIndexOf('.');
        if (dot <= 0) return false;
        String ext = imp.substring(dot + 1).toLowerCase();
        return ext.equals("h") || ext.equals("hpp") || ext.equals("hh") || ext.equals("cpp")
                || ext.equals("c") || ext.equals("js") || ext.equals("ts") || ext.equals("inl");
    }

    // ---------------------------------------------------------- layer 3 edges

    private void resolveFile(FileContext ctx) {
        resolveCalls(ctx);
        for (FileFacts.Ref ref : ctx.facts.refs()) {
            String owner = ownerQname(ctx, ref.ownerName);
            if (owner == null) continue;
            String target = resolve(ctx, ref.name);
            if (target == null) continue;
            if (target.equals(owner)) continue;
            graph.addEdge(Layer.TYPE, owner, target, ref.kind, ref.count);
        }
    }

    /** Files the typed names declared inside one type under that type's node. */
    private void recordFieldTypes(FileFacts facts, String nestedName, String typeQname) {
        String prefix = nestedName + '\u001f';
        for (Map.Entry<String, String> entry : facts.memberFieldTypes.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            fieldTypes.computeIfAbsent(typeQname, k -> new HashMap<>())
                    .putIfAbsent(entry.getKey().substring(prefix.length()), entry.getValue());
        }
    }

    /**
     * Creates the layer-4 nodes for the callables declared in one file. The enclosing type
     * is found by name because C++ defines members away from their declaration, so
     * {@code void Facade::run()} in one folder belongs to {@code Facade} in another.
     */
    private void createMemberNodes(FileContext ctx) {
        adoptImplementationFile(ctx);
        for (FileFacts.Decl decl : ctx.memberDecls) {
            String nested = decl.nestedName;
            int cut = nested.lastIndexOf('.');
            String simple = cut < 0 ? nested : nested.substring(cut + 1);
            String enclosing = cut < 0 ? null : nested.substring(0, cut);

            String typeQname;
            if (enclosing == null) {
                // a free function: it belongs to the file that holds it
                typeQname = ctx.primaryOwner;
            } else {
                String local = ctx.layer2Qname + '#' + enclosing;
                typeQname = graph.hasNode(Layer.TYPE, local) ? local : resolve(ctx, enclosing);
            }
            if (typeQname == null) continue;

            String qname = typeQname + "::" + simple;
            GNode node = graph.addNode(new GNode(Layer.MEMBER, decl.kind, simple, qname, typeQname));
            node.path = ctx.facts.relPath;
            node.lang = ctx.facts.lang;
            node.loc = Math.max(node.loc, decl.endLine - decl.startLine + 1);
            node.addFile(ctx.facts.relPath,
                    decl.endLine > decl.startLine ? GNode.FileRef.IMPLEMENTATION
                            : GNode.FileRef.DECLARATION,
                    decl.endLine - decl.startLine + 1);
            ctx.members.put(nested, new String[]{qname, typeQname});
        }
    }

    /** Turns call sites into layer-4 edges between callables. */
    private void resolveCalls(FileContext ctx) {
        for (FileFacts.Call call : ctx.facts.calls()) {
            String[] source = call.ownerName == null ? null : ctx.members.get(call.ownerName);
            if (source == null) continue;                      // call outside any callable

            String targetType;
            if (call.receiver == null) {
                targetType = source[1];                        // bare or this call
            } else {
                // a local in this file, else a field of the type being implemented, else
                // the receiver may itself be a type name used statically
                String written = ctx.facts.variableTypes.get(call.receiver);
                if (written == null) {
                    written = fieldTypes.getOrDefault(source[1], Map.of()).get(call.receiver);
                }
                targetType = resolve(ctx, written != null ? written : call.receiver);
            }
            if (targetType == null) continue;

            String targetMember = targetType + "::" + call.callee;
            if (!graph.hasNode(Layer.MEMBER, targetMember)) continue;
            graph.addEdge(Layer.MEMBER, source[0], targetMember, EdgeKind.CALL, call.count);
            if (!targetType.equals(source[1])) {
                graph.addEdge(Layer.TYPE, source[1], targetType, EdgeKind.CALL, call.count);
            }
        }
    }

    /**
     * Points an implementation file at the type it implements, and credits that type with
     * the file's lines so a class is not reported as 50 lines when 150 of it live in a
     * .cpp next door. Falls back to a node for the file itself if nothing resolves, so a
     * file never silently disappears from the map.
     */
    private void adoptImplementationFile(FileContext ctx) {
        if (ctx.primaryOwner != null || ctx.outOfLineOwners.isEmpty()) return;
        for (String owner : ctx.outOfLineOwners) {
            String resolved = resolve(ctx, owner);
            if (resolved == null) continue;
            ctx.primaryOwner = resolved;
            GNode node = graph.node(Layer.TYPE, resolved);
            if (node != null) {
                node.loc += ctx.facts.loc;
                node.addFile(ctx.facts.relPath, GNode.FileRef.IMPLEMENTATION, ctx.facts.loc);
            }
            return;
        }
        String fileName = fileName(ctx.facts.relPath);
        String qname = ctx.layer2Qname + '#' + fileName;
        GNode node = graph.addNode(new GNode(Layer.TYPE, NodeKind.FILE, fileName, qname,
                ctx.layer2Qname));
        node.path = ctx.facts.relPath;
        node.lang = ctx.facts.lang;
        node.loc = ctx.facts.loc;
        node.addFile(ctx.facts.relPath, GNode.FileRef.FILE, ctx.facts.loc);
        ctx.primaryOwner = qname;
    }

    /** Which layer-3 node a reference belongs to. */
    private String ownerQname(FileContext ctx, String ownerName) {
        if (ownerName == null) return ctx.primaryOwner;
        // a reference inside a method is a fact about the method's type
        String[] member = ctx.members.get(ownerName);
        if (member != null) return member[1];
        String local = ctx.layer2Qname + '#' + ownerName;
        if (graph.hasNode(Layer.TYPE, local)) return local;
        // out-of-line member definition whose type lives in another file, e.g. a header
        String resolved = resolve(ctx, ownerName);
        return resolved != null ? resolved : ctx.primaryOwner;
    }

    /**
     * Maps a name as written in the source onto a layer-3 node, or null when it is not
     * something this project declares (JDK types, STL, third-party) or is ambiguous.
     */
    private String resolve(FileContext ctx, String rawName) {
        String hit = resolveName(ctx, rawName);
        if (hit != null) resolvedRefs++;
        return hit;
    }

    private String resolveName(FileContext ctx, String rawName) {
        String name = rawName;
        // C++ and Rust write A::B, and a leading :: is just the global scope
        while (name.startsWith(":") || name.startsWith(".") || name.startsWith("\\")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) return null;
        String simple = lastSegment(name);

        // 1. already fully qualified
        String hit = accept(ctx, index.byFqn(name));
        if (hit != null) return hit;

        // 2. an import names it explicitly
        String imported = ctx.importedTypes.get(simple);
        if (imported != null) {
            hit = accept(ctx, index.byFqn(imported));
            if (hit != null) return hit;
        }
        for (String prefix : ctx.wildcardPrefixes) {
            hit = accept(ctx, index.byFqn(prefix + ctx.separator + simple));
            if (hit != null) return hit;
        }

        // 3. same package/namespace
        if (!ctx.symbolNamespace.isEmpty()) {
            hit = accept(ctx, index.byFqn(ctx.symbolNamespace + ctx.separator + name));
            if (hit != null) return hit;
        }

        // 4. a sibling in the same layer-2 container
        String sibling = ctx.layer2Qname + '#' + name;
        if (graph.hasNode(Layer.TYPE, sibling)) {
            hit = accept(ctx, sibling);
            if (hit != null) return hit;
        }

        // 5. an included/relative-imported file declares it
        for (String imp : ctx.pathImports) {
            for (String candidate : index.byPath(normalizePath(imp, directoryOf(ctx.facts.relPath)))) {
                if (!lastSegment(qnameSimpleName(candidate)).equals(simple)) continue;
                hit = accept(ctx, candidate);
                if (hit != null) return hit;
            }
        }

        // 6. unique by simple name, preferring the module we are already in
        List<String> candidates = new ArrayList<>();
        for (String candidate : index.bySimpleName(simple)) {
            if (accept(ctx, candidate) != null) candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            unresolvedRefs++;
            return null;
        }
        if (candidates.size() == 1) return candidates.get(0);
        String onlyLocal = null;
        int localCount = 0;
        for (String candidate : candidates) {
            if (ctx.moduleName.equals(index.moduleOf(candidate))) {
                localCount++;
                onlyLocal = candidate;
            }
        }
        if (localCount == 1) return onlyLocal;
        ambiguousRefs++;
        return null;
    }

    /** Drops a candidate whose language could not see the referencing file's types. */
    private String accept(FileContext ctx, String candidate) {
        if (candidate == null) return null;
        return ctx.family.equals(index.familyOf(candidate)) ? candidate : null;
    }

    // ------------------------------------------------------- layers 2 and 1

    /**
     * Groups a module's packages by their name path, so a module with hundreds of them
     * opens into a handful of readable steps instead of one wall.
     */
    private void groupPackagesByName() {
        ContainerTree tree = new ContainerTree(graph);
        tree.build();
        groupsCreated = tree.groupsCreated;
    }

    /**
     * Files every edge under the view it belongs to, and creates the rolled-up edges the
     * higher views need.
     *
     * <p>An edge is drawn in the view of the nearest common ancestor of its endpoints, so
     * for each recorded fact this walks both parent chains, finds where they first diverge,
     * and records an edge between those two nodes. One rule covers every level: two classes
     * in a package, two packages in a group, two groups in a module, two modules in the
     * project - and the mixed case where a class and a sub-package are siblings.
     */
    private void assignEdgeViews() {
        List<GEdge> facts = new ArrayList<>();
        facts.addAll(graph.edges(Layer.TYPE).values());
        facts.addAll(graph.edges(Layer.MEMBER).values());

        for (GEdge fact : facts) {
            GNode src = graph.node(fact.layer, fact.srcQname);
            GNode dst = graph.node(fact.layer, fact.dstQname);
            if (src == null || dst == null) continue;

            List<GNode> up = ancestry(src);
            List<GNode> down = ancestry(dst);
            int i = 0;
            while (i < up.size() && i < down.size() && up.get(i) == down.get(i)) i++;
            if (i >= up.size() || i >= down.size()) continue;   // one contains the other

            GNode a = up.get(i);
            GNode b = down.get(i);
            String parent = i == 0 ? "" : up.get(i - 1).qname;
            if (a == src && b == dst) {
                fact.parentQname = parent;                      // already at its frontier
                continue;
            }
            graph.addEdge(a.layer, a.qname, b.qname, fact.kind, fact.weight, parent);
        }
    }

    /** The chain from the outermost container down to the node itself. */
    private List<GNode> ancestry(GNode node) {
        List<GNode> chain = new ArrayList<>(6);
        GNode current = node;
        for (int guard = 0; current != null && guard < 32; guard++) {
            chain.add(current);
            current = graph.parentOf(current);
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    private void addDeclaredModuleDeps() {
        for (String[] pair : modules.declaredDependencyPairs()) {
            if (graph.hasNode(Layer.MODULE, pair[0]) && graph.hasNode(Layer.MODULE, pair[1])) {
                graph.addEdge(Layer.MODULE, pair[0], pair[1], EdgeKind.DECLARED_DEP, 1, "");
            }
        }
    }

    /** A member whose type did not survive has nowhere to live. */
    private void pruneOrphanMembers() {
        graph.nodes(Layer.MEMBER).values()
                .removeIf(m -> !graph.hasNode(Layer.TYPE, m.parentQname));
    }

    /**
     * A folder whose files all turned out to be implementations of types declared
     * elsewhere has nothing left to show, and an empty bubble is worse than no bubble.
     */
    private void pruneEmptyPackages() {
        Set<String> occupied = new HashSet<>();
        for (GNode type : graph.nodes(Layer.TYPE).values()) occupied.add(type.parentQname);
        // a grouping level holds containers rather than types, so it counts as occupied
        // when something still hangs off it
        for (int pass = 0; pass < 24; pass++) {
            int before = occupied.size();
            for (GNode pkg : graph.nodes(Layer.PACKAGE).values()) {
                if (occupied.contains(pkg.qname)) occupied.add(pkg.parentQname);
            }
            if (occupied.size() == before) break;
        }
        graph.nodes(Layer.PACKAGE).values().removeIf(pkg -> !occupied.contains(pkg.qname));
    }

    /** Aggregator poms and CMake files that hold no code and no dependencies are noise. */
    private void pruneEmptyModules() {
        Set<String> withContent = new HashSet<>();
        for (GNode pkg : graph.nodes(Layer.PACKAGE).values()) withContent.add(pkg.parentQname);
        for (GEdge e : graph.edges(Layer.MODULE).values()) {
            withContent.add(e.srcQname);
            withContent.add(e.dstQname);
        }
        graph.nodes(Layer.MODULE).values().removeIf(m -> !withContent.contains(m.qname));
    }

    // ---------------------------------------------------------------- helpers

    private LangSpec specFor(String relPath) {
        int dot = relPath.lastIndexOf('.');
        if (dot < 0) return null;
        return registry.forExtension(relPath.substring(dot + 1));
    }

    private static String directoryOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? "" : relPath.substring(0, slash);
    }

    private static String fileName(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? relPath : relPath.substring(slash + 1);
    }

    /** last segment of a dotted, ::-separated or backslash-separated name. */
    private static String lastSegment(String name) {
        int best = -1;
        for (String sep : new String[]{"::", ".", "\\"}) {
            int idx = name.lastIndexOf(sep);
            if (idx >= 0) best = Math.max(best, idx + sep.length());
        }
        return best <= 0 ? name : name.substring(best);
    }

    /** the type name part of a layer-3 qname ({@code mod/pkg#Name} -> {@code Name}). */
    private static String qnameSimpleName(String qname) {
        int hash = qname.lastIndexOf('#');
        return hash < 0 ? qname : qname.substring(hash + 1);
    }

    /** Resolves {@code ./x} and {@code ../x} so an include matches the file index. */
    private static String normalizePath(String imp, String fromDir) {
        String path = imp.replace('\\', '/');
        if (!path.startsWith("./") && !path.startsWith("../")) return path;
        List<String> parts = new ArrayList<>();
        if (!fromDir.isEmpty()) {
            for (String part : fromDir.split("/")) {
                if (!part.isEmpty()) parts.add(part);
            }
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }
}
