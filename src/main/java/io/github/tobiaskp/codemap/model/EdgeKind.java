package io.github.tobiaskp.codemap.model;

/**
 * Why two nodes are connected. Ordered weakest -> strongest: when the same pair is
 * found several times, {@link #strongest} decides which label the edge carries.
 */
public enum EdgeKind {
    /**
     * layer 1 only: the build file declares the dependency. Ranked weakest on purpose, so
     * that an edge which is also backed by real code carries the code label and keeps
     * {@code DECLARED_DEP} visible in the breakdown instead.
     */
    DECLARED_DEP,
    /** the name of a project type turned up somewhere in a type position. */
    TYPE_REF,
    /** a field/member is declared with that type. */
    FIELD,
    /** a method is called on a receiver whose type resolved to the target. */
    CALL,
    /** {@code new Target(...)}. */
    NEW,
    IMPLEMENTS,
    EXTENDS;

    public static EdgeKind strongest(EdgeKind a, EdgeKind b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
