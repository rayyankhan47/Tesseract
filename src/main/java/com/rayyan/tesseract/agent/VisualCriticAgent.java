package com.rayyan.tesseract.agent;

import com.google.gson.*;
import com.rayyan.tesseract.api.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sends the isometric render of the current blueprint to Gemini Vision and
 * parses the structured critique response.
 *
 * <p>On malformed or missing JSON: fail-soft — returns {@link Critique#satisfied()}
 * so a broken critic never aborts a structurally valid build.
 *
 * <p>On bad patch entries (unknown ids, missing fields): the entry is silently
 * dropped and the rest of the critique is still applied.
 */
public final class VisualCriticAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.visual_critic");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static final Set<String> VALID_OPS = Set.of("modify", "add", "remove", "replace");

    private VisualCriticAgent() {}

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    static final String SYSTEM_PROMPT =
        "You are a senior Minecraft architecture critic reviewing an in-progress build.\n" +
        "You will be shown an isometric render of the current compiled blueprint " +
        "(two views: front and back, side-by-side).\n\n" +

        "Your task: decide if the build is visually coherent and matches the player's request. " +
        "If it already looks good, set satisfied=true and stop. " +
        "If it needs work, emit a minimal patch list.\n\n" +

        "=== RESPONSE FORMAT ===\n" +
        "Respond ONLY with a single JSON object:\n" +
        "{\n" +
        "  \"satisfied\": boolean,\n" +
        "  \"issues\": [\"human-readable issue 1\", ...],\n" +
        "  \"patch\": [ <PatchOp>, ... ]\n" +
        "}\n\n" +

        "=== PATCH OP TYPES ===\n\n" +
        "Modify one field on an existing primitive:\n" +
        "  { \"op\": \"modify\", \"id\": \"<primitive_id>\", \"field\": \"<dot.path>\", \"value\": <new_value> }\n\n" +
        "  Dot-path examples:\n" +
        "    \"height\"                    → changes the primitive's height param\n" +
        "    \"overhang\"                  → changes overhang (gable_roof)\n" +
        "    \"openings[0].u_offset\"      → changes u_offset of the first opening\n" +
        "    \"openings[0].width\"         → changes width of the first opening\n" +
        "    \"battlements\"               → sets battlements true/false on flat_roof\n\n" +
        "Add a new primitive at the end of the primitives list:\n" +
        "  { \"op\": \"add\", \"primitive\": { <full primitive object> } }\n\n" +
        "Remove a primitive by id:\n" +
        "  { \"op\": \"remove\", \"id\": \"<primitive_id>\" }\n\n" +
        "Replace a primitive body in-place (preserves list position and id):\n" +
        "  { \"op\": \"replace\", \"id\": \"<primitive_id>\", \"primitive\": { <new primitive body> } }\n\n" +

        "=== RULES ===\n" +
        "1. Prefer modify over replace for small tweaks.\n" +
        "2. Never reference an id that doesn't appear in the current blueprint.\n" +
        "3. Patches must not push coordinates outside the blueprint bounds.\n" +
        "4. Keep patches minimal — fix the top 1-2 most impactful issues only.\n" +
        "5. If the build looks good (or good enough), set satisfied=true and patch=[].\n" +
        "6. Only emit valid JSON. No markdown fences, no prose outside the JSON object.";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs a visual critique pass asynchronously.
     *
     * <p>Requires {@code state.lastRenderPng} to be set (call {@link
     * com.rayyan.tesseract.render.IsoRenderer#renderPng} first) and
     * {@code state.blueprint} to be the current blueprint.
     */
    public static void run(BuildState state,
                           GeminiClient gemini,
                           Consumer<Critique> onComplete,
                           Consumer<String> onError) {
        if (state.lastRenderPng == null || state.lastRenderPng.length == 0) {
            LOGGER.error("VisualCriticAgent: lastRenderPng is null — cannot critique without a render");
            onError.accept("VisualCriticAgent: no render PNG available");
            return;
        }
        if (state.blueprint == null) {
            LOGGER.error("VisualCriticAgent: no blueprint to critique");
            onError.accept("VisualCriticAgent: no blueprint set");
            return;
        }

        String userPrompt = buildUserPrompt(state);
        LOGGER.info("VisualCriticAgent: critiquing '{}' (iter {})", state.blueprint.name, state.iterationCount);

        gemini.complete(SYSTEM_PROMPT, userPrompt, state.lastRenderPng, "image/png")
              .whenComplete((raw, ex) -> {
                  if (ex != null) {
                      LOGGER.warn("VisualCriticAgent: Gemini call failed: {}", ex.getMessage());
                      onError.accept("VisualCriticAgent: Gemini call failed: " + ex.getMessage());
                      return;
                  }
                  try {
                      Critique critique = parse(raw, state.blueprint);
                      LOGGER.info("VisualCriticAgent: satisfied={}, {} issues, {} patch ops",
                              critique.satisfied(), critique.issues().size(), critique.patch().size());
                      if (!critique.issues().isEmpty()) {
                          LOGGER.info("VisualCriticAgent issues: {}", critique.issues());
                      }
                      onComplete.accept(critique);
                  } catch (Exception e) {
                      LOGGER.warn("VisualCriticAgent: failed to parse response — failing soft (satisfied=true). Raw: {}",
                              preview(raw), e);
                      onComplete.accept(Critique.converged());
                  }
              });
    }

    // -------------------------------------------------------------------------
    // User prompt
    // -------------------------------------------------------------------------

    static String buildUserPrompt(BuildState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build request: \"").append(state.originalPrompt).append("\"\n\n");
        if (state.spec != null) {
            sb.append("Interpreted spec:\n").append(GSON.toJson(state.spec)).append("\n\n");
        }
        sb.append("Current blueprint (").append(state.blueprint.primitives.size())
          .append(" primitives):\n").append(state.blueprint.rawJson).append("\n\n");
        sb.append("The attached image shows the isometric render of this blueprint ");
        sb.append("(front view on left, back view on right).\n\n");
        sb.append("Does this look correct? Respond with the JSON critique object.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    public static Critique parse(String raw, com.rayyan.tesseract.blueprint.Blueprint blueprint) {
        if (raw == null || raw.isBlank()) return Critique.converged();

        // Strip markdown fences if present
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            int first = cleaned.indexOf('\n');
            int last  = cleaned.lastIndexOf("```");
            if (first != -1 && last > first) {
                cleaned = cleaned.substring(first + 1, last).strip();
            }
        }

        JsonObject obj;
        try {
            obj = JsonParser.parseString(cleaned).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("VisualCriticAgent: could not parse critique as JSON object. raw: {}", preview(raw));
            return Critique.converged();
        }

        boolean satisfied = obj.has("satisfied") && obj.get("satisfied").getAsBoolean();

        List<String> issues = new ArrayList<>();
        if (obj.has("issues") && obj.get("issues").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("issues")) {
                if (el.isJsonPrimitive()) issues.add(el.getAsString());
            }
        }

        List<Patch> patches = new ArrayList<>();
        if (obj.has("patch") && obj.get("patch").isJsonArray()) {
            Set<String> knownIds = new java.util.HashSet<>();
            for (com.rayyan.tesseract.blueprint.Primitive p : blueprint.primitives) {
                knownIds.add(p.id);
            }

            for (JsonElement el : obj.getAsJsonArray("patch")) {
                if (!el.isJsonObject()) continue;
                Patch p = parsePatch(el.getAsJsonObject(), knownIds);
                if (p != null) patches.add(p);
            }
        }

        return new Critique(satisfied, List.copyOf(issues), List.copyOf(patches));
    }

    private static Patch parsePatch(JsonObject obj, Set<String> knownIds) {
        String op = obj.has("op") ? obj.get("op").getAsString() : null;
        if (op == null || !VALID_OPS.contains(op)) {
            LOGGER.debug("VisualCriticAgent: dropping patch with unknown op '{}'", op);
            return null;
        }

        Patch p = new Patch();
        p.op = op;

        if (obj.has("id")) p.id = obj.get("id").getAsString();
        if (obj.has("field")) p.field = obj.get("field").getAsString();
        if (obj.has("value")) p.value = obj.get("value");
        if (obj.has("primitive") && obj.get("primitive").isJsonObject()) {
            p.primitive = obj.getAsJsonObject("primitive");
        }

        // Validate id reference for ops that require an existing primitive
        if ((op.equals("modify") || op.equals("remove") || op.equals("replace"))
                && p.id != null && !knownIds.contains(p.id)) {
            LOGGER.warn("VisualCriticAgent: dropping patch op='{}' — unknown id '{}' not in blueprint",
                    op, p.id);
            return null;
        }

        // Validate required fields per op
        if (op.equals("modify") && (p.id == null || p.field == null || p.value == null)) {
            LOGGER.warn("VisualCriticAgent: dropping incomplete modify patch: {}", obj);
            return null;
        }
        if (op.equals("add") && p.primitive == null) {
            LOGGER.warn("VisualCriticAgent: dropping add patch with no primitive: {}", obj);
            return null;
        }
        if ((op.equals("replace")) && (p.id == null || p.primitive == null)) {
            LOGGER.warn("VisualCriticAgent: dropping replace patch missing id or primitive: {}", obj);
            return null;
        }

        return p;
    }

    private static String preview(String s) {
        if (s == null) return "<null>";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
