package io.github.tobiaskp.codemap.scan;

import io.github.tobiaskp.codemap.model.EdgeKind;
import io.github.tobiaskp.codemap.model.NodeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one source file told us. Purely syntactic: names as they were written, with no
 * attempt to resolve them yet - that needs the whole project.
 */
public final class FileFacts {

    /** A type declared in this file. */
    public static final class Decl {
        public final String simpleName;
        /** {@code Outer.Inner} for nested types, otherwise the same as simpleName. */
        public final String nestedName;
        public final NodeKind kind;
        public final int startByte, endByte;
        public final int startLine, endLine;

        public Decl(String simpleName, String nestedName, NodeKind kind,
                    int startByte, int endByte, int startLine, int endLine) {
            this.simpleName = simpleName;
            this.nestedName = nestedName;
            this.kind = kind;
            this.startByte = startByte;
            this.endByte = endByte;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    /**
     * A call site: who made the call, what it was called on, and the callable's name.
     * The receiver is the text as written ({@code helper}, {@code Foo}, or null for a bare
     * or {@code this} call); turning that into a type is resolution's job.
     */
    public static final class Call {
        /** nested name of the declaration containing the call; null at file level. */
        public final String ownerName;
        /** receiver as written, or null for a bare / this / self call. */
        public final String receiver;
        public final String callee;
        public int count = 1;

        Call(String ownerName, String receiver, String callee) {
            this.ownerName = ownerName;
            this.receiver = receiver;
            this.callee = callee;
        }
    }

    /**
     * A name used somewhere, together with which declaration used it. Identical uses are
     * folded into {@code count} while parsing, which keeps big projects in memory.
     */
    public static final class Ref {
        /** nested name of the declaration that owns this reference; null means file level. */
        public final String ownerName;
        /** the referenced name exactly as written, e.g. {@code Helper} or {@code a.b.Helper}. */
        public final String name;
        public final EdgeKind kind;
        public int count = 1;

        Ref(String ownerName, String name, EdgeKind kind) {
            this.ownerName = ownerName;
            this.name = name;
            this.kind = kind;
        }
    }

    public final String relPath;
    public final String lang;
    public int loc;
    /** declared package/namespace, empty when the language or file has none. */
    public String container = "";
    public final Set<String> imports = new LinkedHashSet<>();
    /**
     * Types defined out of line in this file but declared somewhere else, e.g. the
     * {@code Foo} in {@code void Foo::bar()}. A file that has these but declares nothing
     * of its own is an implementation file: its content belongs to those types, not to a
     * node of its own.
     */
    public final Set<String> outOfLineOwners = new LinkedHashSet<>();
    /** variable or field name -> the type written for it, for resolving call receivers. */
    public final Map<String, String> variableTypes = new HashMap<>();
    /**
     * {@code owner<US>name -> written type}, for typed names that belong to a declaration
     * rather than to a function body. A C++ field is declared in the header while the call
     * on it sits in the .cpp, so the owning type is the only way to connect the two.
     */
    public final Map<String, String> memberFieldTypes = new HashMap<>();
    public final List<Decl> decls = new ArrayList<>();
    private final Map<String, Ref> refs = new HashMap<>();
    private final Map<String, Call> calls = new HashMap<>();
    /** true when the grammar reported a syntax error; the facts are still usable. */
    public boolean hadParseError;

    public FileFacts(String relPath, String lang) {
        this.relPath = relPath;
        this.lang = lang;
    }

    public void addRef(String ownerName, String name, EdgeKind kind) {
        if (name == null || name.isEmpty()) return;
        String key = (ownerName == null ? "" : ownerName) + '\u001f' + name + '\u001f' + kind.ordinal();
        Ref existing = refs.get(key);
        if (existing == null) refs.put(key, new Ref(ownerName, name, kind));
        else existing.count++;
    }

    public void addCall(String ownerName, String receiver, String callee) {
        if (callee == null || callee.isEmpty()) return;
        String key = (ownerName == null ? "" : ownerName) + '\u001f'
                + (receiver == null ? "" : receiver) + '\u001f' + callee;
        Call existing = calls.get(key);
        if (existing == null) calls.put(key, new Call(ownerName, receiver, callee));
        else existing.count++;
    }

    public Iterable<Call> calls() {
        return calls.values();
    }

    public Iterable<Ref> refs() {
        return refs.values();
    }

    public int refCount() {
        return refs.size();
    }
}
