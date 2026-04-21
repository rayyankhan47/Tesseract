package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.agent.critic.CriticOpinion;
import com.rayyan.tesseract.agent.critic.CriticKind;
import com.rayyan.tesseract.agent.critic.SilhouetteCritic;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.ElementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * §8.3 — merges parallel {@link CriticOpinion}s into one critique for
 * the next L4 turn. Uses {@link TaskKind#CRITIC_RECONCILE} (Pro).
 *
 * <p>Also computes {@link #lockEarly(List, Set)} — when the mean
 * critic score is ≥ 0.85 and there is no hard silhouette violation
 * (§8.3.3), the caller may skip further REPL turns.
 */
public final class ReconcilerAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.reconciler");

    private static final String SYSTEM = """
            You reconcile multiple specialist critics' opinions about one Minecraft
            architectural element. You may receive conflicting advice (e.g. style wants
            ornament, silhouette wants trim). Produce ONE coherent direction for the
            next scripting turn: prioritise staying inside the mass envelope, then
            style fidelity, then detail.

            Return ONLY JSON:
            {
              "approved": boolean,
              "score": 0.0-1.0,
              "issues": ["top issue", ...],
              "suggestions": ["actionable fix", ...],
              "consolidated_patches": ["merged patch hints", ...],
              "summary": "one-line reconciled brief",
              "conflicts_resolved": "how you merged disagreements (optional)"
            }
            """;

    private static final String REMINDER = "Return ONLY the JSON object. No markdown fences.";

    public record ReconciledCritique(
            boolean approved,
            double score,
            List<String> issues,
            List<String> suggestions,
            List<String> consolidatedPatches,
            String summary,
            boolean lockEarly,
            double meanCriticScore,
            boolean hardSilhouetteViolation) {

        public ReconciledCritique {
            issues = issues == null ? List.of() : List.copyOf(issues);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            consolidatedPatches = consolidatedPatches == null ? List.of() : List.copyOf(consolidatedPatches);
            if (summary == null) summary = "";
        }
    }

    private ReconcilerAgent() {}

    /**
     * §8.3.3 — early lock when quality is already high and silhouette is clean.
     */
    public static boolean lockEarly(List<CriticOpinion> opinions, java.util.Set<BlockOp> candidateOps) {
        if (candidateOps == null || candidateOps.isEmpty()) return false;
        double mean = meanScore(opinions);
        CriticOpinion sil = find(opinions, CriticKind.SILHOUETTE);
        boolean hard = SilhouetteCritic.hardViolation(sil);
        return mean >= 0.85 && !hard;
    }

    public static double meanScore(List<CriticOpinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (CriticOpinion o : opinions) {
            if (o == null || o.skipped()) continue;
            sum += o.score();
            n++;
        }
        return n == 0 ? 0.0 : sum / n;
    }

    private static CriticOpinion find(List<CriticOpinion> opinions, CriticKind kind) {
        if (opinions == null) return null;
        for (CriticOpinion o : opinions) {
            if (o != null && o.kind() == kind) return o;
        }
        return null;
    }

    public static CompletableFuture<ReconciledCritique> reconcile(
            BuildState state,
            GeminiClient gemini,
            ElementSpec spec,
            List<CriticOpinion> opinions,
            byte[] cumulativeRender,
            Set<BlockOp> candidateOps) {

        double mean = meanScore(opinions);
        CriticOpinion sil = find(opinions, CriticKind.SILHOUETTE);
        boolean hard = SilhouetteCritic.hardViolation(sil);

        String userPrompt = buildUserPrompt(spec, opinions, mean, hard);

        List<ImagePart> images = new ArrayList<>(1);
        if (cumulativeRender != null && cumulativeRender.length > 0) {
            images.add(new ImagePart(cumulativeRender, "image/png"));
        }

        return gemini.call(TaskKind.CRITIC_RECONCILE, SYSTEM, userPrompt, images,
                        ReconcilerAgent::isValidReconcileResponse, REMINDER,
                        state == null ? null : state.costTracker())
                .thenApply(raw -> parse(raw, mean, hard, opinions, candidateOps))
                .exceptionally(err -> {
                    LOGGER.warn("RECONCILER_SKIPPED element={} reason={}", spec.id(), err.getMessage());
                    return fallback(opinions, mean, hard, err.getMessage(), candidateOps);
                });
    }

    /** Synchronous wrapper — L4 REPL blocks on the full stack. */
    public static ReconciledCritique reconcileSync(
            BuildState state,
            GeminiClient gemini,
            ElementSpec spec,
            List<CriticOpinion> opinions,
            byte[] cumulativeRender,
            Set<BlockOp> candidateOps) {
        return reconcile(state, gemini, spec, opinions, cumulativeRender, candidateOps).join();
    }

    static boolean isValidReconcileResponse(String raw) {
        try {
            JsonObject o = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
            return o.has("score");
        } catch (Exception e) {
            return false;
        }
    }

    private static ReconciledCritique parse(String raw, double mean, boolean hard,
                                            List<CriticOpinion> opinions,
                                            Set<BlockOp> candidateOps) {
        JsonObject o = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
        boolean approved = o.has("approved") && o.get("approved").getAsBoolean();
        double score = o.has("score") ? clamp01(o.get("score").getAsDouble()) : mean;
        String summary = text(o, "summary");
        List<String> issues = strings(o, "issues");
        List<String> suggestions = strings(o, "suggestions");
        List<String> patches = strings(o, "consolidated_patches");
        if (score >= 0.85) approved = true;
        boolean le = lockEarly(opinions, candidateOps);
        return new ReconciledCritique(approved, score, issues, suggestions, patches, summary,
                le, mean, hard);
    }

    private static ReconciledCritique fallback(List<CriticOpinion> opinions,
                                               double mean, boolean hard, String err,
                                               Set<BlockOp> candidateOps) {
        List<String> patches = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (CriticOpinion o : opinions) {
            if (o == null || o.skipped()) continue;
            patches.addAll(o.suggestedPatches());
            if (!o.summary().isBlank()) issues.add(o.kind() + ": " + o.summary());
        }
        boolean approved = mean >= 0.8 && !hard;
        boolean le = lockEarly(opinions, candidateOps);
        return new ReconciledCritique(approved, mean, issues, List.of(), patches,
                "reconciler fallback: " + err, le, mean, hard);
    }

    private static String buildUserPrompt(ElementSpec spec, List<CriticOpinion> opinions,
                                          double mean, boolean hardSilhouette) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Element id: ").append(spec.id()).append('\n');
        sb.append("Description: ").append(spec.description()).append('\n');
        sb.append("Pre-computed mean critic score (non-skipped): ").append(String.format("%.3f", mean)).append('\n');
        sb.append("Hard silhouette violation: ").append(hardSilhouette).append("\n\n");
        sb.append("Specialist opinions:\n");
        for (CriticOpinion o : opinions) {
            if (o == null) continue;
            sb.append("- ").append(o.kind()).append(" score=").append(String.format("%.3f", o.score()));
            if (o.skipped()) {
                sb.append(" SKIPPED: ").append(o.skipReason());
            } else {
                sb.append('\n').append("  ").append(o.summary()).append('\n');
                if (!o.suggestedPatches().isEmpty()) {
                    sb.append("  patches: ").append(o.suggestedPatches()).append('\n');
                }
                if (o.fractionOutsideMass() != null) {
                    sb.append("  fraction_outside_mass: ").append(o.fractionOutsideMass()).append('\n');
                }
            }
        }
        sb.append("\nMerge these into one actionable brief for the geometry REPL.");
        return sb.toString();
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (var el : o.getAsJsonArray(key)) {
            if (el != null && el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String stripFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
