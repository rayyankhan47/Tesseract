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
        "You know nothing about block coordinates — only spatial structure and vertical build order.\n" +
        "\n" +
        "Respond with ONLY a valid JSON array — no markdown fences, no prose, no explanation.\n" +
        "\n" +
        "Each element in the array must have this schema:\n" +
        "{\n" +
        "  \"id\": string,           // unique id like \"comp_1\", \"comp_2\", etc.\n" +
        "  \"name\": string,         // short name: \"foundation\", \"walls\", \"roof\", etc.\n" +
        "  \"description\": string,  // precise description a block-level generator can work from:\n" +
        "                           //   include footprint dimensions, height in blocks, materials, and\n" +
        "                           //   architectural details (arches, overhangs, crenellations, etc.)\n" +
        "  \"build_after\": [string],// ids of components that must be built before this one\n" +
        "  \"y_min\": integer,       // lowest y row this component occupies (0 = ground level of the build)\n" +
        "  \"y_max\": integer        // highest y row this component occupies (inclusive, 0-based)\n" +
        "}\n" +
        "\n" +
        "Rules:\n" +
        "- Decompose into the MINIMUM number of components needed. Typical builds have 3–8 components.\n" +
        "- Order them bottom-to-top: foundation first, roof last. Dependencies always come before dependents.\n" +
        "- The first component should always have build_after: [] (it's the foundation or base).\n" +
        "- CRITICAL — vertical layout: y=0 is the bottom row of the build. The total build height is\n" +
        "  given in context. Assign non-overlapping y_min/y_max ranges to every component.\n" +
        "  Example for a 10-block-tall cabin: foundation y_min=0 y_max=0, walls y_min=1 y_max=7,\n" +
        "  roof y_min=8 y_max=9. The Generation Agent will be told 'your y coordinates must be 0 to\n" +
        "  (y_max - y_min)' — so y=0 inside a component always maps to y_min in the full build.\n" +
        "  Components MUST NOT have overlapping y ranges.\n" +
        "- Write descriptions that are precise: include exact footprint dimensions, height in blocks,\n" +
        "  materials, and structural details.\n" +
        "- The total block count across ALL components must not exceed the maxBlocks limit given in context.\n" +
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
               "Bounding box: " + width + "×" + height + "×" + depth + " blocks (width × height × depth)\n" +
               "Total build height: " + height + " blocks (y=0 is ground, y=" + (height - 1) + " is the top)\n" +
               "Max total blocks across all components: " + maxBlocks + "\n\n" +
               "Assign non-overlapping y_min/y_max ranges to each component so they stack correctly.\n" +
               "The tallest component's y_max must be exactly " + (height - 1) + ".\n\n" +
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

            // Read explicit vertical bounds — used by assignSpatialLayout.
            // Defaults (sizeY=0) signal that the full-height fallback should apply.
            if (obj.has("y_min") && !obj.get("y_min").isJsonNull()) {
                cp.originY = Math.max(0, obj.get("y_min").getAsInt());
            }
            if (obj.has("y_max") && !obj.get("y_max").isJsonNull()) {
                int yMax = obj.get("y_max").getAsInt();
                cp.sizeY = Math.max(1, yMax - cp.originY + 1);
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
     * Assigns spatial layout to each component.
     *
     * X/Z: every component gets the full horizontal footprint (origin 0, size = totalW/totalD).
     * Y: taken from the y_min/y_max fields the LLM returned (stored in cp.originY / cp.sizeY
     *    during parse). Falls back to the full height if the LLM omitted those fields.
     * All values are clamped to stay within [0, totalH).
     */
    static void assignSpatialLayout(List<ComponentPlan> components, int totalW, int totalH, int totalD) {
        for (ComponentPlan cp : components) {
            // Horizontal footprint — always the full bounding box.
            cp.originX = 0;
            cp.originZ = 0;
            cp.sizeX = totalW;
            cp.sizeZ = totalD;

            // Vertical layout — use LLM-supplied y_min/y_max if present (sizeY > 0),
            // otherwise fall back to the full height range.
            if (cp.sizeY <= 0) {
                cp.originY = 0;
                cp.sizeY = totalH;
            }
            // Clamp to valid range so a bad LLM response can't cause world-corrupting offsets.
            cp.originY = Math.max(0, Math.min(cp.originY, totalH - 1));
            cp.sizeY   = Math.max(1, Math.min(cp.sizeY,   totalH - cp.originY));
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
