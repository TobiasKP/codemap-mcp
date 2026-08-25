package io.github.tobiaskp.codemap.serve;

import io.github.tobiaskp.codemap.proposal.Proposal.Change;
import io.github.tobiaskp.codemap.proposal.Proposal.Op;
import io.github.tobiaskp.codemap.util.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the graph has to say about a proposal, as opposed to what the proposal says about
 * itself.
 *
 * <p>The overlay in {@link ProposalApi} paints the plan: here is the new class, here is the
 * method that changes, here is the call it wants to add. That answers <i>where</i> and
 * <i>what</i>, and it is all an agent's own assertion - drawn faithfully whether the idea is
 * good or terrible. The two questions a human reviewer actually has are different, and the
 * scan already contains the answers to both:
 *
 * <ul>
 *   <li><b>Precedent.</b> Does this proposed dependency have any? A call that joins 529
 *       existing references between the same two packages is a mechanical consequence. The
 *       first ever edge from A to B is an architectural decision that somebody made in
 *       passing, and it should not look identical to the first kind.</li>
 *   <li><b>Exposure.</b> What does the plan touch that has users it never mentions? A
 *       modified enum with thirty-four references and no word about any of them is the
 *       single most common shape of an incomplete plan, and silence is invisible on a map
 *       that only draws what it was told.</li>
 * </ul>
 *
 * <p>Neither needs a model, another scan, or a configuration file: precedent is a rolled-up
 * edge that is already in the table, and exposure is the reverse of the edges used to draw
 * the view. This class asks the questions; nothing here decides what to do about the answers.
 */
final class Review {

    /**
     * How many neighbours to carry per exposed entity. Generous, because these are what the
     * map paints and a rollup built from eight of thirty-four would under-report the shadow
     * of the change at every level above them. The panel shows far fewer; the viewer slices.
     */
    private static final int SAMPLES = 40;
    /** Entities to report at all, worst first: a plan touching a hub has a long tail. */
    private static final int MAX_EXPOSED = 24;

    private final Connection conn;
    private final Map<Long, Long> parents = new HashMap<>();

    Review(Connection conn) {
        this.conn = conn;
    }

    // --------------------------------------------------------------- precedent

    /**
     * How usual a proposed edge would be, measured between the two containers it actually
     * crosses.
     *
     * @param local     true when both ends live in the same container, so no boundary is
     *                  crossed and there is no ratio worth reporting
     * @param forward   existing weight already going the way the proposal wants to go
     * @param backward  existing weight going the other way, which is what tells you whether
     *                  the proposal is swimming against the grain
     */
    record Precedent(boolean local, long fromId, String from, long toId, String to,
                     long forward, long backward, String kinds) {

        /** The verdict in words, because a bare pair of numbers is not a judgement. */
        String verdict() {
            if (local) return "inside " + from;
            if (forward == 0 && backward == 0) return "first dependency between these two";
            if (forward == 0) return "first in this direction (" + backward + " the other way)";
            if (backward > forward * 4) return "against the grain ("
                    + ratio(backward, forward) + ":1 the other way)";
            return "follows " + forward + " existing";
        }

        private static String ratio(long big, long small) {
            return String.valueOf(Math.round((double) big / Math.max(1, small)));
        }
    }

    /**
     * The precedent for one proposed connection.
     *
     * <p>An endpoint that does not exist yet stands in for its parent: a new class in
     * {@code verwaltung} calling into {@code dokumentation} is, as far as precedent goes, one
     * more {@code verwaltung -> dokumentation} reference, and that is exactly the number
     * worth knowing before agreeing to it.
     */
    Precedent precedentFor(Change c, List<Change> changes) throws SQLException {
        long fromBox = container(c, true, changes);
        long toBox = container(c, false, changes);
        if (fromBox <= 0 || toBox <= 0) return null;

        /*
         * Measured between the two endpoints' *containers*, and the divergence found from
         * there. Working from the endpoints themselves would be wrong twice over: a node
         * that does not exist yet has no place in the tree to walk up from, and no two
         * methods reference each other often enough for a count to mean anything. The
         * question "how usual is this" only has an answer at the level where the two sides
         * are siblings - which is also the level the map draws the arrow at.
         */
        List<Long> a = selfAndAncestors(fromBox);
        List<Long> b = selfAndAncestors(toBox);
        for (int i = 0; i < a.size(); i++) {
            int j = b.indexOf(a.get(i));
            if (j < 0) continue;
            if (i == 0 || j == 0) {
                // one container sits inside the other, so the edge crosses no boundary at
                // all - it is local to whichever of them is the outer one
                long box = a.get(i);
                return new Precedent(true, box, nameOf(box), box, nameOf(box), 0, 0, "");
            }
            long left = a.get(i - 1);
            long right = b.get(j - 1);
            long[] fwd = rolledUp(left, right);
            long[] back = rolledUp(right, left);
            return new Precedent(false, left, nameOf(left), right, nameOf(right),
                    fwd[0], back[0], breakdownOf(left, right));
        }
        return null;
    }

