package io.github.tobiaskp.codemap.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * A directed edge between two nodes of the same layer. Layer 2 and 3 edges are
 * rollups of layer 3 facts, so every edge keeps the breakdown that produced it.
 */
public final class GEdge {
    public long id;
    public final Layer layer;
    public final String srcQname;
    public final String dstQname;
    public long srcId, dstId;
    /**
     * The node whose view this edge is drawn in: the nearest common ancestor of its two
     * endpoints. Both endpoints are children of it, so a view is exactly "the edges whose
     * parent is me" - which works at any depth and even when the two children sit on
     * different layers, as a package and a class do inside the same package.
     */
    public String parentQname = "";
    public long parentId;
    /** label of the edge: the strongest kind that contributed to it. */
    public EdgeKind kind;
    /** how many distinct facts produced this edge. */
    public int weight;
    /** kind -> count, so the UI can say "3 fields, 11 calls". */
    public final Map<EdgeKind, Integer> breakdown = new EnumMap<>(EdgeKind.class);

    public GEdge(Layer layer, String srcQname, String dstQname, EdgeKind kind, int weight) {
        this.layer = layer;
        this.srcQname = srcQname;
        this.dstQname = dstQname;
        this.kind = kind;
        this.weight = weight;
        this.breakdown.put(kind, weight);
    }

    public void merge(EdgeKind other, int otherWeight) {
        kind = EdgeKind.strongest(kind, other);
        weight += otherWeight;
        breakdown.merge(other, otherWeight, Integer::sum);
    }

    public void mergeAll(GEdge other) {
        kind = EdgeKind.strongest(kind, other.kind);
        weight += other.weight;
        other.breakdown.forEach((k, v) -> breakdown.merge(k, v, Integer::sum));
    }

    /** compact "CALL:11,FIELD:3" form stored in the DB and handed to the UI. */
    public String breakdownString() {
        StringBuilder sb = new StringBuilder();
        breakdown.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(',');
            sb.append(k).append(':').append(v);
        });
        return sb.toString();
    }

    /** Unit separator, not a space: module names can contain spaces. */
    private static final String KEY_SEP = "\u001f";

    public static String key(String src, String dst) {
        return src + KEY_SEP + dst;
    }

    public String key() {
        return key(srcQname, dstQname);
    }
}
