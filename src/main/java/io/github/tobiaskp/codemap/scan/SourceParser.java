package io.github.tobiaskp.codemap.scan;

import io.github.tobiaskp.codemap.model.EdgeKind;
import io.github.tobiaskp.codemap.model.NodeKind;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns one source file into {@link FileFacts} using tree-sitter.
 *
 * <p>Not thread safe: tree-sitter parsers and cursors are stateful, so the scanner keeps
 * one instance per thread and language.
 */
public final class SourceParser {

    /** grammars are immutable once loaded, so one instance per language is shared. */
    private static final Map<String, TSLanguage> LANGUAGES = new ConcurrentHashMap<>();
    /** query text that actually compiled against this grammar build. */
    private static final Map<String, String> VALIDATED_QUERIES = new ConcurrentHashMap<>();
    /** patterns rejected by the grammar, reported once in verbose mode. */
    private static final Map<String, List<String>> REJECTED = new ConcurrentHashMap<>();

    private static final int MAX_INLINE_TEXT = 200;
    private static final int MAX_NAME_DEPTH = 6;

    private final LangSpec spec;
    private final TSParser parser;
    private final TSQuery query;
    private final TSQueryCursor cursor = new TSQueryCursor();

    public SourceParser(LangSpec spec) {
        this.spec = spec;
        TSLanguage language = language(spec);
        this.parser = new TSParser();
        this.parser.setLanguage(language);
        this.query = new TSQuery(language, validatedQuery(spec));
    }

    public static TSLanguage language(LangSpec spec) {
        return LANGUAGES.computeIfAbsent(spec.id, id -> {
            try {
                return (TSLanguage) Class.forName(spec.grammarClass).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("grammar not on classpath: " + spec.grammarClass, e);
            }
        });
    }

