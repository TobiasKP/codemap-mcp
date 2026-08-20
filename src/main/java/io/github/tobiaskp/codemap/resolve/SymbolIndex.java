package io.github.tobiaskp.codemap.resolve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the project declares, indexed the handful of ways source code refers to it:
 * by fully qualified name, by simple name, and by the file that declares it.
 *
 * <p>Only names that come out of this index become edges, which is what keeps the graph
 * to project-internal facts instead of drowning it in JDK and STL types.
 */
public final class SymbolIndex {

    /** language-level FQN, e.g. {@code com.example.foo.Widget} -> type node qname. */
    private final Map<String, String> byFqn = new HashMap<>();
    /** simple name, e.g. {@code Widget} -> every type node with that name. */
    private final Map<String, List<String>> bySimpleName = new HashMap<>();
    /** file path spellings -> type nodes declared in that file, for include/relative imports. */
    private final Map<String, List<String>> byPath = new HashMap<>();
    /** type node qname -> owning module, used to break ties in favour of the local module. */
    private final Map<String, String> moduleOf = new HashMap<>();
    /** type node qname -> language family, so a Java name never matches a JS file. */
    private final Map<String, String> familyOf = new HashMap<>();

    public void addType(String nodeQname, String simpleName, String fqn, String module, String lang) {
        if (fqn != null && !fqn.isEmpty()) byFqn.putIfAbsent(fqn, nodeQname);
        bySimpleName.computeIfAbsent(simpleName, k -> new ArrayList<>(1)).add(nodeQname);
        moduleOf.put(nodeQname, module);
        familyOf.put(nodeQname, family(lang));
    }

    public String familyOf(String nodeQname) {
        return familyOf.get(nodeQname);
    }

    /**
     * Languages that can legitimately see each other's types. Without this, a Java
     * reference to {@code Context} happily matches a {@code Context.js} file that merely
     * shares a name, which is never a real dependency.
     */
    public static String family(String lang) {
        if (lang == null) return "";
        return switch (lang) {
            case "java", "kotlin", "scala", "groovy" -> "jvm";
            case "c", "cpp" -> "c";
            case "javascript", "typescript" -> "js";
            default -> lang;
        };
    }

    /** Registers the file a type was declared in, under every spelling an import may use. */
    public void addPathAlias(String relPath, String nodeQname) {
        String norm = relPath.replace('\\', '/');
        put(byPath, norm, nodeQname);
        put(byPath, stripExtension(norm), nodeQname);
        String base = norm.substring(norm.lastIndexOf('/') + 1);
        put(byPath, base, nodeQname);
        put(byPath, stripExtension(base), nodeQname);
    }

    private static void put(Map<String, List<String>> map, String key, String value) {
        if (key.isEmpty()) return;
        List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>(1));
        if (!list.contains(value)) list.add(value);
    }

    public String byFqn(String fqn) {
        return byFqn.get(fqn);
    }

    public List<String> bySimpleName(String simpleName) {
        return bySimpleName.getOrDefault(simpleName, List.of());
    }

    public List<String> byPath(String path) {
        return byPath.getOrDefault(path, List.of());
    }

    public String moduleOf(String nodeQname) {
        return moduleOf.get(nodeQname);
    }

    public int typeCount() {
        return moduleOf.size();
    }

    public static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1 ? path.substring(0, dot) : path;
    }
}
