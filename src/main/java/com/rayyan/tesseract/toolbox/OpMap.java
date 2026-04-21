package com.rayyan.tesseract.toolbox;

import com.rayyan.tesseract.agent.BlockOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal helper used by {@link Toolbox} and {@link CompositionOps} to
 * manipulate block ops as a dense position → material map.
 *
 * <p>This is intentionally <em>not</em> public: callers only see
 * {@link Set}{@code <}{@link BlockOp}{@code >}. We use a map internally
 * because two {@link BlockOp} instances at the same coordinate with
 * different materials should dedup to one op (later-wins), and set
 * operations (subtract / intersect) need position-only equality which
 * the existing mutable {@link BlockOp} doesn't provide.
 *
 * <p>The coordinate encoding packs (x, y, z) into a single long so we
 * get constant-time lookup and don't allocate per-lookup.
 */
final class OpMap {

    /**
     * Per-axis range used for packing. Voxel-mass-local coordinates fit
     * easily inside this bound, and so do reasonably-sized world-relative
     * blueprints. Stepping outside the band throws an
     * {@link IllegalArgumentException} so we fail loudly instead of silently
     * colliding two distant coordinates on the same key.
     */
    static final int COORD_BOUND = 1 << 20;
    private static final long MASK_21 = (1L << 21) - 1L;

    private final LinkedHashMap<Long, BlockOp> ops = new LinkedHashMap<>();

    static long pack(int x, int y, int z) {
        if (Math.abs(x) >= COORD_BOUND || Math.abs(y) >= COORD_BOUND || Math.abs(z) >= COORD_BOUND) {
            throw new IllegalArgumentException("OpMap coordinate out of band ["
                    + -COORD_BOUND + ", " + COORD_BOUND + "): (" + x + "," + y + "," + z + ")");
        }
        long ux = (long) (x + COORD_BOUND) & MASK_21;
        long uy = (long) (y + COORD_BOUND) & MASK_21;
        long uz = (long) (z + COORD_BOUND) & MASK_21;
        return (ux << 42) | (uy << 21) | uz;
    }

    /**
     * Later-writer wins so {@link Toolbox#crenellate} etc. can overwrite
     * an input op's material at the same position.
     */
    void put(int x, int y, int z, String material) {
        if (material == null || material.isBlank()) return;
        BlockOp op = new BlockOp();
        op.x = x; op.y = y; op.z = z; op.block = material;
        ops.put(pack(x, y, z), op);
    }

    void putOp(BlockOp op) {
        if (op == null || op.block == null || op.block.isBlank()) return;
        ops.put(pack(op.x, op.y, op.z), op);
    }

    void merge(Set<BlockOp> other) {
        if (other == null) return;
        for (BlockOp op : other) putOp(op);
    }

    boolean containsPos(int x, int y, int z) {
        return ops.containsKey(pack(x, y, z));
    }

    int size() { return ops.size(); }

    List<BlockOp> valuesList() { return new ArrayList<>(ops.values()); }

    /**
     * Returns a deterministic {@link LinkedHashSet} so callers can iterate
     * in insertion order (useful for tests and debug logs).
     */
    Set<BlockOp> toSet() {
        return new LinkedHashSet<>(ops.values());
    }

    static OpMap fromSet(Set<BlockOp> s) {
        OpMap m = new OpMap();
        if (s != null) for (BlockOp op : s) m.putOp(op);
        return m;
    }

    /**
     * Iteration helper used by set operations — exposes packed keys so
     * callers can filter without re-packing.
     */
    Map<Long, BlockOp> raw() { return ops; }
}
