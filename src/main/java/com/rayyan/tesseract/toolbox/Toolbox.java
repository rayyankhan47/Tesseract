package com.rayyan.tesseract.toolbox;

import com.rayyan.tesseract.agent.BlockOp;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Core geometric toolbox — §6.1 of REFACTOR_3.
 *
 * <p>Fourteen pure functions the L4 REPL agent calls to produce block
 * geometry. Every function returns a freshly-allocated
 * {@link LinkedHashSet}{@code <}{@link BlockOp}{@code >} so callers can
 * merge results freely without aliasing side-effects.
 *
 * <p>All coordinates are inclusive integers in the caller's coordinate
 * space. Implementations clamp or reject out-of-range input rather than
 * crashing so sandbox scripts can't stall the build on a typo.
 *
 * <p>Categories:
 * <ul>
 *   <li><b>Fills:</b> {@link #box}, {@link #cylinder}, {@link #pyramid}, {@link #sphere}.
 *   <li><b>Outlines:</b> {@link #walls}, {@link #frame}, {@link #line}.
 *   <li><b>Curves:</b> {@link #arc}.
 *   <li><b>Composition:</b> {@link #repeat}, {@link #mirror},
 *       {@link #subtract}, {@link #intersect} — see {@link CompositionOps}.
 *   <li><b>Decoration:</b> {@link #crenellate}, {@link #scatter}.
 * </ul>
 *
 * <p>See {@code docs/toolbox.md} for worked examples. The doc is read
 * verbatim into every L4 system prompt, so the docstrings here and the
 * markdown reference should stay in sync.
 */
public final class Toolbox {

    private Toolbox() {}

    // =========================================================================
    // Fills
    // =========================================================================

    /**
     * Inclusive-ended axis-aligned filled box.
     * {@code box(0,0,0, 3,3,3, "stone")} fills a 4×4×4 cube (64 blocks).
     * Coordinates are normalised so {@code x1>x2} still works.
     */
    public static Set<BlockOp> box(int x1, int y1, int z1,
                                   int x2, int y2, int z2,
                                   String material) {
        OpMap m = new OpMap();
        int[] xs = order(x1, x2); int[] ys = order(y1, y2); int[] zs = order(z1, z2);
        for (int x = xs[0]; x <= xs[1]; x++) {
            for (int y = ys[0]; y <= ys[1]; y++) {
                for (int z = zs[0]; z <= zs[1]; z++) {
                    m.put(x, y, z, material);
                }
            }
        }
        return m.toSet();
    }

    /**
     * Solid vertical cylinder centred at {@code (cx, cz)} from {@code y1}
     * to {@code y2} inclusive, axis = Y. Blocks whose centre falls within
     * {@code radius + 0.5} of the central axis are filled (half-step
     * widens the naïve disc so cylinders read as round, not jaggy).
     */
    public static Set<BlockOp> cylinder(double cx, double cz,
                                        int y1, int y2,
                                        double radius,
                                        String material) {
        OpMap m = new OpMap();
        int[] ys = order(y1, y2);
        double rMax = radius + 0.5;
        double rSq = rMax * rMax;
        int iR = (int) Math.ceil(rMax);
        int baseX = (int) Math.round(cx);
        int baseZ = (int) Math.round(cz);
        for (int y = ys[0]; y <= ys[1]; y++) {
            for (int x = baseX - iR; x <= baseX + iR; x++) {
                for (int z = baseZ - iR; z <= baseZ + iR; z++) {
                    double dx = x - cx;
                    double dz = z - cz;
                    if (dx * dx + dz * dz <= rSq) m.put(x, y, z, material);
                }
            }
        }
        return m.toSet();
    }

    /**
     * Solid stepped square pyramid. Apex is at {@code (cx, y1 + height - 1, cz)};
     * base layer at {@code y1} has radius {@code baseRadius}. Radius
     * shrinks by one block per Y step (so a pyramid of height 4 with
     * baseRadius 3 has radii 3, 2, 1, 0 from bottom to top).
     *
     * <p>For a cone-style pyramid (round instead of square), pass the
     * same params to {@link #cylinder} in a loop.
     */
    public static Set<BlockOp> pyramid(int cx, int cz,
                                       int y1, int height,
                                       int baseRadius,
                                       String material) {
        OpMap m = new OpMap();
        for (int step = 0; step < height; step++) {
            int r = baseRadius - step;
            if (r < 0) break;
            int y = y1 + step;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    m.put(x, y, z, material);
                }
            }
        }
        return m.toSet();
    }

    /**
     * Solid sphere centred at {@code (cx, cy, cz)} with the given radius.
     * Uses the same half-step widening as {@link #cylinder}.
     */
    public static Set<BlockOp> sphere(double cx, double cy, double cz,
                                      double radius, String material) {
        OpMap m = new OpMap();
        double rMax = radius + 0.5;
        double rSq = rMax * rMax;
        int iR = (int) Math.ceil(rMax);
        int bx = (int) Math.round(cx), by = (int) Math.round(cy), bz = (int) Math.round(cz);
        for (int x = bx - iR; x <= bx + iR; x++) {
            for (int y = by - iR; y <= by + iR; y++) {
                for (int z = bz - iR; z <= bz + iR; z++) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    if (dx * dx + dy * dy + dz * dz <= rSq) m.put(x, y, z, material);
                }
            }
        }
        return m.toSet();
    }

    // =========================================================================
    // Outlines
    // =========================================================================

    /**
     * Four vertical walls of an axis-aligned box. Interior and top/bottom
     * are empty — use {@link #frame} for the 12 edges only, or {@link #box}
     * for a solid.
     */
    public static Set<BlockOp> walls(int x1, int y1, int z1,
                                     int x2, int y2, int z2,
                                     String material) {
        OpMap m = new OpMap();
        int[] xs = order(x1, x2); int[] ys = order(y1, y2); int[] zs = order(z1, z2);
        for (int y = ys[0]; y <= ys[1]; y++) {
            for (int x = xs[0]; x <= xs[1]; x++) {
                m.put(x, y, zs[0], material);
                m.put(x, y, zs[1], material);
            }
            for (int z = zs[0]; z <= zs[1]; z++) {
                m.put(xs[0], y, z, material);
                m.put(xs[1], y, z, material);
            }
        }
        return m.toSet();
    }

    /**
     * Twelve edges of an axis-aligned box — a wire-frame. Useful for
     * decorative ribs (vaulted ceilings, I-beam skeletons).
     */
    public static Set<BlockOp> frame(int x1, int y1, int z1,
                                     int x2, int y2, int z2,
                                     String material) {
        OpMap m = new OpMap();
        int[] xs = order(x1, x2); int[] ys = order(y1, y2); int[] zs = order(z1, z2);
        for (int x = xs[0]; x <= xs[1]; x++) {
            m.put(x, ys[0], zs[0], material);
            m.put(x, ys[0], zs[1], material);
            m.put(x, ys[1], zs[0], material);
            m.put(x, ys[1], zs[1], material);
        }
        for (int y = ys[0]; y <= ys[1]; y++) {
            m.put(xs[0], y, zs[0], material);
            m.put(xs[0], y, zs[1], material);
            m.put(xs[1], y, zs[0], material);
            m.put(xs[1], y, zs[1], material);
        }
        for (int z = zs[0]; z <= zs[1]; z++) {
            m.put(xs[0], ys[0], z, material);
            m.put(xs[0], ys[1], z, material);
            m.put(xs[1], ys[0], z, material);
            m.put(xs[1], ys[1], z, material);
        }
        return m.toSet();
    }

    /**
     * 3D Bresenham line between two integer endpoints (both endpoints are
     * included). Algorithm credit: Amanatides-Woo style driver with the
     * dominant axis selected per call.
     */
    public static Set<BlockOp> line(int x1, int y1, int z1,
                                    int x2, int y2, int z2,
                                    String material) {
        OpMap m = new OpMap();
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;

        int x = x1, y = y1, z = z1;
        m.put(x, y, z, material);

        if (dx >= dy && dx >= dz) {
            int err1 = 2 * dy - dx, err2 = 2 * dz - dx;
            for (int i = 0; i < dx; i++) {
                if (err1 > 0) { y += sy; err1 -= 2 * dx; }
                if (err2 > 0) { z += sz; err2 -= 2 * dx; }
                err1 += 2 * dy;
                err2 += 2 * dz;
                x += sx;
                m.put(x, y, z, material);
            }
        } else if (dy >= dx && dy >= dz) {
            int err1 = 2 * dx - dy, err2 = 2 * dz - dy;
            for (int i = 0; i < dy; i++) {
                if (err1 > 0) { x += sx; err1 -= 2 * dy; }
                if (err2 > 0) { z += sz; err2 -= 2 * dy; }
                err1 += 2 * dx;
                err2 += 2 * dz;
                y += sy;
                m.put(x, y, z, material);
            }
        } else {
            int err1 = 2 * dx - dz, err2 = 2 * dy - dz;
            for (int i = 0; i < dz; i++) {
                if (err1 > 0) { x += sx; err1 -= 2 * dz; }
                if (err2 > 0) { y += sy; err2 -= 2 * dz; }
                err1 += 2 * dx;
                err2 += 2 * dy;
                z += sz;
                m.put(x, y, z, material);
            }
        }
        return m.toSet();
    }

    // =========================================================================
    // Curves
    // =========================================================================

    /**
     * 2D arc in a plane perpendicular to {@code axis}. Angles are in
     * degrees, increasing counter-clockwise looking down the axis. The
     * arc is single-voxel-thick.
     *
     * @param axis {@code 'X'}, {@code 'Y'}, or {@code 'Z'} — the axis
     *             perpendicular to the arc's plane.
     */
    public static Set<BlockOp> arc(double cx, double cy, double cz,
                                   double radius, double startDeg, double endDeg,
                                   char axis, String material) {
        OpMap m = new OpMap();
        double start = Math.toRadians(startDeg);
        double end = Math.toRadians(endDeg);
        if (end < start) end += 2 * Math.PI;
        double span = end - start;
        int steps = Math.max(12, (int) Math.ceil(radius * span * 2.0));

        for (int i = 0; i <= steps; i++) {
            double t = start + (span * i) / steps;
            double u = Math.cos(t) * radius;
            double v = Math.sin(t) * radius;
            int ix, iy, iz;
            switch (axis) {
                case 'X' -> { ix = (int) Math.round(cx); iy = (int) Math.round(cy + u); iz = (int) Math.round(cz + v); }
                case 'Y' -> { ix = (int) Math.round(cx + u); iy = (int) Math.round(cy); iz = (int) Math.round(cz + v); }
                case 'Z' -> { ix = (int) Math.round(cx + u); iy = (int) Math.round(cy + v); iz = (int) Math.round(cz); }
                default -> throw new IllegalArgumentException("arc axis must be X, Y, or Z; got " + axis);
            }
            m.put(ix, iy, iz, material);
        }
        return m.toSet();
    }

    // =========================================================================
    // Decoration
    // =========================================================================

    /**
     * Turns the top-Y layer of {@code wallTop} into a crenellated parapet.
     * For every input block at the parapet's maximum Y, blocks are kept
     * when {@code ((x + z + offset) / period) % 2 == 0} (the "merlons")
     * and the gaps become missing blocks. Raised merlons get a block
     * added one Y above.
     *
     * <p>Use: give it the top row of a wall (e.g. from {@link #walls})
     * and it returns the crenellated replacement layer. Callers combine
     * with the rest of the wall via {@link CompositionOps#union}.
     */
    public static Set<BlockOp> crenellate(Set<BlockOp> wallTop,
                                          int period, int offset,
                                          String material) {
        if (wallTop == null || wallTop.isEmpty() || period <= 0) {
            return new LinkedHashSet<>();
        }
        OpMap m = new OpMap();
        int maxY = Integer.MIN_VALUE;
        for (BlockOp op : wallTop) if (op.y > maxY) maxY = op.y;

        for (BlockOp op : wallTop) {
            if (op.y != maxY) {
                m.putOp(op);
                continue;
            }
            boolean merlon = (Math.floorMod(op.x + op.z + offset, 2 * period)) < period;
            if (merlon) {
                m.putOp(op);
                m.put(op.x, op.y + 1, op.z, material);
            }
            // crenel: leave the gap (don't emit the op)
        }
        return m.toSet();
    }

    /**
     * Deterministic block scatter inside an axis-aligned bounding box.
     * Each voxel is filled with probability {@code density}; the
     * pseudo-random generator is seeded by {@code seed} so reruns with
     * the same seed produce identical output.
     */
    public static Set<BlockOp> scatter(int x1, int y1, int z1,
                                       int x2, int y2, int z2,
                                       double density, long seed,
                                       String material) {
        if (density <= 0.0) return new LinkedHashSet<>();
        double d = Math.min(1.0, density);
        Random rng = new Random(seed);
        OpMap m = new OpMap();
        int[] xs = order(x1, x2); int[] ys = order(y1, y2); int[] zs = order(z1, z2);
        for (int x = xs[0]; x <= xs[1]; x++) {
            for (int y = ys[0]; y <= ys[1]; y++) {
                for (int z = zs[0]; z <= zs[1]; z++) {
                    if (rng.nextDouble() < d) m.put(x, y, z, material);
                }
            }
        }
        return m.toSet();
    }

    // =========================================================================
    // Composition pass-throughs — see CompositionOps for implementations
    // =========================================================================

    /** See {@link CompositionOps#repeat}. */
    public static Set<BlockOp> repeat(Set<BlockOp> ops, int dx, int dy, int dz, int count) {
        return CompositionOps.repeat(ops, dx, dy, dz, count);
    }

    /** See {@link CompositionOps#mirror}. */
    public static Set<BlockOp> mirror(Set<BlockOp> ops, char axis, int pivot) {
        return CompositionOps.mirror(ops, axis, pivot);
    }

    /** See {@link CompositionOps#subtract}. */
    public static Set<BlockOp> subtract(Set<BlockOp> a, Set<BlockOp> b) {
        return CompositionOps.subtract(a, b);
    }

    /** See {@link CompositionOps#intersect}. */
    public static Set<BlockOp> intersect(Set<BlockOp> a, Set<BlockOp> b) {
        return CompositionOps.intersect(a, b);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int[] order(int a, int b) {
        return a <= b ? new int[]{a, b} : new int[]{b, a};
    }
}
