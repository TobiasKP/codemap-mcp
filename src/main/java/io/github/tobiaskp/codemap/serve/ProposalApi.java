package io.github.tobiaskp.codemap.serve;

import io.github.tobiaskp.codemap.proposal.Proposal;
import io.github.tobiaskp.codemap.proposal.Proposal.Change;
import io.github.tobiaskp.codemap.proposal.Proposal.Op;
import io.github.tobiaskp.codemap.proposal.Proposal.Ref;
import io.github.tobiaskp.codemap.proposal.Proposal.Status;
import io.github.tobiaskp.codemap.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The proposal half of the HTTP API: turns tool calls into overlay state, and overlay
 * state into something the viewer can paint without knowing anything about the tree.
 *
 * <p>Two jobs are worth naming. The first is <b>reference resolution</b>: an agent should
 * be able to say {@code "com.example.billing.Invoice"}, or {@code "Invoice"}, or {@code 4711},
 * or {@code "n2"} for something it invented three calls ago, and have all four mean the
 * node it meant. The second is <b>rollup</b>: a change to one method has to be visible from
 * the top view, so every touched node's ancestors are marked too, with a colour that says
 * whether what is inside is an addition, a deletion, or a mix. The viewer only ever draws
 * one level, so without that rollup a proposal deep in the tree would be invisible until
 * you happened to open the right package.
 */
final class ProposalApi {

    private final Connection conn;
    private final Proposal proposal = new Proposal();

    ProposalApi(Connection conn) {
        this.conn = conn;
    }

    Proposal proposal() {
        return proposal;
    }

    // ------------------------------------------------------------------ writing

    /** {@code POST /api/proposal/start} - names a proposal and drops the previous one. */
    String start(Map<String, Object> body) {
        proposal.start(str(body, "title"));
        return ok(null);
    }

    /** {@code DELETE /api/proposal} - back to showing the code as it is. */
    String clear() {
        proposal.start("");
        return ok(null);
    }

