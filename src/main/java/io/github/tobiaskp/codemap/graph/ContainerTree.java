package io.github.tobiaskp.codemap.graph;

import io.github.tobiaskp.codemap.model.CodeGraph;
import io.github.tobiaskp.codemap.model.GNode;
import io.github.tobiaskp.codemap.model.Layer;
import io.github.tobiaskp.codemap.model.NodeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a module's flat list of packages into the tree their names already describe.
 *
 * <p>A module with 365 packages is not a view anyone can read, but those packages are not
 * really flat: {@code com.example.reporting.editor} sits under
 * {@code com.example.reporting}, and a developer already navigates it that way.
 * Grouping on the name path turns 365 siblings into 23, then again at each step down.
 *
 * <p>Two rules keep the tree useful rather than merely deep:
 * <ul>
 *   <li>a level is only created where it branches - a chain of single-child prefixes like
 *       {@code com} / {@code com.example} / {@code com.example.app} is collapsed into one step</li>
 *   <li>a prefix that is itself a real package keeps its own types; opening it shows its
 *       classes and its sub-packages together, the way a folder shows files and folders</li>
 * </ul>
 */
final class ContainerTree {

    /** one node of the name trie while the tree is being worked out. */
    private static final class Branch {
        final String path;                       // full container label, e.g. "a.b.c"
        final Map<String, Branch> children = new LinkedHashMap<>();
        GNode realPackage;                       // set when a package with this exact name exists
        int descendantPackages;

        Branch(String path) {
            this.path = path;
        }
    }

    private final CodeGraph graph;
    /** how many synthetic grouping levels were introduced. */
    int groupsCreated;

    ContainerTree(CodeGraph graph) {
        this.graph = graph;
    }

    /** Rebuilds layer 2 as a tree, one module at a time. */
    void build() {
        Map<String, List<GNode>> byModule = new HashMap<>();
        for (GNode pkg : graph.nodes(Layer.PACKAGE).values()) {
            byModule.computeIfAbsent(pkg.parentQname, k -> new ArrayList<>()).add(pkg);
        }
        for (Map.Entry<String, List<GNode>> entry : byModule.entrySet()) {
            buildModule(entry.getKey(), entry.getValue());
        }
    }

    private void buildModule(String moduleQname, List<GNode> packages) {
        if (packages.size() < 8) return;          // already a readable view, leave it flat

        Branch root = new Branch("");
        for (GNode pkg : packages) {
            String label = containerLabel(pkg);
            if (label.isEmpty() || label.equals(".")) continue;
            Branch branch = root;
            for (String segment : split(label)) {
                String path = branch.path.isEmpty() ? segment : branch.path + '.' + segment;
                branch = branch.children.computeIfAbsent(segment, k -> new Branch(path));
                branch.descendantPackages++;
            }
            branch.realPackage = pkg;
        }

        attach(moduleQname, root, moduleQname);
    }

    /**
     * Walks the trie, creating a node per branching level and re-parenting the packages
     * that hang off it. Returns nothing: everything is wired through parentQname.
     */
    private void attach(String moduleQname, Branch branch, String parentQname) {
        for (Branch child : branch.children.values()) {
            Branch target = compress(child);
            String qname;
            if (target.realPackage != null) {
                // a real package: keep it, and hang whatever is below it underneath
                target.realPackage.parentQname = parentQname;
                qname = target.realPackage.qname;
            } else if (target.children.isEmpty()) {
                continue;                          // nothing to show and nothing below
            } else {
                qname = createGroup(moduleQname, target, parentQname);
            }
            attach(moduleQname, target, qname);
        }
    }

    /**
     * Skips past prefixes that neither hold a package nor branch. Without this, three
     * levels of {@code com} / {@code com.example} / {@code com.example.app} sit between a module
     * and anything worth looking at.
     */
    private Branch compress(Branch branch) {
        Branch current = branch;
        while (current.realPackage == null && current.children.size() == 1) {
            current = current.children.values().iterator().next();
        }
        return current;
    }

    private String createGroup(String moduleQname, Branch branch, String parentQname) {
        String qname = moduleQname + '/' + branch.path + "/*";
        GNode group = graph.addNode(new GNode(Layer.PACKAGE, NodeKind.GROUP,
                branch.path, qname, parentQname));
        group.lang = "";
        groupsCreated++;
        return qname;
    }

    /** the package's own name, with the module prefix removed. */
    private static String containerLabel(GNode pkg) {
        int slash = pkg.qname.indexOf('/');
        return slash < 0 ? pkg.name : pkg.qname.substring(slash + 1);
    }

    /** Package names use dots, folder names use slashes; both are path separators here. */
    private static List<String> split(String label) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '.' || c == '/') {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }
}
