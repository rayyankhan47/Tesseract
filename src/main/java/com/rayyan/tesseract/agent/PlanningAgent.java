package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.rayyan.tesseract.api.GeminiClient;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
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

    // -------------------------------------------------------------------------
    // User prompt template
    // -------------------------------------------------------------------------

    static String buildUserPrompt(BuildSpec spec, int width, int height, int depth, int maxBlocks) {
        String specJson = GSON.toJson(spec);
        return "Building spec:\n" + specJson + "\n\n" +
               "Bounding box: " + width + "×" + height + "×" + depth + " blocks\n" +
               "Max total blocks across all components: " + maxBlocks + "\n\n" +
               "Decompose this into an ordered list of named components. Return only the JSON array.";
    }

    private PlanningAgent() {}

    // -------------------------------------------------------------------------
    // Public API (implementation in Step 5.2)
    // -------------------------------------------------------------------------

    public static void run(BuildState state, GeminiClient gemini,
                           Runnable onComplete, Consumer<String> onError) {
        // TODO(Step 5.2): full implementation
        onError.accept("PlanningAgent.run() not yet implemented.");
    }
}
