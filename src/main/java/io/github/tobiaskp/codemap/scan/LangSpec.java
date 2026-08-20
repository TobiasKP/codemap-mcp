package io.github.tobiaskp.codemap.scan;

import java.util.List;
import java.util.Set;

/**
 * Everything language specific in one place: which files belong to it, which
 * tree-sitter grammar parses them, and the query patterns that pull the facts out.
 *
 * <p>Patterns are kept as a list rather than one blob so that a pattern referring to a
 * node type a newer or older grammar does not have can be dropped individually instead
 * of taking the whole language down with it.
 */
public final class LangSpec {

    /** How the layer-2 node for a file is decided. */
    public enum ContainerStyle {
        /** the language has real packages/namespaces: use the declared one. */
        PACKAGE,
        /** no package concept, or the directory is the truth: use the folder path. */
        FOLDER
    }

    public final String id;
    public final Set<String> extensions;
    /** fully qualified name of the grammar class inside the tree-sitter jar. */
    public final String grammarClass;
    public final ContainerStyle containerStyle;
    /** separator between container and type name, e.g. "." or "::". */
    public final String separator;
    public final List<String> patterns;
    /**
     * True when this language has {@code @call.name} patterns. Where it does, call edges
     * come from those - richer, and member-level - and the coarse {@code @ref.CALLOBJ}
     * receiver heuristic is downgraded to a plain type reference so the same call is not
     * counted twice on layer 3.
     */
    public final boolean hasCallPatterns;

    public LangSpec(String id, Set<String> extensions, String grammarClass,
                    ContainerStyle containerStyle, String separator, List<String> patterns) {
        this.id = id;
        this.extensions = extensions;
        this.grammarClass = grammarClass;
        this.containerStyle = containerStyle;
        this.separator = separator;
        this.patterns = patterns;
        this.hasCallPatterns = patterns.stream().anyMatch(p -> p.contains("@call.name"));
    }
}
