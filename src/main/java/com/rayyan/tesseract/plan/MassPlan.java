package com.rayyan.tesseract.plan;

import com.rayyan.tesseract.agent.VoxelMass;

import java.util.Collections;
import java.util.List;

/**
 * L1 Architect output — the top-level decomposition of the building into
 * major volumes (§5.1.1).
 *
 * <p>{@code overallStyle} is a short label ({@code "gothic"},
 * {@code "brutalist"}) that L2/L3 reference when choosing features and
 * materials. {@code narrative} is a one-paragraph rationale for the massing
 * choice; it's preserved in logs but not re-fed into later prompts.
 *
 * <p>{@link #coverageAgainst} computes the silhouette-coverage metric used by
 * the §5.1.3 critic: the fraction of filled voxels in the reference mass that
 * fall inside at least one {@link MajorMass} bounding box.
 */
public record MassPlan(
        String overallStyle,
        String narrative,
        List<MajorMass> masses,
        List<String> citing) {

    public MassPlan {
        if (overallStyle == null) overallStyle = "unspecified";
        if (narrative == null) narrative = "";
        if (masses == null || masses.isEmpty()) {
            throw new IllegalArgumentException("MassPlan requires at least one MajorMass");
        }
        masses = List.copyOf(masses);
        citing = citing == null ? Collections.emptyList() : List.copyOf(citing);
    }

    /** Smallest AABB enclosing every mass in this plan. */
    public BoundingBox envelope() {
        BoundingBox union = null;
        for (MajorMass m : masses) union = BoundingBox.union(union, m.bounds());
        return union;
    }

    /**
     * Silhouette coverage (§5.1.3): fraction of voxels filled in {@code mass}
     * that fall inside at least one {@link MajorMass} bounding box. 1.0 means
     * every voxel of the reference silhouette is claimed by some mass;
     * anything below ~0.80 signals the critic should retry.
     */
    public double coverageAgainst(VoxelMass mass) {
        if (mass == null || mass.filledCount() == 0) return 1.0;
        int covered = 0;
        int total = 0;
        int res = mass.resolution();
        for (int x = 0; x < res; x++) {
            for (int y = 0; y < res; y++) {
                for (int z = 0; z < res; z++) {
                    if (!mass.isFilled(x, y, z)) continue;
                    total++;
                    for (MajorMass m : masses) {
                        if (m.bounds().containsVoxel(x, y, z)) {
                            covered++;
                            break;
                        }
                    }
                }
            }
        }
        return total == 0 ? 1.0 : (double) covered / (double) total;
    }

    /**
     * Fraction of mass-plan volume that lies <em>outside</em> the reference
     * voxel mass — reports the L1 plan's overreach. High overreach means the
     * LLM's bounding boxes are wrapping empty space (the critic should tighten).
     */
    public double overreachAgainst(VoxelMass mass) {
        if (mass == null) return 0.0;
        long planVoxels = 0;
        long outsideVoxels = 0;
        int res = mass.resolution();
        for (MajorMass m : masses) {
            BoundingBox b = m.bounds().clampTo(res);
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        planVoxels++;
                        if (!mass.isFilled(x, y, z)) outsideVoxels++;
                    }
                }
            }
        }
        return planVoxels == 0 ? 0.0 : (double) outsideVoxels / (double) planVoxels;
    }
}
