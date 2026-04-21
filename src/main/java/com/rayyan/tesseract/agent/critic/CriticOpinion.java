package com.rayyan.tesseract.agent.critic;

import java.util.List;

/**
 * Typed output from one critic seat in the Step 8 swarm (§8.2.2).
 *
 * <p>{@code suggestedPatches} are short, actionable strings the
 * reconciler and L4 can key into — not blueprint JSON patches (those
 * come from the legacy VisualCriticAgent path).
 */
public record CriticOpinion(
        CriticKind kind,
        double score,
        String summary,
        List<String> suggestedPatches,
        boolean skipped,
        String skipReason,
        /** Silhouette seat only: fraction of element voxels outside the mass envelope. */
        Double fractionOutsideMass) {

    public CriticOpinion {
        if (summary == null) summary = "";
        if (skipReason == null) skipReason = "";
        suggestedPatches = suggestedPatches == null ? List.of() : List.copyOf(suggestedPatches);
        if (Double.isNaN(score)) score = 0.0;
    }

    public static CriticOpinion skipped(CriticKind kind, String reason) {
        return new CriticOpinion(kind, 0.0, "", List.of(), true,
                reason == null ? "CRITIC_SKIPPED" : reason, null);
    }

    public static CriticOpinion silhouette(double score, String summary,
                                         List<String> patches, double fractionOutside) {
        return new CriticOpinion(CriticKind.SILHOUETTE, score, summary, patches,
                false, "", fractionOutside);
    }
}
