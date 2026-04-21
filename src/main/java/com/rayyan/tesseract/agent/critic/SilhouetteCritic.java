package com.rayyan.tesseract.agent.critic;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;
import com.rayyan.tesseract.agent.VoxelMass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * §8.1.1 — compares candidate element voxels to the {@link VoxelMass}
 * envelope. Pure geometry — no LLM.
 *
 * <p>Computes the fraction of voxels in {@code candidateOps} whose
 * coordinates fall on cells where the mass sketch is empty. Score is
 * {@code 1 - min(1, 2 * fractionOutside)} so small drift is tolerated.
 *
 * <p>When {@link BuildState#massSketch} is null or resolution mismatches,
 * returns a neutral opinion (score 1.0, empty patches) so the swarm
 * does not block the build.
 */
public final class SilhouetteCritic {

    /** Above this fraction, {@link #hardViolation(CriticOpinion)} is true (§8.3.3). */
    public static final double HARD_VIOLATION_THRESHOLD = 0.12;

    private SilhouetteCritic() {}

    /**
     * @param candidateOps voxels for this element only (L4 sandbox output).
     */
    public static CriticOpinion evaluate(BuildState state, Set<BlockOp> candidateOps) {
        if (state == null || state.massSketch() == null || candidateOps == null || candidateOps.isEmpty()) {
            return new CriticOpinion(CriticKind.SILHOUETTE, 1.0,
                    "no mass sketch or empty candidate — silhouette check waived",
                    List.of(), false, "", 0.0);
        }
        VoxelMass mass = state.massSketch();
        int res = mass.resolution();
        int outside = 0;
        int total = 0;
        for (BlockOp op : candidateOps) {
            if (op == null || op.block == null) continue;
            total++;
            int x = op.x, y = op.y, z = op.z;
            if (x < 0 || y < 0 || z < 0 || x >= res || y >= res || z >= res) {
                outside++;
            } else if (!mass.isFilled(x, y, z)) {
                outside++;
            }
        }
        if (total == 0) {
            return new CriticOpinion(CriticKind.SILHOUETTE, 1.0, "empty geometry", List.of(),
                    false, "", 0.0);
        }
        double frac = (double) outside / (double) total;
        double score = 1.0 - Math.min(1.0, 2.0 * frac);
        List<String> patches = new ArrayList<>();
        if (frac > 0.02) {
            patches.add("Trim or move blocks so more voxels sit inside the 16³ mass envelope "
                    + "(fraction outside mass: " + String.format("%.3f", frac) + ").");
        }
        String summary = String.format("fraction_outside_mass=%.3f outside=%d total=%d",
                frac, outside, total);
        return CriticOpinion.silhouette(score, summary, patches, frac);
    }

    public static boolean hardViolation(CriticOpinion o) {
        if (o == null || o.kind() != CriticKind.SILHOUETTE || o.fractionOutsideMass() == null) {
            return false;
        }
        return o.fractionOutsideMass() > HARD_VIOLATION_THRESHOLD;
    }
}