    /** True when the grammar jar for this language is present and loadable. */
    public static boolean isAvailable(LangSpec spec) {
        try {
            language(spec);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static List<String> rejectedPatterns(String langId) {
        return REJECTED.getOrDefault(langId, List.of());
    }

    /**
     * Compiles the patterns one by one and keeps the ones the grammar accepts. Grammar
     * versions rename node types now and then; this way a stale pattern costs us one
     * kind of edge instead of the whole language.
     */
    private static String validatedQuery(LangSpec spec) {
        return VALIDATED_QUERIES.computeIfAbsent(spec.id, id -> {
            TSLanguage language = language(spec);
            StringBuilder good = new StringBuilder();
            List<String> bad = new ArrayList<>();
            for (String pattern : spec.patterns) {
                try {
                    new TSQuery(language, pattern);
                    good.append(pattern).append('\n');
                } catch (RuntimeException e) {
                    bad.add(pattern);
                }
            }
            if (!bad.isEmpty()) REJECTED.put(id, bad);
            if (good.length() == 0) good.append("(ERROR) @unused");
            return good.toString();
        });
    }

    // ------------------------------------------------------------------- parse

    /** a declaration: the kind, its name node, and the node whose extent it spans. */
    private record DeclCap(String kind, String name, int start, int end, int startLine, int endLine) {
    }

    /** an out-of-line member definition whose body belongs to a type declared elsewhere. */
    private record OwnerCap(String name, int start, int end) {
    }

    /** a reference to a type, still unresolved. */
    private record RefCap(String kind, TSNode node) {
    }

    /**
     * A call site keyed by where its name token sits. Grammars need one pattern for a
     * bare call and another for a call with a receiver, and both fire on the same token,
     * so the position is the identity and the receiver-bearing match wins.
     */
    private static final class CallCap {
        String receiver;
        final String callee;
        final int offset;

        CallCap(String callee, int offset) {
            this.callee = callee;
            this.offset = offset;
        }
    }

    /** a byte range that owns the references inside it. */
    private record OwnerRange(int start, int end, String ownerName) {
    }

    public FileFacts parse(String relPath, byte[] utf8) {
        String source = new String(utf8, StandardCharsets.UTF_8);
        FileFacts facts = new FileFacts(relPath, spec.id);
        facts.loc = countLines(utf8);

        TSTree tree = parser.parseString(null, source);
        TSNode root = tree.getRootNode();
        if (root.isNull()) return facts;
        facts.hadParseError = root.hasError();

        List<DeclCap> declCaps = new ArrayList<>();
        List<RefCap> refCaps = new ArrayList<>();
        List<OwnerCap> ownerCaps = new ArrayList<>();
        List<String[]> varPairs = new ArrayList<>();
        Map<Integer, CallCap> callCaps = new java.util.LinkedHashMap<>();

        cursor.exec(query, root);
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            TSNode scope = null, declName = null, owner = null, ownerScope = null;
            TSNode varName = null, varType = null;
            TSNode callName = null, callRecv = null;
            String declKind = null;

            for (TSQueryCapture c : match.getCaptures()) {
                String capture = query.getCaptureNameForId(c.getIndex());
                TSNode node = c.getNode();
                if (capture.startsWith("ref.")) {
                    refCaps.add(new RefCap(capture.substring(4), node));
                } else if (capture.startsWith("decl.")) {
                    declName = node;
                    declKind = capture.substring(5);
                } else if (capture.equals("scope")) {
                    scope = node;
                } else if (capture.equals("owner")) {
                    owner = node;
                } else if (capture.equals("ownerscope")) {
                    ownerScope = node;
                } else if (capture.equals("container")) {
                    if (facts.container.isEmpty()) facts.container = text(utf8, node).trim();
                } else if (capture.equals("import")) {
                    String imp = cleanImport(text(utf8, node));
                    if (!imp.isEmpty()) facts.imports.add(imp);
                } else if (capture.equals("var.name")) {
                    varName = node;
                } else if (capture.equals("var.type")) {
                    varType = node;
                } else if (capture.equals("call.name")) {
                    callName = node;
                } else if (capture.equals("call.recv")) {
                    callRecv = node;
                }
            }

            if (declName != null) {
                // the name node identifies the type; the scope node covers its whole body,
                // which is both the attribution range and the line count for the map
                TSNode range = scope != null ? scope : declName;
                declCaps.add(new DeclCap(declKind, text(utf8, declName).trim(),
                        range.getStartByte(), range.getEndByte(),
                        range.getStartPoint().getRow() + 1, range.getEndPoint().getRow() + 1));
            }
            if (owner != null && ownerScope != null) {
                ownerCaps.add(new OwnerCap(text(utf8, owner).trim(),
                        ownerScope.getStartByte(), ownerScope.getEndByte()));
            }
            if (callName != null) {
                int offset = callName.getStartByte();
                String callee = text(utf8, callName).trim();
                CallCap cap = callCaps.get(offset);
                if (cap == null) {
                    cap = new CallCap(callee, offset);
                    callCaps.put(offset, cap);
                }
                if (callRecv != null && cap.receiver == null) {
                    cap.receiver = text(utf8, callRecv).trim();
                }
            }
            if (varName != null && varType != null) {
                List<String> types = new ArrayList<>(2);
                collectNames(varType, utf8, types, 0);
                if (!types.isEmpty()) {
                    varPairs.add(new String[]{
                            text(utf8, varName).trim(),
                            types.get(0),
                            String.valueOf(varName.getStartByte()),
                    });
                }
            }
        }

        List<OwnerRange> ranges = buildDecls(facts, declCaps, ownerCaps);
        Map<String, String> varTypes = facts.variableTypes;
        for (String[] pair : varPairs) {
            varTypes.putIfAbsent(pair[0], pair[1]);
            // also file it under the declaration that owns it, so a field stays reachable
            // from an implementation file that never sees the declaration
            String owner = innermostOwner(ranges, Integer.parseInt(pair[2]));
            if (owner != null) {
                facts.memberFieldTypes.putIfAbsent(owner + '\u001f' + pair[0], pair[1]);
            }
        }

        emitRefs(facts, refCaps, ranges, varTypes, utf8);
        emitCalls(facts, callCaps.values(), ranges);
        return facts;
    }

    /** Creates the declarations, works out nesting, and returns the attribution ranges. */
    private List<OwnerRange> buildDecls(FileFacts facts, List<DeclCap> declCaps,
                                        List<OwnerCap> ownerCaps) {
        List<DeclCap> ordered = new ArrayList<>(declCaps.size());
        for (DeclCap cap : declCaps) {
            if (!cap.name().isEmpty()) ordered.add(cap);
        }
        // outermost first, so a nested type always finds its parent already built
        ordered.sort(Comparator.comparingInt(DeclCap::start)
                .thenComparing(Comparator.comparingInt(DeclCap::end).reversed()));

        List<OwnerRange> ranges = new ArrayList<>();
        // Out-of-line definitions come first: `void Facade::run()` has to be in place
        // before the member declared inside it can find Facade as its enclosing name.
        for (OwnerCap cap : ownerCaps) {
            if (cap.name().isEmpty()) continue;
            ranges.add(new OwnerRange(cap.start(), cap.end(), cap.name()));
        }
        Set<String> seen = new HashSet<>();
        for (DeclCap cap : ordered) {
            String enclosing = innermostOwner(ranges, cap.start());
            String nested = enclosing == null ? cap.name() : enclosing + '.' + cap.name();
            // a type declared twice in one file (forward declarations) is still one node
            if (seen.add(nested)) {
                facts.decls.add(new FileFacts.Decl(cap.name(), nested, nodeKind(cap.kind()),
                        cap.start(), cap.end(), cap.startLine(), cap.endLine()));
            }
            ranges.add(new OwnerRange(cap.start(), cap.end(), nested));
        }

        for (OwnerCap cap : ownerCaps) {
            if (!cap.name().isEmpty() && !seen.contains(cap.name())) {
                facts.outOfLineOwners.add(cap.name());
            }
        }
        ranges.sort(Comparator.comparingInt(OwnerRange::start)
                .thenComparing(Comparator.comparingInt(OwnerRange::end).reversed()));
        return ranges;
    }

    private void emitRefs(FileFacts facts, List<RefCap> refCaps, List<OwnerRange> ranges,
                          Map<String, String> varTypes, byte[] utf8) {
        List<String> names = new ArrayList<>(4);
        for (RefCap cap : refCaps) {
            TSNode node = cap.node();
            String owner = innermostOwner(ranges, node.getStartByte());

            if (cap.kind().equals("CALLOBJ")) {
                // foo.bar(): the interesting part is what foo's type is
                String receiver = text(utf8, node).trim();
                String type = varTypes.get(receiver);
                if (type != null) {
                    facts.addRef(owner, type, EdgeKind.CALL);
                } else if (looksLikeName(receiver)) {
                    // not a known variable, so it may be a type used statically
                    facts.addRef(owner, receiver, EdgeKind.CALL);
                }
                continue;
            }

            EdgeKind kind = edgeKind(cap.kind());
            names.clear();
            collectNames(node, utf8, names, 0);
            for (String name : names) facts.addRef(owner, name, kind);
        }
    }

    private static String innermostOwner(List<OwnerRange> ranges, int offset) {
        String best = null;
        int bestSize = Integer.MAX_VALUE;
        for (OwnerRange r : ranges) {
            if (r.start() > offset) continue;
            if (offset >= r.end()) continue;
            int size = r.end() - r.start();
            // An out-of-line definition and the member declared in it span exactly the
            // same bytes, so on a tie prefer the longer, more qualified name: the body
            // belongs to Facade.run, not merely to Facade.
            boolean better = size < bestSize
                    || (size == bestSize && best != null && r.ownerName().length() > best.length());
            if (better) {
                bestSize = size;
                best = r.ownerName();
            }
        }
        return best;
    }

    /** Attributes each call to the declaration it sits in. */
    private void emitCalls(FileFacts facts, Iterable<CallCap> caps, List<OwnerRange> ranges) {
        for (CallCap cap : caps) {
            if (!looksLikeName(cap.callee)) continue;
            String owner = innermostOwner(ranges, cap.offset);
            String receiver = cap.receiver != null && looksLikeName(cap.receiver)
                    ? cap.receiver : null;
            facts.addCall(owner, receiver, cap.callee);
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Pulls every plausible type name out of a captured node. {@code Map<String, Widget>}
     * yields Map, String and Widget; names that are not project types get dropped later,
     * so casting a wide net here only costs a hash lookup.
     */
    private static void collectNames(TSNode node, byte[] utf8, List<String> out, int depth) {
        if (node.isNull() || depth > MAX_NAME_DEPTH || out.size() > 24) return;
        int len = node.getEndByte() - node.getStartByte();
        if (len > 0 && len <= MAX_INLINE_TEXT) {
            String text = text(utf8, node).trim();
            if (looksLikeName(text) && !out.contains(text)) out.add(text);
        }
        int children = node.getNamedChildCount();
        if (children == 0) return;
        for (int i = 0; i < children; i++) collectNames(node.getNamedChild(i), utf8, out, depth + 1);
    }

    private static boolean looksLikeName(String s) {
        if (s.isEmpty() || s.length() > 160) return false;
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':'
                    || c == '\\' || c == '$';
            if (!ok) return false;
        }
        return true;
    }

    /** Strips the quoting around an import so both {@code "a/b.h"} and {@code <a>} work. */
    private static String cleanImport(String raw) {
        String s = raw.trim();
        while (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            boolean quoted = (a == '"' && b == '"') || (a == '\'' && b == '\'') || (a == '<' && b == '>')
                    || (a == '`' && b == '`');
            if (!quoted) break;
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String text(byte[] utf8, TSNode node) {
        int start = Math.max(0, node.getStartByte());
        int end = Math.min(utf8.length, node.getEndByte());
        if (end <= start) return "";
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }

    private static int countLines(byte[] utf8) {
        int lines = utf8.length == 0 ? 0 : 1;
        for (byte b : utf8) {
            if (b == '\n') lines++;
        }
        return lines;
    }

    private static NodeKind nodeKind(String captureSuffix) {
        try {
            return NodeKind.valueOf(captureSuffix);
        } catch (IllegalArgumentException e) {
            return NodeKind.CLASS;
        }
    }

    private static EdgeKind edgeKind(String captureSuffix) {
        try {
            return EdgeKind.valueOf(captureSuffix);
        } catch (IllegalArgumentException e) {
            return EdgeKind.TYPE_REF;
        }
    }
}
