package io.github.tobiaskp.codemap.proposal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A change an LLM is proposing, expressed against the map instead of in prose.
 *
 * <p>The point of this class is what it does <em>not</em> do: nothing here is ever written
 * to the graph database. A proposal lives in the memory of the running map server, is
 * layered over the graph as colour, and disappears when it is cleared or the server stops.
 * That is deliberate - the graph stays the record of what the code <em>is</em>, and a
 * proposal is only a way of saying what someone wants it to become. "Add a service here,
 * call it from there, delete this class" is a shape on the map, and a shape can be looked
 * at from the top view and drilled into; a paragraph saying "service X, line Y" cannot.
 *
 * <p>References are either an existing node or a node the proposal wants to create. The
 * second case is why {@link #proposeAdd} hands back a ref: an LLM cannot name something
 * that does not exist yet, so the server mints the name and later calls use it.
 *
 * <p>Every method is synchronized and every mutation bumps {@link #revision()}: the HTTP
 * threads that accept tool calls and the viewer polling for changes are different threads,
 * and the revision is what lets the viewer ask "anything new?" for almost nothing.
 */
public final class Proposal {

    public enum Op {
        ADD, MODIFY, DELETE, MOVE, CONNECT, ANNOTATE, HIGHLIGHT;

        public static Op of(String name) {
            for (Op op : values()) {
                if (op.name().equalsIgnoreCase(name)) return op;
            }
            throw new IllegalArgumentException("unknown operation: " + name);
        }
    }

    /**
     * How a node is drawn once a proposal is active. Kept separate from {@link Op} because
     * several operations look the same on the map: a move is a change, and an annotation is
     * a pointer rather than a change at all.
     */
    public enum Status {
        /** annotated or highlighted: worth looking at, but nothing is changing. */
        MARK(1),
        MODIFY(2),
        ADD(3),
        DELETE(4);

        public final int rank;

        Status(int rank) {
            this.rank = rank;
        }

        public String json() {
            return name().toLowerCase();
        }

        public static Status of(Op op) {
            return switch (op) {
                case ADD -> ADD;
                case DELETE -> DELETE;
                case MODIFY, MOVE, CONNECT -> MODIFY;
                case ANNOTATE, HIGHLIGHT -> MARK;
            };
        }
    }

    /** Either a node that exists in the graph, or one this proposal wants to create. */
    public record Ref(long id, String ref) {

        public static final Ref NONE = new Ref(0, "");

        public static Ref of(long id) {
            return new Ref(id, "");
        }

        public static Ref of(String ref) {
            return new Ref(0, ref == null ? "" : ref);
        }

        public boolean exists() {
            return id > 0;
        }

        public boolean isNew() {
            return id <= 0 && !ref.isEmpty();
        }

        public boolean present() {
            return exists() || isNew();
        }

        /** A key both kinds of reference can share, so they can go in one map. */
        public String key() {
            return exists() ? Long.toString(id) : ref;
        }
    }

    /** One proposed operation, as it will be reported back and drawn. */
    public static final class Change {
        public final String id;
        public final Op op;
        public Ref target = Ref.NONE;
        /** where an addition goes, or where a move takes it. */
        public Ref parent = Ref.NONE;
        public Ref from = Ref.NONE;
        public Ref to = Ref.NONE;
        /** for an addition: CLASS, METHOD, PACKAGE ... whatever the caller said. */
        public String kind = "";
        public String name = "";
        /** for a connection: CALL, FIELD, EXTENDS ... */
        public String edgeKind = "";
        public String note = "";

        Change(String id, Op op) {
            this.id = id;
            this.op = op;
        }

        public Status status() {
            return Status.of(op);
        }

        /** The node this change is about, for the ones that are about a single node. */
        public Ref subject() {
            return target.present() ? target : from;
        }
    }

    private final List<Change> changes = new ArrayList<>();
    private final Map<String, Change> additionsByRef = new LinkedHashMap<>();
    private String title = "";
    private long revision;
    private int nextChangeId = 1;
    private int nextNodeRef = 1;

    // ------------------------------------------------------------------ reading

    public synchronized long revision() {
        return revision;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized boolean isEmpty() {
        return changes.isEmpty();
    }

    /** A snapshot, so callers can iterate without holding the lock. */
    public synchronized List<Change> changes() {
        return new ArrayList<>(changes);
    }

    /** The addition a {@code new} ref names, or null if no such addition exists. */
    public synchronized Change addition(String ref) {
        return additionsByRef.get(ref);
    }

    // ----------------------------------------------------------------- writing

    /** Drops everything and names the new proposal. Also how a proposal is cleared. */
    public synchronized void start(String title) {
        changes.clear();
        additionsByRef.clear();
        this.title = title == null ? "" : title.strip();
        nextChangeId = 1;
        nextNodeRef = 1;
        revision++;
    }

    /**
     * Records a proposed new node and returns the ref later calls use to talk about it.
     * A proposal can therefore build structure: a package, then a class inside it, then a
     * method inside that, none of which exist yet.
     */
    public synchronized String proposeAdd(Ref parent, String kind, String name, String note) {
        Change c = new Change("c" + nextChangeId++, Op.ADD);
        c.target = Ref.of("n" + nextNodeRef++);
        c.parent = parent;
        c.kind = clean(kind);
        c.name = clean(name);
        c.note = clean(note);
        changes.add(c);
        additionsByRef.put(c.target.ref(), c);
        revision++;
        return c.target.ref();
    }

    public synchronized void proposeModify(Ref target, String note) {
        Change c = single(Op.MODIFY, target);
        c.note = clean(note);
        revision++;
    }

    public synchronized void proposeDelete(Ref target, String note) {
        Change c = single(Op.DELETE, target);
        c.note = clean(note);
        revision++;
    }

    public synchronized void proposeMove(Ref target, Ref parent, String note) {
        Change c = single(Op.MOVE, target);
        c.parent = parent;
        c.note = clean(note);
        revision++;
    }

    public synchronized void proposeConnection(Ref from, Ref to, String kind, String note) {
        for (Change existing : changes) {
            if (existing.op == Op.CONNECT
                    && existing.from.key().equals(from.key())
                    && existing.to.key().equals(to.key())) {
                existing.edgeKind = clean(kind);
                existing.note = clean(note);
                revision++;
                return;
            }
        }
        Change c = new Change("c" + nextChangeId++, Op.CONNECT);
        c.from = from;
        c.to = to;
        c.edgeKind = clean(kind);
        c.note = clean(note);
        changes.add(c);
        revision++;
    }

    public synchronized void annotate(Ref target, String note) {
        Change c = single(Op.ANNOTATE, target);
        c.note = clean(note);
        revision++;
    }

    public synchronized void highlight(List<Ref> targets, String note) {
        for (Ref target : targets) {
            Change c = single(Op.HIGHLIGHT, target);
            c.note = clean(note);
        }
        revision++;
    }

    /**
     * The change for one (operation, node) pair, reusing an existing one if there is one.
     * An agent that revises its plan will call {@code propose_modify} on the same class
     * twice, and two identical entries in the list is noise rather than information.
     */
    private Change single(Op op, Ref target) {
        for (Change existing : changes) {
            if (existing.op == op && existing.target.key().equals(target.key())) return existing;
        }
        Change c = new Change("c" + nextChangeId++, op);
        c.target = target;
        changes.add(c);
        return c;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
