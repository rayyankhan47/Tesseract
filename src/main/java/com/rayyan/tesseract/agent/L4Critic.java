package com.rayyan.tesseract.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.ElementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Inner-loop critic for the L4 REPL (§7.1.1 critic seat).
 *
 * <p>This is the single "does the element look right?" check. Step 8
 * expands this into a five-critic parallel swarm; for Step 7 one
 * Flash call is enough to drive the REPL toward convergence.
 *
 * <p>Return contract: a small structured record the REPL can key into
 * — {@code approved} drives the "done or one more turn" decision,
 * {@code score} is the numeric target, and {@code issues} /
 * {@code suggestions} feed the next turn's user prompt so the LLM
 * has concrete things to react to.
 *
 * <p>Failure mode: any HTTP / parse error logs {@code CRITIC_SKIPPED}
 * and returns an auto-approval. Aligns with §3.3.1 — a broken critic
 * can never abort the build.
 */
final class L4Critic {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l4_critic");

    private static final String SYSTEM_PROMPT = """
            You are a specialist Minecraft architecture critic looking at a
            single architectural element that the L4 REPL just rendered. You
            will see:
              1. The concept image that defines the target style.
              2. A render of the element as it currently looks (isometric,
                 two views).
              3. The element's natural-language spec and parameters.

            Score how well the render matches the spec and the target style,
            from 0.0 (unrecognisable / wrong) to 1.0 (spec-perfect). If the
            score is >= 0.8 set approved=true. Otherwise list the one or two
            top issues and one concrete suggestion the REPL should try on
            its next turn.

            Return ONLY this JSON:
            {
              "approved": boolean,
              "score": 0.0-1.0,
              "issues": ["issue 1", ...],
              "suggestions": ["try X", ...],
              "notes": "one-line summary"
            }
            """;

    private static final String STRICT_REMINDER = """
            You MUST return ONLY the JSON object with keys approved, score,
            issues, suggestions, notes. No markdown, no prose outside it.
            """;

    static final double APPROVAL_THRESHOLD = 0.8;

    public record Verdict(boolean approved, double score,
                          List<String> issues, List<String> suggestions,
                          String notes) {

        public Verdict {
            issues = issues == null ? List.of() : List.copyOf(issues);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            if (notes == null) notes = "";
        }

        public static Verdict autoApproved(String why) {
            return new Verdict(true, 1.0, List.of(), List.of(), why);
        }
    }

    private L4Critic() {}

    /**
     * Evaluates the current element render against its spec.
     *
     * @param elementRender PNG of just this element, or the cumulative
     *                      build scoped to this element's voxels.
     * @param cumulativeRender PNG of the growing build including the
     *                         current element — optional, provides
     *                         context for "does this fit?".
     */
    public static CompletableFuture<Verdict> evaluate(
            BuildState state, GeminiClient gemini,
            ElementSpec spec, byte[] elementRender, byte[] cumulativeRender) {

        List<ImagePart> images = new ArrayList<>(3);
        ReferenceImage concept = primaryConcept(state);
        if (concept != null) images.add(new ImagePart(concept.bytes(), concept.mimeType()));
        if (elementRender != null && elementRender.length > 0) {
            images.add(new ImagePart(elementRender, "image/png"));
        }
        if (cumulativeRender != null && cumulativeRender.length > 0) {
            images.add(new ImagePart(cumulativeRender, "image/png"));
        }

        String userPrompt = buildPrompt(state, spec);

        return gemini.call(TaskKind.CRITIC_INNER,
                        SYSTEM_PROMPT, userPrompt, images,
                        L4Critic::isValidResponse, STRICT_REMINDER,
                        state == null ? null : state.costTracker)
                .thenApply(L4Critic::parse)
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED task=l4 element={} reason={}",
                            spec.id(), err.getMessage());
                    return Verdict.autoApproved("critic unavailable: " + err.getMessage());
                });
    }

    private static ReferenceImage primaryConcept(BuildState state) {
        if (state == null || state.referenceImages.isEmpty()) return null;
        int idx = Math.max(0, Math.min(state.selectedConceptIndex, state.referenceImages.size() - 1));
        return state.referenceImages.get(idx);
    }

    private static String buildPrompt(BuildState state, ElementSpec spec) {
        StringBuilder sb = new StringBuilder(1024);
        if (state != null && state.massPlan != null) {
            sb.append("Overall style: ").append(state.massPlan.overallStyle()).append('\n');
        }
        if (state != null && state.originalPrompt != null) {
            sb.append("Original prompt: ").append(state.originalPrompt).append('\n');
        }
        sb.append("Element id: ").append(spec.id()).append('\n');
        sb.append("Element description: ").append(spec.description()).append('\n');
        if (spec.parameters() != null && spec.parameters().size() > 0) {
            sb.append("Element parameters: ").append(spec.parameters()).append('\n');
        }
        sb.append("\nReturn ONLY the JSON verdict.");
        return sb.toString();
    }

    static boolean isValidResponse(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            return obj.has("approved") && obj.has("score");
        } catch (Exception e) {
            return false;
        }
    }

    private static Verdict parse(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            boolean approved = obj.has("approved") && obj.get("approved").getAsBoolean();
            double score = obj.has("score") ? clamp01(obj.get("score").getAsDouble()) : 0.0;
            List<String> issues = strings(obj, "issues");
            List<String> suggestions = strings(obj, "suggestions");
            String notes = obj.has("notes") && obj.get("notes").isJsonPrimitive()
                    ? obj.get("notes").getAsString() : "";
            if (score >= APPROVAL_THRESHOLD) approved = true;
            return new Verdict(approved, score, issues, suggestions, notes);
        } catch (Exception e) {
            LOGGER.warn("CRITIC_SKIPPED task=l4 reason=parse_error detail={}", e.getMessage());
            return Verdict.autoApproved("parse error");
        }
    }

    private static List<String> strings(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (var el : obj.getAsJsonArray(key)) {
            if (el != null && el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String stripJsonFence(String raw) {
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
