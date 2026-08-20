package io.github.tobiaskp.codemap.model;

/** The four levels of the map. Layer numbers are what the DB and the API speak. */
public enum Layer {
    MODULE(1),
    PACKAGE(2),
    TYPE(3),
    /** the callables inside a type: methods, constructors, free functions. */
    MEMBER(4);

    public final int code;

    Layer(int code) {
        this.code = code;
    }

    public static Layer of(int code) {
        for (Layer l : values()) {
            if (l.code == code) return l;
        }
        throw new IllegalArgumentException("no layer " + code);
    }
}
