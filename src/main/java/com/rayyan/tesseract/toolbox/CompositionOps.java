package com.rayyan.tesseract.toolbox;

import com.rayyan.tesseract.agent.BlockOp;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Composition operators over {@link Set}{@code <}{@link BlockOp}{@code >}.
 *
 * <p>Kept separate from {@link Toolbox} so the public API can expose them
 * either by plain name ({@code union(a,b)}) or as chainable instance
 * methods later without circular compile deps. Every operation is purely
 * functional — inputs are never mutated.
 *
 * <p>Set semantics are <em>position-based</em>: two ops at the same
 * {@code (x,y,z)} are treated as equal regardless of block id. This
 * matches the intuitive geometric meaning of "subtract B from A" —
 * remove from A every position B occupies.
 */
public final class CompositionOps {

    private CompositionOps() {}

    /**
     * Union of two op sets. Later-writer wins when both contain the same
     * position with different materials.
     */
    public static Set<BlockOp> union(Set<BlockOp> a, Set<BlockOp> b) {
        OpMap m = OpMap.fromSet(a);
        m.merge(b);
        return m.toSet();
    }

    /**
     * Translates {@code ops} by {@code (dx,dy,dz)} {@code count} times and
     * unions all copies (including the original). {@code count=0} returns
     * the input unchanged; {@code count=3} produces 4 copies at offsets
     * {@code 0..3×delta}.
     *
     * <p>Typical use: window bays. {@code repeat(bay, 0, 0, 4, 5)} tiles
     * the bay five times along Z at 4-block spacing.
     */
    public static Set<BlockOp> repeat(Set<BlockOp> ops, int dx, int dy, int dz, int count) {
        OpMap m = new OpMap();
        if (ops == null) return m.toSet();
        int n = Math.max(0, count);
        for (int i = 0; i <= n; i++) {
            int ox = dx * i, oy = dy * i, oz = dz * i;
            for (BlockOp op : ops) {
                m.put(op.x + ox, op.y + oy, op.z + oz, op.block);
            }
        }
        return m.toSet();
    }

    /**
     * Reflects {@code ops} across an axis-aligned plane and unions with
     * the originals.
     *
     * <p>Mirror math: a voxel at position {@code p} on the mirrored axis
     * maps to {@code (2*pivot - p)} since pivots live on voxel centres.
     *
     * @param axis {@code 'X'}, {@code 'Y'}, or {@code 'Z'} — the axis whose
     *             coordinate is flipped (so {@code 'X'} mirrors left↔right).
     * @param pivot the mirror-plane coordinate on that axis.
     */
    public static Set<BlockOp> mirror(Set<BlockOp> ops, char axis, int pivot) {
        OpMap m = OpMap.fromSet(ops);
        if (ops == null) return m.toSet();
        for (BlockOp op : ops) {
            int mx = op.x, my = op.y, mz = op.z;
            switch (axis) {
                case 'X' -> mx = 2 * pivot - op.x;
                case 'Y' -> my = 2 * pivot - op.y;
                case 'Z' -> mz = 2 * pivot - op.z;
                default -> throw new IllegalArgumentException("mirror axis must be X, Y, or Z; got " + axis);
            }
            m.put(mx, my, mz, op.block);
        }
        return m.toSet();
    }

    /**
     * Returns {@code A \ B} by position — every op in {@code a} whose
     * {@code (x,y,z)} is not also in {@code b}. Materials are ignored
     * for matching.
     */
    public static Set<BlockOp> subtract(Set<BlockOp> a, Set<BlockOp> b) {
        if (a == null || a.isEmpty()) return new LinkedHashSet<>();
        if (b == null || b.isEmpty()) return new LinkedHashSet<>(a);
        OpMap bm = OpMap.fromSet(b);
        OpMap out = new OpMap();
        for (BlockOp op : a) {
            if (!bm.containsPos(op.x, op.y, op.z)) out.putOp(op);
        }
        return out.toSet();
    }

    /**
     * Returns {@code A ∩ B} by position. Preserves the material from
     * {@code a} at each shared coordinate.
     */
    public static Set<BlockOp> intersect(Set<BlockOp> a, Set<BlockOp> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return new LinkedHashSet<>();
        OpMap bm = OpMap.fromSet(b);
        OpMap out = new OpMap();
        for (BlockOp op : a) {
            if (bm.containsPos(op.x, op.y, op.z)) out.putOp(op);
        }
        return out.toSet();
    }
}
