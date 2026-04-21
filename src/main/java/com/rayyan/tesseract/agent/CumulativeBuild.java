package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.render.IsoRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-unsafe accumulator for the build's locked elements (§7.3).
 *
 * <p>Maintains:
 * <ul>
 *   <li>A position-keyed map of owned voxels so overlap detection
 *       is O(1) per candidate (§7.3.3).</li>
 *   <li>Insertion-ordered list of {@link ElementLock}s so we can
 *       re-render and reason about build order.</li>
 * </ul>
 *
 * <p>Earlier-committed wins: when a new element tries to write to a
 * voxel already owned by a prior {@link ElementLock}, the new op is
 * dropped and an {@code OVERLAP} count is incremented for the
 * current element. The element's {@link ElementLock#committedOps()}
 * reflects what actually went through.
 *
 * <p>Coordinate convention: ops are in voxel-mass-local space (same
 * 16³ grid the MassPlan uses). {@link #render(int)} produces an
 * isometric PNG sized for the full envelope. Step 11 handles world
 * placement.
 */
public final class CumulativeBuild {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.cumulative");

    private final int resolution;
    private final Map<Long, Ownership> owned = new LinkedHashMap<>();
    private final List<ElementLock> locks = new ArrayList<>();

    private record Ownership(String elementId, String material) {}

    public CumulativeBuild() { this(16); }

    public CumulativeBuild(int resolution) {
        this.resolution = Math.max(1, resolution);
    }

    public int resolution() { return resolution; }
    public List<ElementLock> locks() { return List.copyOf(locks); }
    public int ownedVoxels() { return owned.size(); }

    /**
     * Attempts to commit {@code candidate}'s ops. Returns the subset
     * that actually succeeded — anything landing on a voxel owned by
     * an earlier lock is silently dropped.
     *
     * <p>This is the core of §7.3.3. Callers pass the committed set
     * back as the {@link ElementLock#committedOps()} so the timeline
     * reflects what's really in the world.
     */
    public Set<BlockOp> commit(ElementLock candidate) {
        int overlaps = 0;
        java.util.LinkedHashSet<BlockOp> committed = new java.util.LinkedHashSet<>();
        for (BlockOp op : candidate.proposedOps()) {
            if (op == null || op.block == null) continue;
            long key = pack(op.x, op.y, op.z);
            if (owned.containsKey(key)) {
                overlaps++;
                continue;
            }
            owned.put(key, new Ownership(candidate.spec().id(), op.block));
            committed.add(op);
        }
        if (overlaps > 0) {
            LOGGER.info("OVERLAP element={} skipped={} owned_by_earlier=true",
                    candidate.spec().id(), overlaps);
        }
        locks.add(candidate);
        return committed;
    }

    /** Read-only view of all committed block ops across every lock. */
    public List<BlockOp> allOps() {
        List<BlockOp> out = new ArrayList<>(owned.size());
        for (ElementLock lock : locks) out.addAll(lock.committedOps());
        return out;
    }

    /**
     * Renders the full cumulative build to PNG at {@code ppb} pixels
     * per block. Cheap enough to call between every element lock
     * (§7.3.2).
     */
    public byte[] render(int ppb) {
        List<BlockOp> ops = allOps();
        Blueprint.Bounds bounds = new Blueprint.Bounds(resolution, resolution, resolution);
        return IsoRenderer.renderPng(ops, bounds, ppb);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long pack(int x, int y, int z) {
        long ux = ((long) x + (1 << 20)) & ((1L << 21) - 1L);
        long uy = ((long) y + (1 << 20)) & ((1L << 21) - 1L);
        long uz = ((long) z + (1 << 20)) & ((1L << 21) - 1L);
        return (ux << 42) | (uy << 21) | uz;
    }
}
