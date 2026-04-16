package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.api.GeminiClient;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stage 2 of the build pipeline.
 *
 * Takes the structured {@link BuildSpec} from InterpretationAgent and decomposes
 * it into an ordered list of named components with spatial relationships. This
 * agent knows nothing about block coordinates — only spatial structure and build order.
 *
 * Output schema (JSON array):
 * [
 *   { "id": "comp_1", "name": "foundation", "description": "...", "build_after": [] },
 *   { "id": "comp_2", "name": "left_tower", "description": "...", "build_after": ["comp_1"] },
 *   ...
 * ]
 */
public final class PlanningAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.planning");
    private static final Gson GSON = new Gson();

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    static final String SYSTEM_PROMPT =
        "You are the Planning Agent for a Minecraft building assistant.\n" +
        "Your ONLY job is to decompose a building spec into the minimum set of named structural components.\n" +
        "You know nothing about block coordinates — only spatial structure and build order.\n" +
        "\n" +
        "Respond with ONLY a valid JSON array — no markdown fences, no prose, no explanation.\n" +
        "\n" +
        "Each element in the array must have this schema:\n" +
        "{\n" +
        "  \"id\": string,           // unique id like \"comp_1\", \"comp_2\", etc.\n" +
        "  \"name\": string,         // short name: \"foundation\", \"left_wall\", \"roof\", etc.\n" +
        "  \"description\": string,  // precise description a block-level generator can work from:\n" +
        "                           //   include footprint dimensions, height, materials, and any\n" +
        "                           //   architectural details (arches, crenellations, etc.)\n" +
        "  \"build_after\": [string] // ids of components that must be built before this one\n" +
        "}\n" +
        "\n" +
        "Rules:\n" +
        "- Decompose into the MINIMUM number of components needed. Typical builds have 3–8 components.\n" +
        "- Order them so dependencies are always built before dependents (build_after lists only earlier ids).\n" +
        "- The first component should always have build_after: [] (it's the foundation or base).\n" +
        "- Write descriptions that are precise enough for a block-level generator: include exact dimensions,\n" +
        "  materials from the provided list, and structural details.\n" +
        "- The total block count across ALL components must not exceed the maxBlocks limit given in context.\n" +
        "- Every component must fit within the bounding box dimensions given in context.\n" +
        "- Use materials ONLY from the provided materials list.\n" +
        "IMPORTANT: Respond with ONLY the JSON array. No other text whatsoever.";

    private PlanningAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the planning stage asynchronously.
     *
     * Reads {@code state.spec} and {@code state.buildSelection}. On success,
     * writes {@code state.componentPlan} with spatial layout assigned.
     *
     * @param state      shared build context (spec must already be set)
     * @param gemini     Gemini API client
     * @param onComplete called (on the server thread) once {@code state.componentPlan} is populated
     * @param onError    called (on the server thread) with a human-readable reason on failure
     */
    public static void run(BuildState state, GeminiClient gemini,
                           Runnable onComplete, Consumer<String> onError) {
        BuildSpec spec = state.spec;
        if (spec == null) {
            onError.accept("PlanningAgent: no BuildSpec available — InterpretationAgent must run first.");
            return;
        }

        BlockPos size = state.buildSelection != null && state.buildSelection.isComplete()
                ? state.buildSelection.getSize()
                : null;
        int w = size != null ? size.getX() : spec.width;
        int h = size != null ? size.getY() : spec.height;
        int d = size != null ? size.getZ() : spec.depth;
        int maxBlocks = w * h * d;

        String userPrompt = buildUserPrompt(spec, w, h, d, maxBlocks);

        gemini.complete(SYSTEM_PROMPT, userPrompt).whenComplete((responseText, ex) -> {
            if (ex != null) {
                LOGGER.error("PlanningAgent Gemini call failed", ex);
                onError.accept("PlanningAgent Gemini call failed: " + ex.getMessage());
                return;
            }
            try {
                List<ComponentPlan> components = parse(responseText);
                assignSpatialLayout(components, w, h, d);
                state.componentPlan = components;
                LOGGER.info("PlanningAgent produced {} components: {}", components.size(),
                        components.stream().map(c -> c.name).toList());
                onComplete.run();
            } catch (Exception e) {
                LOGGER.error("PlanningAgent failed to parse response: {}", responseText, e);
                onError.accept("PlanningAgent failed to parse plan: " + e.getMessage()
                        + " — raw: " + preview(responseText, 120));
            }
        });
    }

    // -------------------------------------------------------------------------
    // User prompt builder
    // -------------------------------------------------------------------------

    static String buildUserPrompt(BuildSpec spec, int width, int height, int depth, int maxBlocks) {
        String specJson = GSON.toJson(spec);
        return "Building spec:\n" + specJson + "\n\n" +
               "Bounding box: " + width + "×" + height + "×" + depth + " blocks\n" +
               "Max total blocks across all components: " + maxBlocks + "\n\n" +
               "Decompose this into an ordered list of named components. Return only the JSON array.";
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    static List<ComponentPlan> parse(String responseText) {
        String json = responseText.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }

        JsonArray arr;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                throw new RuntimeException("Expected JSON array, got: " + root.getClass().getSimpleName());
            }
            arr = root.getAsJsonArray();
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("JSON parse error: " + e.getMessage());
        }

        if (arr.size() == 0) {
            throw new RuntimeException("PlanningAgent returned an empty component list.");
        }

        List<ComponentPlan> components = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            ComponentPlan cp = new ComponentPlan();
            cp.id = requireString(obj, "id", i);
            cp.name = requireString(obj, "name", i);
            cp.description = requireString(obj, "description", i);

            cp.buildAfter = new ArrayList<>();
            if (obj.has("build_after") && obj.get("build_after").isJsonArray()) {
                for (JsonElement dep : obj.getAsJsonArray("build_after")) {
                    cp.buildAfter.add(dep.getAsString());
                }
            }

            cp.retryCount = 0;
            components.add(cp);
        }

        return components;
    }

    private static String requireString(JsonObject obj, String field, int index) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            throw new RuntimeException("Component[" + index + "] missing required field '" + field + "'");
        }
        return obj.get(field).getAsString();
    }

    // -------------------------------------------------------------------------
    // Spatial layout — assign origin and size to each component
    // -------------------------------------------------------------------------

    /**
     * Simple layout: each component gets the full bounding box as its available
     * space. The GenerationAgent generates coordinates relative to the component
     * origin, so all components share origin (0,0,0) by default — they overlay
     * within the same bounding box, which is correct for architectural components
     * that are spatially part of the same structure (foundation, walls, roof, etc.).
     *
     * Components that the LLM describes as occupying a sub-region will naturally
     * produce coordinates only within that sub-region.
     */
    static void assignSpatialLayout(List<ComponentPlan> components, int totalW, int totalH, int totalD) {
        for (ComponentPlan cp : components) {
            cp.originX = 0;
            cp.originY = 0;
            cp.originZ = 0;
            cp.sizeX = totalW;
            cp.sizeY = totalH;
            cp.sizeZ = totalD;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String preview(String text, int maxLen) {
        if (text == null) return "<null>";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
