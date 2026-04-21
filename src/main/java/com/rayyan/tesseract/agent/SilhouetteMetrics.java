package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.blueprint.Blueprint;

import java.util.List;

/**
 * Silhouette drift measurement per REFACTOR_3 §2.3.2.
 *
 * <p>Given the in-progress compiled block ops and the voxel mass sketch, this
 * class computes what fraction of built blocks fall outside the mass envelope.
 * A low drift means the build is staying inside the silhouette the LLM agreed
 * on; a high drift means the geometry pipeline has wandered.
 *
 * <p>Thresholds (soft 15% · hard 40%) drive feedback loops:
 * <ul>
 *   <li>drift ≥ {@link #SOFT_THRESHOLD}: log / surface to the user as a warning.</li>
 *   <li>drift ≥ {@link #HARD_THRESHOLD}: {@link #shouldRollback} returns true,
 *       signalling the L1 Architect should re-plan rather than continue patching.</li>
 * </ul>
 */
public final class SilhouetteMetrics {

    /** Soft target: warn above this. */
    public static final double SOFT_THRESHOLD = 0.15;

    /** Hard rollback threshold: above this, L1 re-plan is required. */
    public static final double HARD_THRESHOLD = 0.40;

    private SilhouetteMetrics() {}

    /**
     * Computes drift as {@code blocksOutsideMass / totalNonAirBlocks}.
     *
     * <p>Air ops (block id starting with {@code "minecraft:air"} or the literal
     * {@code "air"}) and ops outside the blueprint bounds are ignored in both
     * numerator and denominator.
     *
     * @param ops    compiled block ops in blueprint-local coordinates
     * @param mass   voxel mass sketch (null / empty → drift defined as 0.0)
     * @param bounds blueprint bounds used for voxel-cell mapping
     * @return drift fraction in [0.0, 1.0]; 0.0 if there is nothing to measure
     */
    public static double drift(List<BlockOp> ops, VoxelMass mass, Blueprint.Bounds bounds) {
        if (ops == null || ops.isEmpty()) return 0.0;
        if (mass == null || mass.filledCount() == 0) return 0.0;
        if (bounds == null) return 0.0;

        int r = mass.resolution();
        int[] xEdges = VoxelMassRenderer.linspaceEdges(bounds.sizeX(), r);
        int[] yEdges = VoxelMassRenderer.linspaceEdges(bounds.sizeY(), r);
        int[] zEdges = VoxelMassRenderer.linspaceEdges(bounds.sizeZ(), r);

        int total = 0;
        int outside = 0;

        for (BlockOp op : ops) {
            if (op == null) continue;
            if (isAir(op.block)) continue;
            if (op.x < 0 || op.x >= bounds.sizeX()) continue;
            if (op.y < 0 || op.y >= bounds.sizeY()) continue;
            if (op.z < 0 || op.z >= bounds.sizeZ()) continue;

            total++;
            int vx = cellIndex(xEdges, op.x);
            int vy = cellIndex(yEdges, op.y);
            int vz = cellIndex(zEdges, op.z);
            if (vx < 0 || vy < 0 || vz < 0) { outside++; continue; }
            if (!mass.isFilled(vx, vy, vz)) outside++;
        }

        return total == 0 ? 0.0 : (double) outside / (double) total;
    }

    /**
     * Returns the 1-based voxel index whose cell contains {@code coord}, or
     * {@code -1} if out of range. {@code edges} is the cumulative edge array
     * from {@link VoxelMassRenderer#linspaceEdges(int, int)} (length = res+1).
     */
    private static int cellIndex(int[] edges, int coord) {
        if (coord < edges[0] || coord >= edges[edges.length - 1]) return -1;
        // Linear scan is fine — resolution ≤ 64, so this is ≤ 64 comparisons.
        for (int i = 0; i < edges.length - 1; i++) {
            if (coord >= edges[i] && coord < edges[i + 1]) return i;
        }
        return -1;
    }

    private static boolean isAir(String block) {
        return block == null || block.isEmpty()
                || "air".equals(block) || "minecraft:air".equals(block);
    }

    // -------------------------------------------------------------------------
    // Threshold helpers
    // -------------------------------------------------------------------------

    public static boolean exceedsSoft(double drift) { return drift >= SOFT_THRESHOLD; }
    public static boolean shouldRollback(double drift) { return drift >= HARD_THRESHOLD; }

    public static String classify(double drift) {
        if (drift >= HARD_THRESHOLD) return "HARD_DRIFT";
        if (drift >= SOFT_THRESHOLD) return "SOFT_DRIFT";
        return "OK";
    }
}
