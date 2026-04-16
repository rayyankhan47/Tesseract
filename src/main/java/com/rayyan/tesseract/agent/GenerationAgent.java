package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Stage 3 of the build pipeline.
 *
 * Generates block coordinates for one component at a time. For each component,
 * one focused LLM call uses the full context window to produce a flat JSON array
 * of block operations relative to the component's origin.
 *
 * Retry logic:
 *   - CriticAgent validates the generated ops before any blocks are placed.
 *   - On failure, retries up to MAX_RETRIES times, appending the critic's failure
 *     reason to the next prompt so the model can self-correct.
 *   - After MAX_RETRIES consecutive failures the component is skipped
 *     (added to state.failedComponentIds) and the pipeline advances.
 */
public final class GenerationAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.generation");
    static final int MAX_RETRIES = 3;

    // -------------------------------------------------------------------------
    // Prompts and output schema
    // -------------------------------------------------------------------------

    /**
     * Output schema — a flat JSON array of block ops with coordinates relative
     * to the component's origin (NOT world coordinates):
     * [
     *   { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone_bricks" },
     *   { "x": 1, "y": 0, "z": 0, "block": "minecraft:stone_bricks" }
     * ]
     */
    static final String SYSTEM_PROMPT =
        "You are the Generation Agent for a Minecraft building assistant.\n" +
        "Your ONLY job is to generate the exact block placements for ONE named structural component.\n" +
        "\n" +
        "Respond with ONLY a valid JSON array — no markdown fences, no prose, no explanation.\n" +
        "\n" +
        "Each element in the array must have exactly this schema:\n" +
        "{ \"x\": integer, \"y\": integer, \"z\": integer, \"block\": string }\n" +
        "\n" +
        "Block state properties:\n" +
        "- You MAY append block state properties in bracket notation to orient blocks correctly:\n" +
        "    \"minecraft:oak_stairs[facing=north,half=bottom]\"\n" +
        "    \"minecraft:oak_log[axis=y]\"\n" +
        "    \"minecraft:oak_trapdoor[facing=north,half=bottom,open=false]\"\n" +
        "- Use facing=north/south/east/west for stairs, doors, trapdoors, and other directional blocks.\n" +
        "- Use axis=x/y/z for logs and pillars.\n" +
        "- Always use architecturally correct orientations (stairs face inward, ridge logs run lengthwise, etc.).\n" +
        "\n" +
        "Rules:\n" +
        "- Coordinates are relative to THIS component's origin — start from (0, 0, 0).\n" +
        "- Use ONLY block IDs from the provided materials list (full 'minecraft:' form).\n" +
        "- CRITICAL: Your y coordinates MUST be between 0 and the max_y given in context (inclusive).\n" +
        "  Blocks placed outside this range will be discarded — they will NOT appear in the build.\n" +
        "- Build structurally — ensure every non-ground block has support below it.\n" +
        "- Do NOT use 'minecraft:air' for intentional gaps — just omit those positions.\n" +
        "- Stay within the max block budget given in context.\n" +
        "IMPORTANT: Respond with ONLY the JSON array. No other text whatsoever.";

    static String buildUserPrompt(ComponentPlan component, BuildSpec spec,
                                   List<ComponentPlan> allComponents,
                                   String priorFailureReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component to generate:\n");
        sb.append("  Name: ").append(component.name).append("\n");
        sb.append("  Description: ").append(component.description).append("\n");
        sb.append("  Footprint: ").append(component.sizeX).append("×").append(component.sizeZ)
          .append(" blocks (x: 0–").append(component.sizeX - 1)
          .append(", z: 0–").append(component.sizeZ - 1).append(")\n");
        sb.append("  Height: ").append(component.sizeY).append(" blocks\n");
        sb.append("  CRITICAL — y range: 0 to ").append(component.sizeY - 1)
          .append(" (y=0 maps to world y=").append(component.originY)
          .append(", y=").append(component.sizeY - 1)
          .append(" maps to world y=").append(component.originY + component.sizeY - 1)
          .append("). Blocks outside 0–").append(component.sizeY - 1).append(" are discarded.\n");

        sb.append("\nAvailable block IDs (use ONLY these):\n");
        List<String> palette = buildFullPaletteFromSpec(spec);
        for (String id : palette) {
            sb.append("  ").append(id).append("\n");
        }

        int maxBudget = component.sizeX * component.sizeY * component.sizeZ;
        sb.append("\nMax blocks for this component: ").append(maxBudget)
          .append(" (").append(component.sizeX).append("×").append(component.sizeY)
          .append("×").append(component.sizeZ).append(" volume — don't fill it solid, leave room for architecture)");

        if (allComponents != null && allComponents.size() > 1) {
            sb.append("\n\nOther components in this build (for spatial context — do not place blocks meant for them):\n");
            for (ComponentPlan other : allComponents) {
                if (!other.id.equals(component.id)) {
                    sb.append("  ").append(other.name).append(": ").append(other.description).append("\n");
                }
            }
        }

        if (priorFailureReason != null) {
            sb.append("\n\nPrevious attempt was rejected — fix this issue:\n  ").append(priorFailureReason);
        }

        sb.append("\n\nReturn only the JSON array of block placements.");
        return sb.toString();
    }

    private static List<String> buildFullPaletteFromSpec(BuildSpec spec) {
        return buildFocusedPalette(spec);
    }

    private GenerationAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates and validates blocks for the component at {@code state.currentComponentIndex}.
     *
     * @param state             shared build context
     * @param gemini            Gemini API client
     * @param retryAttempt      0-based attempt count for the current component
     * @param priorFailureReason critic failure reason from the previous attempt, or null
     * @param onCriticPass      called (already on server thread) when CriticAgent approves —
     *                          approved ops are stored on {@code component.pendingOps}
     * @param onCriticFail      called (already on server thread) with (reason, shouldRetry);
     *                          {@code shouldRetry=false} means max retries reached: skip component
     */
    public static void runComponent(BuildState state,
                                    GeminiClient gemini,
                                    int retryAttempt,
                                    String priorFailureReason,
                                    Runnable onCriticPass,
                                    BiConsumer<String, Boolean> onCriticFail) {
        ComponentPlan component = state.componentPlan.get(state.currentComponentIndex);

        // Max retries exceeded — skip this component.
        if (retryAttempt >= MAX_RETRIES) {
            String reason = priorFailureReason != null ? priorFailureReason : "max retries exceeded";
            LOGGER.warn("GenerationAgent: component '{}' failed after {} attempts. Skipping. Reason: {}",
                    component.name, MAX_RETRIES, reason);
            state.failedComponentIds.add(component.id);
            onCriticFail.accept(reason, false);
            return;
        }

        String userPrompt = buildUserPrompt(component, state.spec, state.componentPlan, priorFailureReason);

        LOGGER.info("GenerationAgent: calling Gemini for component '{}' (attempt {}/{}).",
                component.name, retryAttempt + 1, MAX_RETRIES);

        gemini.complete(SYSTEM_PROMPT, userPrompt).whenComplete((responseText, ex) -> {
            if (ex != null) {
                LOGGER.error("GenerationAgent: Gemini call failed for '{}'", component.name, ex);
                onCriticFail.accept("Gemini call failed: " + ex.getMessage(), retryAttempt + 1 < MAX_RETRIES);
                return;
            }
            List<BlockOp> ops;
            try {
                ops = parseOps(responseText);
            } catch (Exception e) {
                LOGGER.error("GenerationAgent: JSON parse failed for '{}': {}", component.name, responseText, e);
                onCriticFail.accept("Malformed JSON from LLM: " + e.getMessage(), retryAttempt + 1 < MAX_RETRIES);
                return;
            }
            handleCriticResult(state, gemini, component, ops, retryAttempt, onCriticPass, onCriticFail);
        });
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    static List<BlockOp> parseOps(String responseText) {
        String json = responseText.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new RuntimeException("Expected JSON array, got: " + root.getClass().getSimpleName());
        }
        com.google.gson.JsonArray arr = root.getAsJsonArray();
        List<BlockOp> ops = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement el : arr) {
            com.google.gson.JsonObject obj = el.getAsJsonObject();
            BlockOp op = new BlockOp();
            op.x = obj.get("x").getAsInt();
            op.y = obj.get("y").getAsInt();
            op.z = obj.get("z").getAsInt();
            op.block = obj.get("block").getAsString();
            ops.add(op);
        }
        return ops;
    }

    // -------------------------------------------------------------------------
    // Critic integration and retry dispatch
    // -------------------------------------------------------------------------

    static void handleCriticResult(BuildState state,
                                    GeminiClient gemini,
                                    ComponentPlan component,
                                    List<BlockOp> ops,
                                    int retryAttempt,
                                    Runnable onCriticPass,
                                    BiConsumer<String, Boolean> onCriticFail) {
        List<String> palette = buildFullPalette(state);
        int totalMaxBlocks = computeMaxBlocks(state, component);
        int compCount = state.componentPlan != null ? state.componentPlan.size() : 1;
        CriticResult result = CriticAgent.validate(ops, component, palette, totalMaxBlocks, compCount);

        if (result.passed()) {
            LOGGER.info("GenerationAgent: component '{}' passed critic ({} blocks).",
                    component.name, ops.size());
            component.pendingOps = ops;
            component.retryCount = retryAttempt;
            component.lastFailureReason = null;
            onCriticPass.run();
        } else {
            int nextAttempt = retryAttempt + 1;
            boolean shouldRetry = nextAttempt < MAX_RETRIES;
            LOGGER.warn("GenerationAgent: component '{}' failed critic (attempt {}/{}): {}",
                    component.name, retryAttempt + 1, MAX_RETRIES, result.failureReason());
            component.retryCount = nextAttempt;
            component.lastFailureReason = result.failureReason();
            onCriticFail.accept(result.failureReason(), shouldRetry);
        }
    }

    // -------------------------------------------------------------------------
    // Palette helpers
    // -------------------------------------------------------------------------

    private static int computeMaxBlocks(BuildState state, ComponentPlan component) {
        if (state.buildSelection != null && state.buildSelection.isComplete()) {
            net.minecraft.util.math.BlockPos s = state.buildSelection.getSize();
            if (s != null) return s.getX() * s.getY() * s.getZ();
        }
        return component.sizeX * component.sizeY * component.sizeZ;
    }

    static List<String> buildFullPalette(BuildState state) {
        return buildFocusedPalette(state.spec);
    }

    /**
     * Builds a focused palette of ~50–80 blocks:
     *   1. A small universal set (glass, torches, stairs/slabs for stone/oak, etc.)
     *   2. Every material from spec.materials, expanded to "minecraft:" form.
     *   3. Automatic _slab, _stairs, _wall variants for each spec material (where the
     *      pattern is valid — the LLM will ignore unknown IDs).
     *   4. Wood-family extras (_fence, _door, _trapdoor) for plank/log materials.
     *
     * This replaces the old 536-block default list which bloated every prompt
     * and was a primary driver of Gemini 503 errors on large builds.
     */
    public static List<String> buildFocusedPalette(BuildSpec spec) {
        Set<String> palette = new LinkedHashSet<>();

        // Universal set — always useful regardless of build style.
        palette.addAll(List.of(
            "minecraft:stone", "minecraft:stone_slab", "minecraft:stone_stairs",
            "minecraft:cobblestone", "minecraft:cobblestone_slab", "minecraft:cobblestone_stairs", "minecraft:cobblestone_wall",
            "minecraft:stone_bricks", "minecraft:stone_brick_slab", "minecraft:stone_brick_stairs", "minecraft:stone_brick_wall",
            "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_stairs",
            "minecraft:oak_log", "minecraft:oak_fence", "minecraft:oak_door", "minecraft:oak_trapdoor",
            "minecraft:glass", "minecraft:glass_pane",
            "minecraft:torch", "minecraft:lantern",
            "minecraft:iron_bars", "minecraft:ladder",
            "minecraft:dirt", "minecraft:gravel"
        ));

        if (spec != null && spec.materials != null) {
            for (String mat : spec.materials) {
                String id = mat.contains(":") ? mat : "minecraft:" + mat;
                String base = id.substring("minecraft:".length());
                palette.add(id);

                // Slab and stair variants work for most solid block types.
                palette.add("minecraft:" + base + "_slab");
                palette.add("minecraft:" + base + "_stairs");

                // Wall variant for stone-like materials.
                if (base.endsWith("_bricks") || base.endsWith("stone") || base.endsWith("cobblestone")
                        || base.endsWith("deepslate") || base.endsWith("sandstone")
                        || base.endsWith("basalt") || base.endsWith("blackstone")) {
                    palette.add("minecraft:" + base + "_wall");
                }

                // Wood-family extras.
                if (base.endsWith("_planks")) {
                    String wood = base.replace("_planks", "");
                    palette.add("minecraft:" + wood + "_slab");
                    palette.add("minecraft:" + wood + "_stairs");
                    palette.add("minecraft:" + wood + "_fence");
                    palette.add("minecraft:" + wood + "_fence_gate");
                    palette.add("minecraft:" + wood + "_door");
                    palette.add("minecraft:" + wood + "_trapdoor");
                    palette.add("minecraft:" + wood + "_log");
                    palette.add("minecraft:" + wood + "_wood");
                }
            }
        }

        return List.copyOf(palette);
    }

    /** @deprecated kept only to avoid breaking any external callers; use {@link #buildFocusedPalette} */
    @Deprecated
    static List<String> defaultPalette() {
        return buildFocusedPalette(null);
    }

}
