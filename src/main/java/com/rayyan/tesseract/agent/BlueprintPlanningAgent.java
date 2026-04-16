package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.blueprint.BlueprintCompileException;
import com.rayyan.tesseract.blueprint.BlueprintCompiler;
import com.rayyan.tesseract.blueprint.BlueprintParseException;
import com.rayyan.tesseract.blueprint.BlueprintParser;
import com.rayyan.tesseract.blueprint.CompiledBlueprint;
import com.rayyan.tesseract.blueprint.PaletteUtils;
import com.google.gson.Gson;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stage 2 of the Refactor-2 pipeline.
 *
 * Replaces {@link PlanningAgent}. Instead of emitting a list of component
 * descriptions for a downstream generator to turn into coordinates, it emits
 * a complete {@link Blueprint} that the deterministic compiler turns into
 * block ops directly.
 *
 * On success: sets {@code state.blueprint} and {@code state.compiledBlueprint}
 * (pre-flight compile to catch obvious LLM mistakes early).
 */
public final class BlueprintPlanningAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.blueprint.planner");
    private static final Gson GSON = new Gson();

    private BlueprintPlanningAgent() {}

    // -------------------------------------------------------------------------
    // System prompt — full DSL schema + two worked examples as few-shots
    // -------------------------------------------------------------------------

    static final String SYSTEM_PROMPT =
        "You are the Blueprint Planning Agent for a Minecraft building assistant.\n" +
        "Your ONLY job is to emit a single Blueprint JSON object describing how to build the " +
        "requested structure.\n\n" +

        "DO NOT emit block coordinates (x, y, z integers).\n" +
        "DO NOT emit {\"x\":…, \"y\":…, \"z\":…, \"block\":…} objects.\n" +
        "Emit ONLY the Blueprint JSON object described below.\n\n" +

        "=== BLUEPRINT SCHEMA ===\n" +
        "{\n" +
        "  \"name\": \"string — short build label\",\n" +
        "  \"bounds\": { \"sizeX\": int, \"sizeY\": int, \"sizeZ\": int },\n" +
        "  \"primitives\": [ <Primitive>, ... ]\n" +
        "}\n\n" +

        "Every primitive has: \"id\" (unique), \"type\" (one of the 10 types), " +
        "\"on\" (parent id, nullable) plus type-specific params.\n\n" +

        "Reference semantics (\"on\"):\n" +
        "- When \"on\": \"parent_id\" is set, this primitive's y-origin = parent's top face,\n" +
        "  and its x/z footprint is inherited from the parent (unless you override with " +
        "explicit params).\n" +
        "- The first primitive MUST have no \"on\" reference.\n" +
        "- Every \"on\" must reference an EARLIER primitive in the array.\n\n" +

        "=== 10 PRIMITIVE TYPES ===\n\n" +

        "platform  — filled rectangular slab\n" +
        "  Required: origin [x,y,z], size [sx,sy,sz], material\n" +
        "  Optional: edge_material (outermost ring uses this instead)\n\n" +

        "walls  — hollow perimeter box (no interior fill)\n" +
        "  Required: on, height, material\n" +
        "  Optional: corner_material, openings [{face,u_offset,v_offset?,width,height,type}]\n" +
        "  Opening: face=north|south|east|west; type=door|window|gap\n" +
        "  u_offset = blocks from left edge of that face (0-based)\n" +
        "  v_offset = blocks from bottom of wall (0 = ground level of wall)\n\n" +

        "wall_segment  — single flat wall from/to two points\n" +
        "  Required: from [x,y,z], to [x,y,z], height, material\n\n" +

        "gable_roof  — triangular peaked roof with stair slopes\n" +
        "  Required: on, ridge_axis (\"x\" or \"z\"), overhang, stairs_material, slab_material\n" +
        "  Optional: ridge_material (defaults to slab_material)\n\n" +

        "hip_roof  — pyramid roof converging to a ridge/apex\n" +
        "  Required: on, stairs_material, slab_material\n" +
        "  Optional: apex_material\n\n" +

        "flat_roof  — single flat layer with optional crenellations\n" +
        "  Required: on, material\n" +
        "  Optional: battlements (boolean), battlement_material\n\n" +

        "column  — vertical pillar\n" +
        "  Required: origin [x,y,z], height, material\n" +
        "  Optional: cap_material, base_material\n\n" +

        "arch  — semicircle archway between two points at the same y\n" +
        "  Required: from [x,y,z], to [x,y,z], height, material\n\n" +

        "staircase  — steps connecting two elevations\n" +
        "  Required: from [x,y,z], to [x,y,z], width, material (use a stairs block ID)\n\n" +

        "frame  — hollow rectangular box (outer shell only)\n" +
        "  Required: origin [x,y,z], size [sx,sy,sz], material\n\n" +

        "=== EXAMPLE 1: cozy_oak_cabin ===\n" +
        "{\n" +
        "  \"name\": \"cozy_oak_cabin\",\n" +
        "  \"bounds\": { \"sizeX\": 12, \"sizeY\": 12, \"sizeZ\": 10 },\n" +
        "  \"primitives\": [\n" +
        "    { \"id\": \"foundation\", \"type\": \"platform\",\n" +
        "      \"origin\": [0,0,0], \"size\": [12,1,10],\n" +
        "      \"material\": \"minecraft:stone_bricks\",\n" +
        "      \"edge_material\": \"minecraft:cobblestone\" },\n" +
        "    { \"id\": \"walls\", \"type\": \"walls\", \"on\": \"foundation\",\n" +
        "      \"height\": 6, \"material\": \"minecraft:oak_planks\",\n" +
        "      \"corner_material\": \"minecraft:oak_log\",\n" +
        "      \"openings\": [\n" +
        "        { \"face\": \"south\", \"u_offset\": 4, \"v_offset\": 0,\n" +
        "          \"width\": 2, \"height\": 3, \"type\": \"door\" },\n" +
        "        { \"face\": \"east\", \"u_offset\": 2, \"v_offset\": 2,\n" +
        "          \"width\": 2, \"height\": 2, \"type\": \"window\" },\n" +
        "        { \"face\": \"west\", \"u_offset\": 2, \"v_offset\": 2,\n" +
        "          \"width\": 2, \"height\": 2, \"type\": \"window\" }\n" +
        "      ] },\n" +
        "    { \"id\": \"roof\", \"type\": \"gable_roof\", \"on\": \"walls\",\n" +
        "      \"ridge_axis\": \"z\", \"overhang\": 1,\n" +
        "      \"stairs_material\": \"minecraft:oak_stairs\",\n" +
        "      \"slab_material\": \"minecraft:oak_slab\",\n" +
        "      \"ridge_material\": \"minecraft:oak_log\" }\n" +
        "  ]\n" +
        "}\n\n" +

        "=== EXAMPLE 2: stone_watchtower ===\n" +
        "{\n" +
        "  \"name\": \"stone_watchtower\",\n" +
        "  \"bounds\": { \"sizeX\": 8, \"sizeY\": 16, \"sizeZ\": 8 },\n" +
        "  \"primitives\": [\n" +
        "    { \"id\": \"foundation\", \"type\": \"platform\",\n" +
        "      \"origin\": [0,0,0], \"size\": [8,1,8],\n" +
        "      \"material\": \"minecraft:cobblestone\",\n" +
        "      \"edge_material\": \"minecraft:stone_brick_wall\" },\n" +
        "    { \"id\": \"walls\", \"type\": \"walls\", \"on\": \"foundation\",\n" +
        "      \"height\": 12, \"material\": \"minecraft:stone_bricks\",\n" +
        "      \"corner_material\": \"minecraft:stone_brick_wall\",\n" +
        "      \"openings\": [\n" +
        "        { \"face\": \"south\", \"u_offset\": 2, \"v_offset\": 0,\n" +
        "          \"width\": 2, \"height\": 3, \"type\": \"door\" },\n" +
        "        { \"face\": \"north\", \"u_offset\": 3, \"v_offset\": 5,\n" +
        "          \"width\": 2, \"height\": 2, \"type\": \"window\" }\n" +
        "      ] },\n" +
        "    { \"id\": \"col_sw\", \"type\": \"column\",\n" +
        "      \"origin\": [0,1,0], \"height\": 12, \"material\": \"minecraft:stone_brick_wall\" },\n" +
        "    { \"id\": \"col_se\", \"type\": \"column\",\n" +
        "      \"origin\": [7,1,0], \"height\": 12, \"material\": \"minecraft:stone_brick_wall\" },\n" +
        "    { \"id\": \"col_nw\", \"type\": \"column\",\n" +
        "      \"origin\": [0,1,7], \"height\": 12, \"material\": \"minecraft:stone_brick_wall\" },\n" +
        "    { \"id\": \"col_ne\", \"type\": \"column\",\n" +
        "      \"origin\": [7,1,7], \"height\": 12, \"material\": \"minecraft:stone_brick_wall\" },\n" +
        "    { \"id\": \"parapet\", \"type\": \"flat_roof\", \"on\": \"walls\",\n" +
        "      \"material\": \"minecraft:stone_bricks\",\n" +
        "      \"battlements\": true,\n" +
        "      \"battlement_material\": \"minecraft:stone_brick_wall\" }\n" +
        "  ]\n" +
        "}\n\n" +

        "=== RULES ===\n" +
        "1. Use ONLY block IDs from the palette provided in the user prompt.\n" +
        "2. All primitive coordinates must be inside the bounds given in the user prompt.\n" +
        "3. No negative coordinates.\n" +
        "4. The compositional order must be: foundation first → walls on foundation → " +
           "roof on walls → details last.\n" +
        "5. Column origins are absolute (blueprint-local), not relative to a parent.\n" +
        "6. stairs_material must be a stair block (ends in _stairs), e.g. \"minecraft:oak_stairs\".\n" +
        "7. slab_material must be a slab block (ends in _slab), e.g. \"minecraft:oak_slab\".\n" +
        "8. Keep the blueprint compact: 3–8 primitives is ideal.\n\n" +
        "IMPORTANT: Respond with ONLY the Blueprint JSON object. No markdown fences, " +
        "no prose, no explanation.";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the blueprint planning stage asynchronously.
     *
     * @param state      shared build context (spec must already be set)
     * @param gemini     Gemini API client
     * @param onComplete called on server thread once state.blueprint and
     *                   state.compiledBlueprint are populated
     * @param onError    called on server thread with a human-readable reason on failure
     */
    public static void run(BuildState state,
                           GeminiClient gemini,
                           Runnable onComplete,
                           Consumer<String> onError) {
        if (state.spec == null) {
            onError.accept("BlueprintPlanningAgent: no BuildSpec — InterpretationAgent must run first.");
            return;
        }

        String userPrompt = buildUserPrompt(state);
        LOGGER.info("BlueprintPlanningAgent: requesting blueprint for '{}'", state.originalPrompt);

        gemini.complete(SYSTEM_PROMPT, userPrompt).whenComplete((raw, ex) -> {
            if (ex != null) {
                LOGGER.error("BlueprintPlanningAgent: Gemini call failed", ex);
                onError.accept("BlueprintPlanningAgent: Gemini call failed: " + ex.getMessage());
                return;
            }
            try {
                Blueprint bp = BlueprintParser.parse(raw);
                CompiledBlueprint cb = BlueprintCompiler.compile(bp);

                state.blueprint         = bp;
                state.compiledBlueprint = cb;

                String primList = bp.primitives.stream()
                        .map(p -> p.type + "(" + p.id + ")")
                        .reduce((a, b) -> a + " → " + b).orElse("(empty)");
                LOGGER.info("BlueprintPlanningAgent: blueprint '{}' compiled to {} ops. Primitives: {}",
                        bp.name, cb.ops().size(), primList);
                onComplete.run();

            } catch (BlueprintParseException e) {
                LOGGER.error("BlueprintPlanningAgent: parse failed. Raw: {}", preview(raw), e);
                onError.accept("BlueprintPlanningAgent: parse failed — " + e.getMessage()
                        + " | raw: " + preview(raw));
            } catch (BlueprintCompileException e) {
                LOGGER.error("BlueprintPlanningAgent: pre-flight compile failed", e);
                onError.accept("BlueprintPlanningAgent: blueprint failed to compile — " + e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // User prompt assembly
    // -------------------------------------------------------------------------

    static String buildUserPrompt(BuildState state) {
        BuildSpec spec = state.spec;

        // Resolve bounding box from buildSelection or fall back to spec dimensions
        int w = spec.width, h = spec.height, d = spec.depth;
        if (state.buildSelection != null && state.buildSelection.isComplete()) {
            BlockPos size = state.buildSelection.getSize();
            if (size != null) { w = size.getX(); h = size.getY(); d = size.getZ(); }
        }

        List<String> palette = PaletteUtils.buildFocusedPalette(spec);
        String specJson = GSON.toJson(spec);

        StringBuilder sb = new StringBuilder();
        sb.append("Build prompt: \"").append(state.originalPrompt).append("\"\n\n");
        sb.append("Interpreted spec:\n").append(specJson).append("\n\n");
        sb.append("Blueprint bounds: sizeX=").append(w)
          .append(", sizeY=").append(h)
          .append(", sizeZ=").append(d).append("\n");
        sb.append("(All primitive coordinates MUST be inside these bounds.)\n\n");
        sb.append("Allowed block IDs (use ONLY these):\n");
        for (String id : palette) {
            sb.append("  ").append(id).append("\n");
        }
        sb.append("\nReturn ONLY the Blueprint JSON object.");
        return sb.toString();
    }

    private static String preview(String text) {
        if (text == null) return "<null>";
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }
}
