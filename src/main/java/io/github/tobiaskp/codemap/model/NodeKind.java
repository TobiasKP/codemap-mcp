package io.github.tobiaskp.codemap.model;

/**
 * What a node actually is. The layer says how far you have to zoom in to see it,
 * the kind says which glyph to draw and how to describe it.
 */
public enum NodeKind {
    /** layer 1: a build unit (maven module, cmake target, npm package, ...). */
    MODULE,
    /** layer 2: a language-level package/namespace. */
    PACKAGE,
    /** layer 2: a directory, used when the language has no package concept. */
    FOLDER,
    /** layer 2: a name-path level that holds other containers but no code of its own. */
    GROUP,

    /** layer 3 declarations. */
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    STRUCT,
    ANNOTATION,
    TRAIT,
    PROTOCOL,
    /** layer 3: a source file that holds code but declares no type (C, scripts, ...). */
    FILE,

    /** layer 4: a callable declared inside a type. */
    METHOD,
    CONSTRUCTOR,
    /** layer 4: a callable not inside any type, owned by its file node. */
    FUNCTION;

    /** True for the callables that live on layer 4. */
    public boolean isCallable() {
        return this == METHOD || this == CONSTRUCTOR || this == FUNCTION;
    }

    public boolean isType() {
        return switch (this) {
            case MODULE, PACKAGE, FOLDER, GROUP, METHOD, CONSTRUCTOR, FUNCTION -> false;
            default -> true;
        };
    }
}
