package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.toolbox.ToolboxExtension;
import com.rayyan.tesseract.toolbox.ToolboxExtensionStore;
import com.rayyan.tesseract.toolbox.UserDefExtractor;
import com.rayyan.tesseract.toolbox.UserDefExtractor.UserDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * §6.3.2 — after a successful build, the promoter inspects every
 * user-defined function the L4 agent wrote and rates each 0–1 for
 * "visually compelling, reusable geometry". Scores ≥ {@link #THRESHOLD}
 * are added to the on-disk {@link ToolboxExtensionStore} so the next
 * build sees them as community-contributed tools (§6.3.3).
 *
 * <p>Always uses Flash (see {@link TaskKind#TOOL_PROMOTE}). The call
 * is best-effort: parse / HTTP errors are logged and swallowed because
 * this runs after the user's build already succeeded, and we refuse to
 * surface a promotion failure as a build failure.
 *
 * <p>Wiring: the Step 7 L4 REPL will collect each element's script
 * into {@code BuildState.buildScripts}; this agent aggregates the
 * scripts and passes them to the LLM in one batch.
 */
public final class ToolPromoter {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.tool_promoter");

    public static final double THRESHOLD = 0.8;

    private static final String SYSTEM_PROMPT = """
            You are the ToolPromoter. The Tesseract mod's L4 REPL agent just
            finished a successful Minecraft build. During the build it wrote a
            small set of Python-like helper functions inside its sandbox.

            Your job: decide which of those helpers are worth adding to the
            permanent community-contributed toolbox. A good candidate is:
              * reusable — not hard-coded to this one building's dimensions
              * visually compelling — produces geometry with obvious
                architectural merit (rhythm, symmetry, ornament, silhouette)
              * self-contained — needs only the core 14 toolbox primitives
                plus ints / floats / strings / lists passed in as args

            A bad candidate is: one-shot glue code, trivial wrappers around
            `box`, debug scaffolding, or functions that rely on globals.

            Return ONLY this JSON — no prose, no markdown:
            {
              "reviews": [
                {
                  "name": "<function name>",
                  "score": 0.0-1.0,
                  "description": "<one-sentence summary of the effect>",
                  "usage_examples": ["<one or two inline-usage snippets>"],
                  "notes": "<optional reviewer notes, 1 line>"
                }
              ]
            }
            Emit one review per function, in the same order as the input.
            """;

    private static final String STRICT_REMINDER = """
            You MUST return ONLY a JSON object with the `reviews` array.
            Every entry MUST have `name`, `score`, `description`, and
            `usage_examples`. No prose, no markdown fences.
            """;

    private ToolPromoter() {}

    /**
     * Reviews all top-level defs across the build's scripts and
     * persists anything that scores ≥ {@link #THRESHOLD}. Returns the
     * list of newly-persisted extensions (possibly empty).
     *
     * <p>Failure modes:
     * <ul>
     *   <li>No scripts or no defs → returns empty synchronously.</li>
     *   <li>LLM error → logs warning, resolves with an empty list.</li>
     *   <li>Parse error on the LLM JSON → logs warning, swallows.</li>
     * </ul>
     */
    public static CompletableFuture<List<ToolboxExtension>> promote(
            BuildState state,
            GeminiClient gemini,
            ToolboxExtensionStore store,
            List<String> buildScripts,
            String buildId) {

        List<UserDef> allDefs = new ArrayList<>();
        if (buildScripts != null) {
            for (String script : buildScripts) {
                allDefs.addAll(UserDefExtractor.extract(script));
            }
        }
        if (allDefs.isEmpty()) {
            LOGGER.info("ToolPromoter: no user defs to review (build={})", buildId);
            return CompletableFuture.completedFuture(List.of());
        }

        String prompt = buildUserPrompt(state, allDefs);

        return gemini.call(TaskKind.TOOL_PROMOTE,
                        SYSTEM_PROMPT, prompt, List.of(),
                        ToolPromoter::isValidResponse,
                        STRICT_REMINDER,
                        state == null ? null : state.costTracker)
                .thenApply(raw -> applyReviews(raw, allDefs, store, state, buildId))
                .exceptionally(err -> {
                    LOGGER.warn("ToolPromoter: LLM call failed — swallowing ({})", err.getMessage());
                    return List.of();
                });
    }

    // -------------------------------------------------------------------------
    // Prompt plumbing
    // -------------------------------------------------------------------------

    private static String buildUserPrompt(BuildState state, List<UserDef> defs) {
        String userPrompt = state != null && state.originalPrompt != null ? state.originalPrompt : "(no prompt)";
        StringBuilder sb = new StringBuilder();
        sb.append("Original build prompt: ").append(userPrompt).append("\n\n");
        sb.append("User-defined functions to review (one block per function):\n\n");
        for (UserDef def : defs) {
            sb.append("---\n");
            sb.append("name: ").append(def.name()).append("\n");
            sb.append("signature: ").append(def.signature()).append("\n");
            sb.append("source:\n").append(def.source()).append("\n");
        }
        sb.append("---\n");
        sb.append("\nReturn reviews for all ").append(defs.size()).append(" function(s).");
        return sb.toString();
    }

    static boolean isValidResponse(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            return obj.has("reviews") && obj.get("reviews").isJsonArray();
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Review parsing + persistence
    // -------------------------------------------------------------------------

    private static List<ToolboxExtension> applyReviews(String raw,
                                                       List<UserDef> defs,
                                                       ToolboxExtensionStore store,
                                                       BuildState state,
                                                       String buildId) {
        List<ToolboxExtension> promoted = new ArrayList<>();
        if (store == null) return promoted;

        JsonObject root;
        try {
            root = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("ToolPromoter: failed to parse LLM response ({})", e.getMessage());
            return promoted;
        }
        JsonArray reviews = root.getAsJsonArray("reviews");
        if (reviews == null) return promoted;

        long now = System.currentTimeMillis();
        String userPrompt = state != null && state.originalPrompt != null ? state.originalPrompt : "";
        String buildUuid = buildId == null ? UUID.randomUUID().toString() : buildId;

        for (int i = 0; i < reviews.size(); i++) {
            JsonElement el = reviews.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject review = el.getAsJsonObject();
            String name = stringOr(review, "name", "");
            double score = review.has("score") ? review.get("score").getAsDouble() : 0.0;
            if (name.isBlank() || score < THRESHOLD) continue;

            UserDef match = defs.stream()
                    .filter(d -> d.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (match == null) continue;

            List<String> examples = new ArrayList<>();
            JsonElement exEl = review.get("usage_examples");
            if (exEl != null && exEl.isJsonArray()) {
                for (JsonElement e : exEl.getAsJsonArray()) {
                    if (e != null && e.isJsonPrimitive()) examples.add(e.getAsString());
                }
            }

            ToolboxExtension ext = new ToolboxExtension(
                    match.name(),
                    match.signature(),
                    match.source(),
                    stringOr(review, "description", ""),
                    examples,
                    buildUuid,
                    userPrompt,
                    score,
                    now);
            boolean replaced = store.upsert(ext);
            promoted.add(ext);
            LOGGER.info("ToolPromoter: {} '{}' (score={}) from build={}",
                    replaced ? "updated" : "added", name, score, buildUuid);
        }
        return promoted;
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static String stripJsonFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            if (first > 0) s = s.substring(first + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