    /**
     * The container an endpoint lives in - for something that does not exist yet, the one it
     * is being added to. A new class in {@code verwaltung} counts as {@code verwaltung} for
     * this purpose, which is the whole point: the precedent for its first call is the
     * precedent its package already has.
     */
    private long container(Change c, boolean fromEnd, List<Change> changes)
            throws SQLException {
        var ref = fromEnd ? c.from : c.to;
        if (ref.exists()) return parentOf(ref.id());
        return additionParent(ref.ref(), changes, 0);
    }

    /** Where a minted ref is going to live, following a chain of additions if need be. */
    private long additionParent(String ref, List<Change> changes, int depth)
            throws SQLException {
        if (ref == null || ref.isEmpty() || depth > 8) return 0;
        for (Change add : changes) {
            if (add.op != Op.ADD || !ref.equals(add.target.ref())) continue;
            if (add.parent.exists()) return add.parent.id();
            return additionParent(add.parent.ref(), changes, depth + 1);
        }
        return 0;
    }

    private long[] rolledUp(long src, long dst) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sum(weight), count(*) FROM edges WHERE src_id = ? AND dst_id = ?")) {
                ps.setLong(1, src);
                ps.setLong(2, dst);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new long[] { rs.getLong(1), rs.getLong(2) };
                }
            }
        }
        return new long[] { 0, 0 };
    }

    private String breakdownOf(long src, long dst) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT breakdown FROM edges WHERE src_id = ? AND dst_id = ?"
                            + " ORDER BY weight DESC LIMIT 1")) {
                ps.setLong(1, src);
                ps.setLong(2, dst);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String value = rs.getString(1);
                        return value == null ? "" : value;
                    }
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- exposure

    /** One entity the plan changes, and the users of it the plan says nothing about. */
    record Exposed(long id, String name, String qname, long layer, String reason,
                   long total, long addressed, List<Neighbour> samples) {
    }

    record Neighbour(long id, String name, String qname, String kind, long weight) {
    }

    /**
     * Everything the plan changes that has users it never mentions.
     *
     * <p>Only <b>direct</b> subjects count - the nodes an agent named, not the packages
     * above them that the rollup lit. And only references at the subject's own level, which
     * is where "who would have to be looked at" lives: a package that contains the modified
     * class is not a caller of it.
     *
     * <p>{@code touched} is every id the proposal mentions, so a caller the plan already
     * handles is not reported as an omission. That is the difference between this being a
     * useful warning and being noise on every second change.
     */
    List<Exposed> exposure(List<Change> changes, Set<Long> touched) throws SQLException {
        Map<Long, Exposed> out = new LinkedHashMap<>();
        for (Change c : changes) {
            // an addition has nothing to expose: nothing can reference what does not exist
            if (c.op != Op.MODIFY && c.op != Op.DELETE && c.op != Op.MOVE) continue;
            if (!c.target.exists()) continue;
            long id = c.target.id();
            if (out.containsKey(id)) continue;
            Exposed exposed = usersOf(id, touched);
            if (exposed != null) out.put(id, exposed);
        }
        List<Exposed> list = new ArrayList<>(out.values());
        list.sort((a, b) -> Long.compare(b.total - b.addressed, a.total - a.addressed));
        return list.size() > MAX_EXPOSED ? list.subList(0, MAX_EXPOSED) : list;
    }

    private Exposed usersOf(long id, Set<Long> touched) throws SQLException {
        long layer = layerOf(id);
        List<Neighbour> samples = new ArrayList<>();
        long total = 0;
        long addressed = 0;
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT e.src_id, n.name, n.qname, e.kind, e.weight"
                            + "   FROM edges e JOIN nodes n ON n.id = e.src_id"
                            + "  WHERE e.dst_id = ? AND e.layer = ? AND e.src_id <> ?"
                            + "  ORDER BY e.weight DESC")) {
                ps.setLong(1, id);
                ps.setLong(2, layer);
                ps.setLong(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long src = rs.getLong(1);
                        total++;
                        if (touched.contains(src)) {
                            addressed++;
                            continue;
                        }
                        if (samples.size() < SAMPLES) {
                            samples.add(new Neighbour(src, rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getLong(5)));
                        }
                    }
                }
            }
        }
        if (total == 0 || total == addressed) return null;
        return new Exposed(id, nameOf(id), qnameOf(id), layer, "references",
                total, addressed, samples);
    }

    /**
     * The exposed entities and everything above them, counted.
     *
     * <p>Rolled up for the same reason the proposal itself is: the viewer draws one level at
     * a time, so a caller three packages away is invisible until you happen to open the
     * right package - and "what else would have to be looked at" is a question you ask from
     * the top, not after nine clicks. A container carries the number of exposed entities
     * beneath it, which is what makes the shadow of a change legible at module level.
     */
    Map<Long, Integer> rollUpExposure(List<Exposed> exposure) throws SQLException {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Exposed e : exposure) {
            for (Neighbour n : e.samples()) {
                long current = n.id();
                for (int depth = 0; depth < 32 && current > 0; depth++) {
                    counts.merge(current, 1, Integer::sum);
                    current = parentOf(current);
                }
            }
        }
        return counts;
    }

    // -------------------------------------------------------------------- json

    static void writePrecedent(StringBuilder sb, Precedent p) {
        if (p == null) {
            sb.append("null");
            return;
        }
        sb.append('{');
        Json.field(sb, "local", p.local() ? 1 : 0);
        sb.append(',');
        Json.field(sb, "from", p.from());
        sb.append(',');
        Json.field(sb, "to", p.to());
        sb.append(',');
        Json.field(sb, "forward", p.forward());
        sb.append(',');
        Json.field(sb, "backward", p.backward());
        sb.append(',');
        Json.field(sb, "kinds", p.kinds());
        sb.append(',');
        Json.field(sb, "verdict", p.verdict());
        sb.append('}');
    }

    static void writeExposed(StringBuilder sb, Exposed e) {
        sb.append('{');
        Json.field(sb, "id", e.id());
        sb.append(',');
        Json.field(sb, "name", e.name());
        sb.append(',');
        Json.field(sb, "qname", e.qname());
        sb.append(',');
        Json.field(sb, "layer", e.layer());
        sb.append(',');
        Json.field(sb, "reason", e.reason());
        sb.append(',');
        Json.field(sb, "total", e.total());
        sb.append(',');
        Json.field(sb, "addressed", e.addressed());
        sb.append(",\"samples\":[");
        for (int i = 0; i < e.samples().size(); i++) {
            if (i > 0) sb.append(',');
            Neighbour n = e.samples().get(i);
            sb.append('{');
            Json.field(sb, "id", n.id());
            sb.append(',');
            Json.field(sb, "name", n.name());
            sb.append(',');
            Json.field(sb, "qname", n.qname());
            sb.append(',');
            Json.field(sb, "kind", n.kind());
            sb.append(',');
            Json.field(sb, "weight", n.weight());
            sb.append('}');
        }
        sb.append("]}");
    }

    // ----------------------------------------------------------------- helpers

    private List<Long> selfAndAncestors(long id) throws SQLException {
        List<Long> chain = new ArrayList<>();
        long current = id;
        for (int depth = 0; depth < 32 && current > 0; depth++) {
            chain.add(current);
            current = parentOf(current);
        }
        return chain;
    }

    private long parentOf(long id) throws SQLException {
        Long cached = parents.get(id);
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
        parents.put(id, parent);
        return parent;
    }

    private String nameOf(long id) throws SQLException {
        return text(id, "name");
    }

    private String qnameOf(long id) throws SQLException {
        return text(id, "qname");
    }

    private long layerOf(long id) throws SQLException {
        String value = text(id, "layer");
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private String text(long id, String column) throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + column + " FROM nodes WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String value = rs.getString(1);
                        return value == null ? "" : value;
                    }
                }
            }
        }
        return "";
    }
}
