package io.github.tobiaskp.codemap.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The whole map, held in memory between building and persisting it. */
public final class CodeGraph {
    /** layer -> qname -> node */
    private final Map<Layer, Map<String, GNode>> nodes = new HashMap<>();
    /** layer -> "src\0dst" -> edge */
    private final Map<Layer, Map<String, GEdge>> edges = new HashMap<>();

    public String projectName = "";
    public String projectRoot = "";
    /** language -> file count, for the summary line and the UI legend. */
    public final Map<String, Integer> languageHistogram = new HashMap<>();
    public int filesScanned, filesFailed;

    public CodeGraph() {
        for (Layer l : Layer.values()) {
            nodes.put(l, new HashMap<>());
            edges.put(l, new HashMap<>());
        }
    }

    /** adds the node unless one with the same qname is already there; returns the live node. */
    public GNode addNode(GNode n) {
        return nodes.get(n.layer).computeIfAbsent(n.qname, k -> n);
    }

    public GNode node(Layer layer, String qname) {
        return nodes.get(layer).get(qname);
    }

    public boolean hasNode(Layer layer, String qname) {
        return nodes.get(layer).containsKey(qname);
    }

    public Map<String, GNode> nodes(Layer layer) {
        return nodes.get(layer);
    }

    public List<GNode> allNodes() {
        List<GNode> out = new ArrayList<>();
        for (Layer l : Layer.values()) out.addAll(nodes.get(l).values());
        return out;
    }

    /** records one fact; repeated facts for the same pair accumulate into one edge. */
    public void addEdge(Layer layer, String src, String dst, EdgeKind kind, int weight) {
        addEdge(layer, src, dst, kind, weight, null);
    }

    public void addEdge(Layer layer, String src, String dst, EdgeKind kind, int weight,
                        String parentQname) {
        if (src.equals(dst)) return;
        Map<String, GEdge> m = edges.get(layer);
        GEdge existing = m.get(GEdge.key(src, dst));
        if (existing == null) {
            GEdge edge = new GEdge(layer, src, dst, kind, weight);
            if (parentQname != null) edge.parentQname = parentQname;
            m.put(GEdge.key(src, dst), edge);
        } else {
            existing.merge(kind, weight);
            if (parentQname != null) existing.parentQname = parentQname;
        }
    }

    public Map<String, GEdge> edges(Layer layer) {
        return edges.get(layer);
    }

    public List<GEdge> allEdges() {
        List<GEdge> out = new ArrayList<>();
        for (Layer l : Layer.values()) out.addAll(edges.get(l).values());
        return out;
    }

    /** drops edges whose endpoints are not both present; then recomputes degrees. */
    public void finish() {
        for (Layer l : Layer.values()) {
            Map<String, GNode> ns = nodes.get(l);
            edges.get(l).values().removeIf(e -> !ns.containsKey(e.srcQname) || !ns.containsKey(e.dstQname));
            for (GEdge e : edges.get(l).values()) {
                ns.get(e.srcQname).outDeg++;
                ns.get(e.dstQname).inDeg++;
            }
        }
        // children counts follow parentQname wherever it points: layer 2 is a tree, so a
        // package's parent may be its module or another layer-2 node
        for (Layer l : new Layer[]{Layer.PACKAGE, Layer.TYPE, Layer.MEMBER}) {
            for (GNode n : nodes.get(l).values()) {
                GNode p = parentOf(n);
                if (p != null) p.children++;
            }
        }
    }

    /** The node {@code n} hangs off, looked up across the layers it could belong to. */
    public GNode parentOf(GNode n) {
        return switch (n.layer) {
            case MODULE -> nodes.get(Layer.MODULE).get(n.parentQname);
            case PACKAGE -> {
                GNode inModule = nodes.get(Layer.MODULE).get(n.parentQname);
                yield inModule != null ? inModule : nodes.get(Layer.PACKAGE).get(n.parentQname);
            }
            case TYPE -> nodes.get(Layer.PACKAGE).get(n.parentQname);
            case MEMBER -> nodes.get(Layer.TYPE).get(n.parentQname);
        };
    }
}
