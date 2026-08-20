package io.github.tobiaskp.codemap.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Force-directed placement for one cell of the map: a spring per edge, repulsion between
 * nodes, a little gravity to keep things together, and a collision pass so circles do not
 * sit on top of each other.
 *
 * <p>Repulsion is all-pairs while that is cheap and falls back to a uniform grid with a
 * cutoff for the occasional package with thousands of types.
 */
public final class ForceLayout {

    private static final int ALL_PAIRS_LIMIT = 1200;

    private final int n;
    private final double[] x, y, r;
    private final int[] edgeSrc, edgeDst;
    private final double[] edgeWeight;
    private final double[] dx, dy;
    /** ideal distance between two connected nodes. */
    private final double k;

    public ForceLayout(double[] x, double[] y, double[] r,
                       int[] edgeSrc, int[] edgeDst, double[] edgeWeight) {
        this.n = x.length;
        this.x = x;
        this.y = y;
        this.r = r;
        this.edgeSrc = edgeSrc;
        this.edgeDst = edgeDst;
        this.edgeWeight = edgeWeight;
        this.dx = new double[n];
        this.dy = new double[n];

        double area = 0;
        for (double radius : r) area += radius * radius * Math.PI;
        this.k = Math.max(4.0, 2.2 * Math.sqrt(Math.max(area, 1) / Math.max(1, n)));
    }

    /** Deterministic ring start: same input graph always produces the same map. */
    public void seedRing() {
        if (n == 1) {
            x[0] = 0;
            y[0] = 0;
            return;
        }
        double spread = k * Math.sqrt(n) * 0.5;
        for (int i = 0; i < n; i++) {
            // golden-angle spiral spreads nodes evenly instead of bunching them on a circle
            double angle = i * 2.39996323;
            double radius = spread * Math.sqrt((i + 0.5) / n);
            x[i] = Math.cos(angle) * radius;
            y[i] = Math.sin(angle) * radius;
        }
    }

    public void run(int iterations) {
        if (n <= 1) return;
        double temperature = k * 1.6;
        double cooling = Math.pow(0.02, 1.0 / Math.max(1, iterations));
        for (int step = 0; step < iterations; step++) {
            java.util.Arrays.fill(dx, 0);
            java.util.Arrays.fill(dy, 0);
            if (n <= ALL_PAIRS_LIMIT) repelAllPairs();
            else repelWithGrid();
            attract();
            gravity();
            applyDisplacement(temperature);
            temperature *= cooling;
        }
        // enough passes for the overlap relaxation to actually converge when radii
        // differ by an order of magnitude, as they do between a plugin and a core module
        for (int pass = 0; pass < 60; pass++) separate();
    }

