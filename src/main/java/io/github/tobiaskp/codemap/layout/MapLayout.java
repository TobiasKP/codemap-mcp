package io.github.tobiaskp.codemap.layout;

import io.github.tobiaskp.codemap.model.CodeGraph;
import io.github.tobiaskp.codemap.model.GEdge;
import io.github.tobiaskp.codemap.model.GNode;
import io.github.tobiaskp.codemap.model.Layer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out one view per container: modules at the root, then whatever sits inside each
 * container, all the way down to a type's callables.
 *
 * <p>Coordinates are local to the view, not global. An earlier version composed one shared
 * world - each container sized to physically hold its contents - which reads well on a
 * small project and collapses on a large one: the per-level padding compounds, so the
 * modules of a real system spanned four orders of magnitude and the median one drew at a
 * fifth of a pixel. Since only one level is ever on screen and each view is framed on its
 * own contents, a global scale earns nothing; comparability *within* a view is what makes
 * it readable. Layouts stay deterministic, so a node keeps its position between visits.
 */
public final class MapLayout {

    private MapLayout() {
    }

    /**
     * Sizes are normalised inside each view rather than composed through the tree.
     *
     * <p>Composing radii bottom-up gives every level's padding a multiplicative effect, so
     * on a real project the modules ended up spanning four orders of magnitude and the
     * median one was a fifth of a pixel. Since each view is framed on its own contents,
     * an absolute world scale buys nothing: what matters is that within one view, sizes are
     * comparable and everything is big enough to see and to click.
     */
    private static final double MIN_DRAW_RADIUS = 3.0;
    private static final double MAX_DRAW_RADIUS = 34.0;
    /** how strongly size differences are compressed; below 1 pulls the extremes together. */
    private static final double SIZE_COMPRESSION = 0.38;

    public static void run(CodeGraph graph) {
        Map<String, List<GNode>> childrenOf = new HashMap<>();
        for (GNode node : graph.allNodes()) {
            GNode parent = graph.parentOf(node);
            if (parent != null) {
                childrenOf.computeIfAbsent(parent.qname, k -> new ArrayList<>()).add(node);
            }
        }
        // an edge already knows which view it belongs to, so the cells come for free
        Map<String, List<GEdge>> edgesOf = new HashMap<>();
        for (GEdge e : graph.allEdges()) {
            if (!e.parentQname.isEmpty()) {
                edgesOf.computeIfAbsent(e.parentQname, k -> new ArrayList<>()).add(e);
            }
        }

        Map<String, Double> weight = weights(graph, childrenOf);

        // one independent layout per view, with the children's sizes normalised against
        // the largest of them
        for (Map.Entry<String, List<GNode>> entry : childrenOf.entrySet()) {
            layoutView(entry.getValue(), edgesOf.getOrDefault(entry.getKey(), List.of()), weight);
        }
        List<GNode> modules = new ArrayList<>(graph.nodes(Layer.MODULE).values());
        modules.sort(Comparator.comparing(n -> n.qname));
        layoutView(modules, rootEdges(graph), weight);
    }

    /** Normalises the children's sizes, then lays them out. Coordinates stay local. */
    private static void layoutView(List<GNode> children, List<GEdge> edges,
                                   Map<String, Double> weight) {
        double heaviest = 0;
        for (GNode child : children) {
            heaviest = Math.max(heaviest, weight.getOrDefault(child.qname, 1.0));
        }
        for (GNode child : children) {
            double share = heaviest <= 0 ? 0
                    : weight.getOrDefault(child.qname, 1.0) / heaviest;
            child.r = MIN_DRAW_RADIUS + (MAX_DRAW_RADIUS - MIN_DRAW_RADIUS)
                    * Math.pow(share, SIZE_COMPRESSION);
        }
        layoutCell(children, edges);
    }

    /**
     * How much code sits under each node, summed up the tree. This is what a node's size
     * means: a package with a thousand lines under it draws bigger than one with ten.
     */
    private static Map<String, Double> weights(CodeGraph graph,
                                               Map<String, List<GNode>> childrenOf) {
        Map<String, Double> weight = new HashMap<>();
        List<GNode> all = new ArrayList<>(graph.allNodes());
        Map<String, Integer> depth = depths(graph);
        all.sort(Comparator.comparingInt((GNode n) -> depth.getOrDefault(n.qname, 0)).reversed());
        for (GNode node : all) {
            double own = Math.max(1, node.loc);
            for (GNode child : childrenOf.getOrDefault(node.qname, List.of())) {
                own += weight.getOrDefault(child.qname, 1.0);
            }
            weight.put(node.qname, own);
        }
        return weight;
    }

    /** Edges between top-level nodes, i.e. the module-to-module ones. */
    private static List<GEdge> rootEdges(CodeGraph graph) {
        List<GEdge> out = new ArrayList<>();
        for (GEdge e : graph.edges(Layer.MODULE).values()) {
            if (e.parentQname.isEmpty()) out.add(e);
        }
        return out;
    }

    private static Map<String, Integer> depths(CodeGraph graph) {
        Map<String, Integer> depth = new HashMap<>();
        for (GNode node : graph.allNodes()) {
            int d = 0;
            GNode current = graph.parentOf(node);
            while (current != null && d < 32) {
                d++;
                current = graph.parentOf(current);
            }
            depth.put(node.qname, d);
        }
        return depth;
    }

    /** Runs one force layout and returns the radius the result needs. */
    private static double layoutCell(List<GNode> nodes, List<GEdge> edges) {
        int n = nodes.size();
        if (n == 0) return 0;
        if (n == 1) {
            nodes.get(0).x = 0;
            nodes.get(0).y = 0;
            return nodes.get(0).r;
        }

        List<GNode> ordered = new ArrayList<>(nodes);
        // stable order keeps the map identical between runs on unchanged source
        ordered.sort(Comparator.comparing(node -> node.qname));

        Map<String, Integer> indexOf = new HashMap<>(n * 2);
        double[] x = new double[n];
        double[] y = new double[n];
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            GNode node = ordered.get(i);
            indexOf.put(node.qname, i);
            r[i] = Math.max(node.r, 1.0);
        }

        List<int[]> pairs = new ArrayList<>(edges.size());
        List<Double> weights = new ArrayList<>(edges.size());
        for (GEdge e : edges) {
            Integer a = indexOf.get(e.srcQname);
            Integer b = indexOf.get(e.dstQname);
            if (a == null || b == null || a.equals(b)) continue;
            pairs.add(new int[]{a, b});
            weights.add((double) e.weight);
        }
        int[] src = new int[pairs.size()];
        int[] dst = new int[pairs.size()];
        double[] w = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            src[i] = pairs.get(i)[0];
            dst[i] = pairs.get(i)[1];
            w[i] = weights.get(i);
        }

        ForceLayout layout = new ForceLayout(x, y, r, src, dst, w);
        layout.seedRing();
        layout.run(iterationsFor(n));
        double extent = layout.centerAndMeasure();

        for (int i = 0; i < n; i++) {
            ordered.get(i).x = x[i];
            ordered.get(i).y = y[i];
        }
        return extent;
    }

    private static int iterationsFor(int n) {
        if (n <= 2) return 1;
        if (n <= 60) return 320;
        if (n <= 300) return 260;
        if (n <= 1200) return 180;
        return 120;
    }

}