    /**
     * {@code POST /api/proposal/change} - one operation. Everything the MCP server exposes
     * funnels through here, so there is exactly one place where an operation is validated.
     */
    String change(Map<String, Object> body) throws SQLException {
        Op op;
        try {
            op = Op.of(str(body, "op"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        String note = str(body, "note");
        try {
            switch (op) {
                case ADD -> {
                    Ref parent = require(body, "parent");
                    String name = str(body, "name");
                    if (name.isEmpty()) return error("add needs a name");
                    String ref = proposal.proposeAdd(parent, str(body, "kind"), name, note);
                    return ok(ref);
                }
                case MODIFY -> proposal.proposeModify(require(body, "target"), note);
                case DELETE -> proposal.proposeDelete(require(body, "target"), note);
                case MOVE -> proposal.proposeMove(require(body, "target"),
                        require(body, "parent"), note);
                case CONNECT -> proposal.proposeConnection(require(body, "from"),
                        require(body, "to"), str(body, "edge_kind"), note);
                case ANNOTATE -> {
                    if (note.isEmpty()) return error("annotate needs a note");
                    proposal.annotate(require(body, "target"), note);
                }
                case HIGHLIGHT -> {
                    List<Ref> refs = new ArrayList<>();
                    Object targets = body.get("targets");
                    if (targets instanceof List<?> list) {
                        for (Object item : list) refs.add(resolve(String.valueOf(item)));
                    }
                    if (refs.isEmpty()) refs.add(require(body, "target"));
                    proposal.highlight(refs, note);
                }
            }
        } catch (Unresolved e) {
            return error(e.getMessage());
        }
        return ok(null);
    }

    private Ref require(Map<String, Object> body, String key) throws SQLException, Unresolved {
        String raw = str(body, key);
        if (raw.isEmpty()) throw new Unresolved("this operation needs a '" + key + "'");
        return resolve(raw);
    }

    /** Thrown when a reference names nothing; carries the message the agent will read. */
    static final class Unresolved extends Exception {
        Unresolved(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------- reference resolving

    /**
     * A node id, a ref this proposal minted, a fully qualified name, or a plain name that
     * happens to be unique. Anything else is an error naming the near misses, because an
     * agent that gets "no such node: Invoce" back can fix it, and one that gets a silent
     * no-op cannot.
     */
    Ref resolve(String raw) throws SQLException, Unresolved {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) throw new Unresolved("empty reference");

        if (value.chars().allMatch(Character::isDigit)) {
            long id = Long.parseLong(value);
            if (exists(id)) return Ref.of(id);
            throw new Unresolved("no node with id " + id);
        }
        if (proposal.addition(value) != null) return Ref.of(value);

        // qname is unique per layer, so the same string can be a package and a type; an
        // agent naming a dotted path almost always means the most specific thing there
        List<long[]> byQname = lookup("qname = ?", value);
        if (!byQname.isEmpty()) return Ref.of(deepest(byQname));

        /*
         * A plain name resolves to the outermost thing that carries it. This matters far
         * more than it looks: in most languages a class shares its name with its own
         * constructor, so a strict "more than one match is ambiguous" rule would reject
         * `Invoice` in almost every codebase and make plain names useless. Preferring the
         * shallower layer means `Invoice` is the class and `Invoice::Invoice` is reachable
         * by its qualified name.
         *
         * Two classes called Invoice in different modules is the genuinely ambiguous case,
         * and that is still refused - guessing would put the proposal on the wrong node
         * with nothing to show that it had.
         */
        List<long[]> byName = lookup("name = ?", value);
        if (!byName.isEmpty()) {
            long shallowest = byName.stream().mapToLong(m -> m[1]).min().orElse(0);
            List<long[]> tied = byName.stream().filter(m -> m[1] == shallowest).toList();
            if (tied.size() == 1) return Ref.of(tied.get(0)[0]);
            throw new Unresolved("'" + value + "' matches " + tied.size()
                    + " nodes; use the id or the qualified name (" + describe(tied) + ")");
        }
        throw new Unresolved("nothing called '" + value + "'" + nearMisses(value));
    }

    /** ids and layers of every node matching a predicate on one bound string. */
    private List<long[]> lookup(String where, String value) throws SQLException {
        List<long[]> out = new ArrayList<>();
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    // shallowest first, so the row limit can never hide the outer match
                    "SELECT id, layer FROM nodes WHERE " + where + " ORDER BY layer LIMIT 24")) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
                }
            }
        }
        return out;
    }

    private static long deepest(List<long[]> matches) {
        long bestId = matches.get(0)[0];
        long bestLayer = matches.get(0)[1];
        for (long[] m : matches) {
            if (m[1] > bestLayer) {
                bestLayer = m[1];
                bestId = m[0];
            }
        }
        return bestId;
    }

    private String describe(List<long[]> matches) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (long[] m : matches) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(m[0]).append('=').append(qnameOf(m[0]));
        }
        return sb.toString();
    }

    private String nearMisses(String value) throws SQLException {
        List<String> hits = new ArrayList<>();
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT qname FROM nodes WHERE name LIKE ? ORDER BY (in_deg+out_deg) DESC"
                            + " LIMIT 5")) {
                ps.setString(1, "%" + value + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) hits.add(rs.getString(1));
                }
            }
        }
        return hits.isEmpty() ? "" : ". Did you mean: " + String.join(", ", hits) + "?";
    }

    private boolean exists(long id) throws SQLException {
        return qnameOf(id) != null;
    }

    // ------------------------------------------------------------------ reading

    /**
     * {@code GET /api/proposal} - the whole overlay. The viewer polls this; when its
     * revision matches, the answer is four fields instead of the full payload.
     */
    String read(long since) throws SQLException {
        long revision = proposal.revision();
        if (since >= 0 && since == revision) {
            return "{\"revision\":" + revision + ",\"unchanged\":true}";
        }
        List<Change> changes = proposal.changes();
        Overlay overlay = rollUp(changes);

        StringBuilder sb = new StringBuilder("{");
        Json.field(sb, "revision", revision);
        sb.append(',');
        Json.field(sb, "title", proposal.title());
        sb.append(',');
        Json.field(sb, "active", changes.isEmpty() ? 0 : 1);
        sb.append(",\"changes\":[");
        boolean first = true;
        for (Change c : changes) {
            if (!first) sb.append(',');
            first = false;
            writeChange(sb, c);
        }
        sb.append("],\"nodes\":{");
        first = true;
        for (Map.Entry<Long, Mark> e : overlay.marks.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Json.str(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeMark(sb, e.getValue());
        }
        sb.append("},\"additions\":[");
        first = true;
        for (Change c : changes) {
            if (c.op != Op.ADD) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            Json.field(sb, "ref", c.target.ref());
            sb.append(',');
            Json.field(sb, "name", c.name);
            sb.append(',');
            Json.field(sb, "kind", c.kind.isEmpty() ? "CLASS" : c.kind.toUpperCase());
            sb.append(',');
            Json.field(sb, "parentId", c.parent.id());
            sb.append(',');
            Json.field(sb, "parentRef", c.parent.ref());
            sb.append(',');
            Json.field(sb, "note", c.note);
            sb.append('}');
        }
        sb.append("],\"connections\":[");
        first = true;
        for (Change c : changes) {
            if (c.op != Op.CONNECT) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            Json.field(sb, "fromId", c.from.id());
            sb.append(',');
            Json.field(sb, "fromRef", c.from.ref());
            sb.append(',');
            Json.field(sb, "toId", c.to.id());
            sb.append(',');
            Json.field(sb, "toRef", c.to.ref());
            sb.append(',');
            Json.field(sb, "kind", c.edgeKind.isEmpty() ? "CALL" : c.edgeKind.toUpperCase());
            sb.append(',');
            Json.field(sb, "note", c.note);
            sb.append('}');
        }
        // the ancestors of everything mentioned, so the viewer can roll an endpoint up
        // into whatever level happens to be on screen without fetching the tree
        sb.append("],\"chains\":{");
        first = true;
        for (Map.Entry<Long, List<Long>> e : overlay.chains.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Json.str(sb, String.valueOf(e.getKey()));
            sb.append(":[");
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(e.getValue().get(i));
            }
            sb.append(']');
        }
        sb.append("}}");
        return sb.toString();
    }

    private void writeChange(StringBuilder sb, Change c) throws SQLException {
        sb.append('{');
        Json.field(sb, "id", c.id);
        sb.append(',');
        Json.field(sb, "op", c.op.name().toLowerCase());
        sb.append(',');
        Json.field(sb, "status", c.status().json());
        sb.append(',');
        Json.field(sb, "name", c.name);
        sb.append(',');
        Json.field(sb, "kind", c.kind);
        sb.append(',');
        Json.field(sb, "edgeKind", c.edgeKind);
        sb.append(',');
        Json.field(sb, "note", c.note);
        sb.append(",\"target\":");
        writeRef(sb, c.target);
        sb.append(",\"parent\":");
        writeRef(sb, c.parent);
        sb.append(",\"from\":");
        writeRef(sb, c.from);
        sb.append(",\"to\":");
        writeRef(sb, c.to);
        sb.append('}');
    }

    /** A reference with enough naming to render a panel row without another request. */
    private void writeRef(StringBuilder sb, Ref ref) throws SQLException {
        sb.append('{');
        Json.field(sb, "id", ref.id());
        sb.append(',');
        Json.field(sb, "ref", ref.ref());
        sb.append(',');
        if (ref.exists()) {
            Json.field(sb, "name", nameOf(ref.id()));
            sb.append(',');
            Json.field(sb, "qname", qnameOf(ref.id()));
            sb.append(',');
            Json.field(sb, "layer", layerOf(ref.id()));
        } else {
            Change addition = ref.isNew() ? proposal.addition(ref.ref()) : null;
            Json.field(sb, "name", addition == null ? "" : addition.name);
            sb.append(',');
            Json.field(sb, "qname", "");
            sb.append(',');
            Json.field(sb, "layer", 0);
        }
        sb.append('}');
    }

    private static void writeMark(StringBuilder sb, Mark mark) {
        sb.append('{');
        Json.field(sb, "s", mark.status().json());
        sb.append(',');
        Json.field(sb, "own", mark.own ? 1 : 0);
        sb.append(',');
        Json.field(sb, "add", mark.counts[Status.ADD.ordinal()]);
        sb.append(',');
        Json.field(sb, "modify", mark.counts[Status.MODIFY.ordinal()]);
        sb.append(',');
        Json.field(sb, "delete", mark.counts[Status.DELETE.ordinal()]);
        sb.append(',');
        Json.field(sb, "mark", mark.counts[Status.MARK.ordinal()]);
        sb.append(',');
        Json.field(sb, "note", mark.note);
        sb.append('}');
    }

    // ------------------------------------------------------------------- rollup

    /** What the viewer needs per node, once the tree has been walked. */
    private static final class Mark {
        /** true when this node is itself the subject of a change, not just an ancestor. */
        boolean own;
        /** the strongest status this node was named with directly. */
        Status ownStatus;
        /** every status seen at or below this node. */
        final EnumSet<Status> seen = EnumSet.noneOf(Status.class);
        final int[] counts = new int[Status.values().length];
        String note = "";

        /**
         * What colour to paint. A node that is itself being changed shows its own status.
         * A container shows the one thing happening inside it, or yellow when several
         * different things are: "something in here changes" is the useful signal at the top
         * of a tree, and it is honest about being a summary.
         */
        Status status() {
            if (own && ownStatus != null) return ownStatus;
            if (seen.isEmpty()) return Status.MARK;
            if (seen.size() == 1) return seen.iterator().next();
            EnumSet<Status> real = EnumSet.copyOf(seen);
            real.remove(Status.MARK);
            if (real.size() == 1) return real.iterator().next();
            return Status.MODIFY;
        }
    }

    private static final class Overlay {
        final Map<Long, Mark> marks = new LinkedHashMap<>();
        final Map<Long, List<Long>> chains = new LinkedHashMap<>();
    }

    private Overlay rollUp(List<Change> changes) throws SQLException {
        Overlay overlay = new Overlay();
        Map<Long, Long> parentCache = new HashMap<>();

        for (Change c : changes) {
            switch (c.op) {
                case ADD -> {
                    // the addition itself has no id; what is visible is the parent gaining
                    // something, all the way up to the module
                    contribute(overlay, parentCache, c.parent, Status.ADD, false, c.note);
                }
                case DELETE -> contribute(overlay, parentCache, c.target, Status.DELETE, true, c.note);
                case MODIFY -> contribute(overlay, parentCache, c.target, Status.MODIFY, true, c.note);
                case MOVE -> {
                    contribute(overlay, parentCache, c.target, Status.MODIFY, true, c.note);
                    contribute(overlay, parentCache, c.parent, Status.ADD, false, c.note);
                }
                case CONNECT -> {
                    // a new call changes the caller; the callee is worth seeing but is not
                    // itself being edited, so it is marked rather than claimed as changed
                    contribute(overlay, parentCache, c.from, Status.MODIFY, true, c.note);
                    contribute(overlay, parentCache, c.to, Status.MARK, true, "");
                }
                case ANNOTATE, HIGHLIGHT ->
                        contribute(overlay, parentCache, c.target, Status.MARK, true, c.note);
            }
        }
        for (Long id : new ArrayList<>(overlay.marks.keySet())) {
            overlay.chains.put(id, chainOf(id, parentCache));
        }
        return overlay;
    }

    /**
     * Applies a status to a node and to every ancestor above it. {@code direct} says
     * whether the node itself is the subject: a package that merely contains a proposed
     * class is not itself being changed, and colouring it as though it were would lose the
     * distinction between "look in here" and "edit this".
     */
    private void contribute(Overlay overlay, Map<Long, Long> parentCache, Ref ref,
                            Status status, boolean direct, String note) throws SQLException {
        if (!ref.exists()) return;
        long id = ref.id();
        Mark mark = overlay.marks.computeIfAbsent(id, k -> new Mark());
        mark.seen.add(status);
        if (direct) {
            mark.own = true;
            if (mark.ownStatus == null || status.rank > mark.ownStatus.rank) mark.ownStatus = status;
            if (mark.note.isEmpty() && note != null && !note.isEmpty()) mark.note = note;
        }
        mark.counts[status.ordinal()]++;

        long current = parentOf(id, parentCache);
        for (int depth = 0; depth < 32 && current > 0; depth++) {
            Mark up = overlay.marks.computeIfAbsent(current, k -> new Mark());
            up.seen.add(status);
            up.counts[status.ordinal()]++;
            current = parentOf(current, parentCache);
        }
    }

    private List<Long> chainOf(long id, Map<Long, Long> parentCache) throws SQLException {
        List<Long> chain = new ArrayList<>();
        long current = parentOf(id, parentCache);
        for (int depth = 0; depth < 32 && current > 0; depth++) {
            chain.add(current);
            current = parentOf(current, parentCache);
        }
        return chain;
    }

    private long parentOf(long id, Map<Long, Long> cache) throws SQLException {
        Long cached = cache.get(id);
        if (cached != null) return cached;
        long parent = 0;
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT parent_id FROM nodes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) parent = rs.getLong(1);
                }
            }
        }
        cache.put(id, parent);
        return parent;
    }

    // ------------------------------------------------------------------ helpers

    private String nameOf(long id) throws SQLException {
        return column(id, "name");
    }

    private String qnameOf(long id) throws SQLException {
        return column(id, "qname");
    }

    private long layerOf(long id) throws SQLException {
        String value = column(id, "layer");
        return value == null ? 0 : Long.parseLong(value);
    }

    private String column(long id, String name) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + name + " FROM nodes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    private String ok(String ref) {
        StringBuilder sb = new StringBuilder("{");
        Json.field(sb, "ok", 1);
        sb.append(',');
        Json.field(sb, "revision", proposal.revision());
        sb.append(',');
        Json.field(sb, "changes", proposal.changes().size());
        if (ref != null) {
            sb.append(',');
            Json.field(sb, "ref", ref);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String error(String message) {
        StringBuilder sb = new StringBuilder("{");
        Json.field(sb, "ok", 0);
        sb.append(',');
        Json.field(sb, "error", message);
        sb.append('}');
        return sb.toString();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return "";
        if (value instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(value).strip();
    }

}
