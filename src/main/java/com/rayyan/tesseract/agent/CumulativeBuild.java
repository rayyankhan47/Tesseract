package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.blueprint.CompiledBlueprint;
import com.rayyan.tesseract.blueprint.PrimitiveBounds;
import com.rayyan.tesseract.render.IsoRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Map<String, int[]> elementBounds = new LinkedHashMap<>();

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
        String elementId = candidate.spec().id();
        java.util.LinkedHashSet<BlockOp> committed = new java.util.LinkedHashSet<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockOp op : candidate.proposedOps()) {
            if (op == null || op.block == null) continue;
            long key = pack(op.x, op.y, op.z);
            if (owned.containsKey(key)) {
                overlaps++;
                continue;
            }
            owned.put(key, new Ownership(elementId, op.block));
            committed.add(op);
            if (op.x < minX) minX = op.x; if (op.x > maxX) maxX = op.x;
            if (op.y < minY) minY = op.y; if (op.y > maxY) maxY = op.y;
            if (op.z < minZ) minZ = op.z; if (op.z > maxZ) maxZ = op.z;
        }
        if (overlaps > 0) {
            LOGGER.info("OVERLAP element={} skipped={} owned_by_earlier=true",
                    elementId, overlaps);
        }
        if (!committed.isEmpty()) {
            elementBounds.put(elementId, new int[]{minX, minY, minZ, maxX, maxY, maxZ});
        }
        locks.add(candidate);
        return committed;
    }

    /** Read-only view of all committed block ops across every lock. */
    public List<BlockOp> allOps() {
        List<BlockOp> out = new ArrayList<>(owned.size());
        for (Map.Entry<Long, Ownership> e : owned.entrySet()) {
            long packed = e.getKey();
            int[] xyz = unpack(packed);
            BlockOp op = new BlockOp();
            op.x = xyz[0]; op.y = xyz[1]; op.z = xyz[2];
            op.block = e.getValue().material();
            out.add(op);
        }
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

    /**
     * §7.3.1 — incremental {@link CompiledBlueprint} snapshot from the
     * currently committed ops. Every new {@link #commit} call refines
     * this output so downstream stages can replace their one-shot
     * compile step with progressive reads.
     */
    public CompiledBlueprint toCompiledBlueprint() {
        List<BlockOp> ops = allOps();
        Map<String, PrimitiveBounds> bounds = new LinkedHashMap<>(elementBounds.size());
        for (Map.Entry<String, int[]> e : elementBounds.entrySet()) {
            int[] box = e.getValue();
            bounds.put(e.getKey(), new PrimitiveBounds(
                    box[0], box[1], box[2],
                    box[3] - box[0] + 1,
                    box[4] - box[1] + 1,
                    box[5] - box[2] + 1));
        }
        return new CompiledBlueprint(ops, bounds);
    }

    /**
     * §7.3.2 — if {@code tesseract.debug.renders} is set, dump the
     * current cumulative render to {@code run/tesseract_debug/l4/}.
     * No-op when the debug flag isn't set. Failure-safe (logs and
     * swallows) — debug artefacts can't fail a build.
     */
    public void maybeDumpDebugFrame(String elementId, int ppb) {
        if (!IsoRenderer.isDebugEnabled()) return;
        try {
            byte[] png = render(ppb);
            Path dir = IsoRenderer.debugDir().resolve("l4");
            Files.createDirectories(dir);
            String safe = elementId == null ? "unknown" : elementId.replaceAll("[^A-Za-z0-9_.-]", "_");
            String name = String.format("cumulative_%03d_%s.png", locks.size(), safe);
            Files.write(dir.resolve(name), png);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("L4_DEBUG_DUMP skipped element={} reason={}", elementId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final long MASK_21 = (1L << 21) - 1L;
    private static final int COORD_BIAS = 1 << 20;

    private static long pack(int x, int y, int z) {
        long ux = ((long) x + COORD_BIAS) & MASK_21;
        long uy = ((long) y + COORD_BIAS) & MASK_21;
        long uz = ((long) z + COORD_BIAS) & MASK_21;
        return (ux << 42) | (uy << 21) | uz;
    }

    private static int[] unpack(long packed) {
        int x = (int) ((packed >> 42) & MASK_21) - COORD_BIAS;
        int y = (int) ((packed >> 21) & MASK_21) - COORD_BIAS;
        int z = (int) (packed & MASK_21) - COORD_BIAS;
        return new int[]{x, y, z};
    }
}