    private void repelAllPairs() {
        double k2 = k * k;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double ux = x[i] - x[j];
                double uy = y[i] - y[j];
                double d2 = ux * ux + uy * uy;
                if (d2 < 1e-6) {
                    // identical positions: nudge them apart deterministically
                    ux = 1e-3 * (i + 1);
                    uy = 1e-3 * (j + 1);
                    d2 = ux * ux + uy * uy;
                }
                double force = k2 / d2;
                double d = Math.sqrt(d2);
                double fx = ux / d * force;
                double fy = uy / d * force;
                dx[i] += fx;
                dy[i] += fy;
                dx[j] -= fx;
                dy[j] -= fy;
            }
        }
    }

    /** Same force, but only against neighbours within a few cells; O(n) per iteration. */
    private void repelWithGrid() {
        double cell = k * 2.5;
        double k2 = k * k;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, x[i]);
            minY = Math.min(minY, y[i]);
        }
        int cols = (int) Math.ceil(Math.sqrt(n)) + 2;
        List<List<Integer>> buckets = new ArrayList<>(cols * cols);
        for (int i = 0; i < cols * cols; i++) buckets.add(new ArrayList<>());
        int[] cx = new int[n];
        int[] cy = new int[n];
        for (int i = 0; i < n; i++) {
            cx[i] = Math.min(cols - 1, Math.max(0, (int) ((x[i] - minX) / cell)));
            cy[i] = Math.min(cols - 1, Math.max(0, (int) ((y[i] - minY) / cell)));
            buckets.get(cy[i] * cols + cx[i]).add(i);
        }
        for (int i = 0; i < n; i++) {
            for (int gy = Math.max(0, cy[i] - 1); gy <= Math.min(cols - 1, cy[i] + 1); gy++) {
                for (int gx = Math.max(0, cx[i] - 1); gx <= Math.min(cols - 1, cx[i] + 1); gx++) {
                    for (int j : buckets.get(gy * cols + gx)) {
                        if (j <= i) continue;
                        double ux = x[i] - x[j];
                        double uy = y[i] - y[j];
                        double d2 = ux * ux + uy * uy;
                        if (d2 < 1e-6) {
                            ux = 1e-3 * (i + 1);
                            uy = 1e-3 * (j + 1);
                            d2 = ux * ux + uy * uy;
                        }
                        double force = k2 / d2;
                        double d = Math.sqrt(d2);
                        double fx = ux / d * force;
                        double fy = uy / d * force;
                        dx[i] += fx;
                        dy[i] += fy;
                        dx[j] -= fx;
                        dy[j] -= fy;
                    }
                }
            }
        }
    }

    private void attract() {
        for (int e = 0; e < edgeSrc.length; e++) {
            int i = edgeSrc[e];
            int j = edgeDst[e];
            if (i == j) continue;
            double ux = x[i] - x[j];
            double uy = y[i] - y[j];
            double d = Math.sqrt(ux * ux + uy * uy);
            if (d < 1e-6) continue;
            // log weighting keeps one very heavy edge from collapsing the whole cell
            double strength = 1.0 + Math.log1p(edgeWeight[e]) * 0.5;
            double force = d * d / k * strength;
            double fx = ux / d * force;
            double fy = uy / d * force;
            dx[i] -= fx;
            dy[i] -= fy;
            dx[j] += fx;
            dy[j] += fy;
        }
    }

    private void gravity() {
        double pull = k * 0.005;
        for (int i = 0; i < n; i++) {
            dx[i] -= x[i] * pull;
            dy[i] -= y[i] * pull;
        }
    }

    private void applyDisplacement(double temperature) {
        for (int i = 0; i < n; i++) {
            double d = Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i]);
            if (d < 1e-9) continue;
            double limit = Math.min(d, temperature);
            x[i] += dx[i] / d * limit;
            y[i] += dy[i] / d * limit;
        }
    }

    /** Pushes overlapping circles apart so every node stays clickable. */
    private void separate() {
        double gap = k * 0.18;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double ux = x[i] - x[j];
                double uy = y[i] - y[j];
                double d = Math.sqrt(ux * ux + uy * uy);
                double min = r[i] + r[j] + gap;
                if (d >= min) continue;
                if (d < 1e-6) {
                    ux = 1e-3 * (i + 1);
                    uy = 1e-3 * (j + 1);
                    d = Math.sqrt(ux * ux + uy * uy);
                }
                double push = (min - d) / 2.0;
                double px = ux / d * push;
                double py = uy / d * push;
                x[i] += px;
                y[i] += py;
                x[j] -= px;
                y[j] -= py;
            }
        }
    }

    /**
     * Centres the cell on the origin and reports the radius it ended up needing.
     *
     * <p>Nothing is scaled: a class glyph is the same size in every package, so the
     * enclosing circle grows to fit its contents instead. That is what makes zooming feel
     * like a map rather than a series of unrelated diagrams.
     */
    public double centerAndMeasure() {
        if (n == 0) return 0;
        double cxSum = 0, cySum = 0;
        for (int i = 0; i < n; i++) {
            cxSum += x[i];
            cySum += y[i];
        }
        double cx = cxSum / n;
        double cy = cySum / n;
        double extent = 0;
        for (int i = 0; i < n; i++) {
            x[i] -= cx;
            y[i] -= cy;
            extent = Math.max(extent, Math.hypot(x[i], y[i]) + r[i]);
        }
        return extent;
    }
}
