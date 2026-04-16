package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.api.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stage 1 of the build pipeline.
 *
 * Interprets the player's natural-language prompt (and optional reference image)
 * into a structured {@link BuildSpec} that downstream agents can reason about.
 *
 * This agent's only job is to understand creative intent. It knows nothing
 * about block coordinates or component layout.
 *
 * Runs asynchronously on a CompletableFuture thread. All callbacks passed in by
 * the Orchestrator are already wrapped in {@code player.getServer().execute()},
 * so they execute on the Minecraft server thread when invoked.
 */
public final class InterpretationAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.interpretation");

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT_BASE =
        "You are the Interpretation Agent for a Minecraft building assistant.\n" +
        "Your ONLY job is to parse the user's building prompt into a structured JSON spec.\n" +
        "You know nothing about block coordinates — only architectural intent.\n" +
        "\n" +
        "Respond with ONLY a valid JSON object — no markdown fences, no prose, no explanation.\n" +
        "\n" +
        "Required JSON schema:\n" +
        "{\n" +
        "  \"style\": string,        // architectural style: e.g. \"gothic\", \"medieval\", \"modern\", \"fantasy\", \"rustic\"\n" +
        "  \"type\": string,         // structure type: e.g. \"castle\", \"house\", \"tower\", \"bridge\", \"gate\", \"temple\"\n" +
        "  \"width\": integer,       // approximate X footprint in blocks (1–64)\n" +
        "  \"height\": integer,      // approximate Y height in blocks (1–64)\n" +
        "  \"depth\": integer,       // approximate Z footprint in blocks (1–64)\n" +
        "  \"materials\": [string],  // Minecraft block name fragments, e.g. [\"stone_bricks\", \"oak_planks\", \"glass_pane\"]\n" +
        "  \"features\": [string]    // architectural features, e.g. [\"arches\", \"crenellations\", \"windows\", \"pillars\"]\n" +
        "}\n" +
        "\n" +
        "Rules:\n" +
        "- Choose sensible defaults for any detail not mentioned in the prompt.\n" +
        "- Scale dimensions to feel proportional in Minecraft (a house is ~10 wide, a castle 30-50 wide).\n" +
        "- List only block name fragments (no 'minecraft:' prefix, no full IDs).\n" +
        "- Be specific about features — list individual elements, not vague descriptors.\n" +
        "IMPORTANT: Respond with ONLY the JSON object. No other text whatsoever.";

    private static final String IMAGE_ADDENDUM =
        "\n\nA reference image is attached. Use it to inform the style, materials, and features fields. " +
        "Let the text prompt override any explicit constraints from the image.";

    private InterpretationAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the interpretation stage asynchronously.
     *
     * Reads {@code state.originalPrompt}, {@code state.referenceImageBytes}, and
     * {@code state.referenceImageMimeType}. On success, writes {@code state.spec}.
     *
     * @param state      shared build context
     * @param gemini     Gemini API client
     * @param onComplete called (on the server thread) once {@code state.spec} is populated
     * @param onError    called (on the server thread) with a human-readable reason on failure
     */
    public static void run(BuildState state,
                           GeminiClient gemini,
                           Runnable onComplete,
                           Consumer<String> onError) {
        boolean hasImage = state.referenceImageBytes != null && state.referenceImageMimeType != null;
        String systemPrompt = hasImage ? SYSTEM_PROMPT_BASE + IMAGE_ADDENDUM : SYSTEM_PROMPT_BASE;
        String userPrompt = "Build prompt: \"" + state.originalPrompt + "\"\n\nRespond with the JSON spec.";

        var future = hasImage
            ? gemini.complete(systemPrompt, userPrompt, state.referenceImageBytes, state.referenceImageMimeType)
            : gemini.complete(systemPrompt, userPrompt);

        future.whenComplete((responseText, ex) -> {
            if (ex != null) {
                LOGGER.error("InterpretationAgent Gemini call failed", ex);
                onError.accept("InterpretationAgent Gemini call failed: " + ex.getMessage());
                return;
            }
            try {
                BuildSpec spec = parse(responseText);
                state.spec = spec;
                LOGGER.info("InterpretationAgent parsed spec: {} {} {}×{}×{} materials={} features={}",
                        spec.style, spec.type, spec.width, spec.height, spec.depth,
                        spec.materials, spec.features);
                onComplete.run();
            } catch (Exception e) {
                LOGGER.error("InterpretationAgent failed to parse response: {}", responseText, e);
                onError.accept("InterpretationAgent failed to parse spec: " + e.getMessage()
                        + " — raw: " + preview(responseText, 120));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static BuildSpec parse(String responseText) {
        // Strip markdown code fences if the model disobeyed instructions
        String json = responseText.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }

        BuildSpec spec;
        try {
            spec = new Gson().fromJson(json, BuildSpec.class);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("JSON parse error: " + e.getMessage() + " — raw: " + preview(json, 200));
        }

        if (spec == null) {
            throw new RuntimeException("Gson returned null for response: " + preview(json, 200));
        }

        // Validate and fill defaults for required fields
        if (spec.type == null || spec.type.isBlank()) {
            throw new RuntimeException("Missing required field 'type' in spec: " + preview(json, 200));
        }
        if (spec.style == null || spec.style.isBlank())  spec.style = "minecraft";
        if (spec.width  <= 0)                             spec.width  = 8;
        if (spec.height <= 0)                             spec.height = 8;
        if (spec.depth  <= 0)                             spec.depth  = 8;
        if (spec.materials == null)                       spec.materials = List.of("stone_bricks");
        if (spec.features  == null)                       spec.features  = List.of();

        spec.rawJson = json;
        return spec;
    }

    private static String preview(String text, int maxLen) {
        if (text == null) return "<null>";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
