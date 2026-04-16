package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
        "Rules:\n" +
        "- Coordinates are relative to THIS component's origin — start from (0, 0, 0).\n" +
        "- Use ONLY block IDs from the provided materials list (full 'minecraft:' form).\n" +
        "- Every block must be at a valid coordinate within the component's bounding box.\n" +
        "- Build structurally — walls need foundations, roofs need walls, etc.\n" +
        "- Do NOT place blocks outside the component's bounding box dimensions.\n" +
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
        sb.append("  Bounding box: ").append(component.sizeX).append("×")
          .append(component.sizeY).append("×").append(component.sizeZ).append(" blocks\n");
        sb.append("  Origin within build: (").append(component.originX).append(", ")
          .append(component.originY).append(", ").append(component.originZ).append(")\n");

        sb.append("\nAvailable block IDs (use ONLY these):\n");
        List<String> palette = buildFullPaletteFromSpec(spec);
        for (String id : palette) {
            sb.append("  ").append(id).append("\n");
        }

        sb.append("\nMax blocks for this component: ").append(component.sizeX * component.sizeY * component.sizeZ);

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
        List<String> safe = defaultPalette();
        if (spec == null || spec.materials == null) return safe;
        java.util.List<String> combined = new java.util.ArrayList<>(safe);
        for (String mat : spec.materials) {
            String full = mat.contains(":") ? mat : "minecraft:" + mat;
            if (!combined.contains(full)) combined.add(full);
        }
        return java.util.Collections.unmodifiableList(combined);
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
        // The canonical safe palette — always allowed.
        List<String> safe = defaultPalette();
        if (state.spec == null || state.spec.materials == null) return safe;

        // Expand spec materials (fragments like "stone_bricks") to full IDs.
        java.util.List<String> combined = new java.util.ArrayList<>(safe);
        for (String mat : state.spec.materials) {
            String full = mat.contains(":") ? mat : "minecraft:" + mat;
            if (!combined.contains(full)) combined.add(full);
        }
        return java.util.Collections.unmodifiableList(combined);
    }

    static List<String> defaultPalette() {
        return List.of(
            "minecraft:acacia_door",
            "minecraft:acacia_fence",
            "minecraft:acacia_fence_gate",
            "minecraft:acacia_leaves",
            "minecraft:acacia_log",
            "minecraft:acacia_planks",
            "minecraft:acacia_slab",
            "minecraft:acacia_stairs",
            "minecraft:acacia_trapdoor",
            "minecraft:acacia_wood",
            "minecraft:amethyst_block",
            "minecraft:ancient_debris",
            "minecraft:andesite",
            "minecraft:andesite_slab",
            "minecraft:andesite_stairs",
            "minecraft:andesite_wall",
            "minecraft:anvil",
            "minecraft:azalea_leaves",
            "minecraft:barrel",
            "minecraft:basalt",
            "minecraft:beacon",
            "minecraft:birch_door",
            "minecraft:birch_fence",
            "minecraft:birch_fence_gate",
            "minecraft:birch_leaves",
            "minecraft:birch_log",
            "minecraft:birch_planks",
            "minecraft:birch_slab",
            "minecraft:birch_stairs",
            "minecraft:birch_trapdoor",
            "minecraft:birch_wood",
            "minecraft:blackstone",
            "minecraft:blackstone_slab",
            "minecraft:blackstone_stairs",
            "minecraft:blackstone_wall",
            "minecraft:black_carpet",
            "minecraft:black_concrete",
            "minecraft:black_concrete_powder",
            "minecraft:black_glazed_terracotta",
            "minecraft:black_shulker_box",
            "minecraft:black_stained_glass",
            "minecraft:black_stained_glass_pane",
            "minecraft:black_terracotta",
            "minecraft:black_wool",
            "minecraft:blast_furnace",
            "minecraft:blue_carpet",
            "minecraft:blue_concrete",
            "minecraft:blue_concrete_powder",
            "minecraft:blue_glazed_terracotta",
            "minecraft:blue_ice",
            "minecraft:blue_shulker_box",
            "minecraft:blue_stained_glass",
            "minecraft:blue_stained_glass_pane",
            "minecraft:blue_terracotta",
            "minecraft:blue_wool",
            "minecraft:bone_block",
            "minecraft:bookshelf",
            "minecraft:brain_coral",
            "minecraft:brain_coral_block",
            "minecraft:brewing_stand",
            "minecraft:bricks",
            "minecraft:brick_slab",
            "minecraft:brick_stairs",
            "minecraft:brick_wall",
            "minecraft:brown_carpet",
            "minecraft:brown_concrete",
            "minecraft:brown_concrete_powder",
            "minecraft:brown_glazed_terracotta",
            "minecraft:brown_mushroom_block",
            "minecraft:brown_shulker_box",
            "minecraft:brown_stained_glass",
            "minecraft:brown_stained_glass_pane",
            "minecraft:brown_terracotta",
            "minecraft:brown_wool",
            "minecraft:bubble_coral",
            "minecraft:bubble_coral_block",
            "minecraft:calcite",
            "minecraft:campfire",
            "minecraft:candle",
            "minecraft:candle_cake",
            "minecraft:cartography_table",
            "minecraft:carved_pumpkin",
            "minecraft:chain",
            "minecraft:chest",
            "minecraft:chipped_anvil",
            "minecraft:chiseled_deepslate",
            "minecraft:chiseled_nether_bricks",
            "minecraft:chiseled_polished_blackstone",
            "minecraft:chiseled_quartz_block",
            "minecraft:chiseled_red_sandstone",
            "minecraft:chiseled_sandstone",
            "minecraft:chiseled_stone_bricks",
            "minecraft:clay",
            "minecraft:coal_block",
            "minecraft:coarse_dirt",
            "minecraft:cobbled_deepslate",
            "minecraft:cobbled_deepslate_slab",
            "minecraft:cobbled_deepslate_stairs",
            "minecraft:cobbled_deepslate_wall",
            "minecraft:cobblestone",
            "minecraft:cobblestone_slab",
            "minecraft:cobblestone_stairs",
            "minecraft:cobblestone_wall",
            "minecraft:composter",
            "minecraft:copper_block",
            "minecraft:cracked_deepslate_bricks",
            "minecraft:cracked_deepslate_tiles",
            "minecraft:cracked_nether_bricks",
            "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:cracked_stone_bricks",
            "minecraft:crafting_table",
            "minecraft:crimson_door",
            "minecraft:crimson_fence",
            "minecraft:crimson_fence_gate",
            "minecraft:crimson_hyphae",
            "minecraft:crimson_nylium",
            "minecraft:crimson_planks",
            "minecraft:crimson_slab",
            "minecraft:crimson_stairs",
            "minecraft:crimson_trapdoor",
            "minecraft:crying_obsidian",
            "minecraft:cut_copper",
            "minecraft:cut_copper_slab",
            "minecraft:cut_copper_stairs",
            "minecraft:cut_red_sandstone",
            "minecraft:cut_red_sandstone_slab",
            "minecraft:cut_sandstone",
            "minecraft:cut_sandstone_slab",
            "minecraft:cyan_carpet",
            "minecraft:cyan_concrete",
            "minecraft:cyan_concrete_powder",
            "minecraft:cyan_glazed_terracotta",
            "minecraft:cyan_shulker_box",
            "minecraft:cyan_stained_glass",
            "minecraft:cyan_stained_glass_pane",
            "minecraft:cyan_terracotta",
            "minecraft:cyan_wool",
            "minecraft:damaged_anvil",
            "minecraft:dark_oak_door",
            "minecraft:dark_oak_fence",
            "minecraft:dark_oak_fence_gate",
            "minecraft:dark_oak_leaves",
            "minecraft:dark_oak_log",
            "minecraft:dark_oak_planks",
            "minecraft:dark_oak_slab",
            "minecraft:dark_oak_stairs",
            "minecraft:dark_oak_trapdoor",
            "minecraft:dark_oak_wood",
            "minecraft:dark_prismarine",
            "minecraft:dark_prismarine_slab",
            "minecraft:dark_prismarine_stairs",
            "minecraft:dead_brain_coral",
            "minecraft:dead_brain_coral_block",
            "minecraft:dead_bubble_coral",
            "minecraft:dead_bubble_coral_block",
            "minecraft:dead_fire_coral",
            "minecraft:dead_fire_coral_block",
            "minecraft:dead_horn_coral",
            "minecraft:dead_horn_coral_block",
            "minecraft:dead_tube_coral",
            "minecraft:dead_tube_coral_block",
            "minecraft:deepslate",
            "minecraft:deepslate_bricks",
            "minecraft:deepslate_brick_slab",
            "minecraft:deepslate_brick_stairs",
            "minecraft:deepslate_brick_wall",
            "minecraft:deepslate_tiles",
            "minecraft:deepslate_tile_slab",
            "minecraft:deepslate_tile_stairs",
            "minecraft:deepslate_tile_wall",
            "minecraft:diamond_block",
            "minecraft:diorite",
            "minecraft:diorite_slab",
            "minecraft:diorite_stairs",
            "minecraft:diorite_wall",
            "minecraft:dirt",
            "minecraft:dried_kelp_block",
            "minecraft:dripstone_block",
            "minecraft:emerald_block",
            "minecraft:enchanting_table",
            "minecraft:ender_chest",
            "minecraft:end_rod",
            "minecraft:end_stone",
            "minecraft:end_stone_bricks",
            "minecraft:end_stone_brick_slab",
            "minecraft:end_stone_brick_stairs",
            "minecraft:end_stone_brick_wall",
            "minecraft:exposed_copper",
            "minecraft:exposed_cut_copper",
            "minecraft:exposed_cut_copper_slab",
            "minecraft:exposed_cut_copper_stairs",
            "minecraft:fire_coral",
            "minecraft:fire_coral_block",
            "minecraft:fletching_table",
            "minecraft:flowering_azalea_leaves",
            "minecraft:furnace",
            "minecraft:gilded_blackstone",
            "minecraft:glass",
            "minecraft:glass_pane",
            "minecraft:glowstone",
            "minecraft:gold_block",
            "minecraft:granite",
            "minecraft:granite_slab",
            "minecraft:granite_stairs",
            "minecraft:granite_wall",
            "minecraft:grass_block",
            "minecraft:gravel",
            "minecraft:gray_carpet",
            "minecraft:gray_concrete",
            "minecraft:gray_concrete_powder",
            "minecraft:gray_glazed_terracotta",
            "minecraft:gray_shulker_box",
            "minecraft:gray_stained_glass",
            "minecraft:gray_stained_glass_pane",
            "minecraft:gray_terracotta",
            "minecraft:gray_wool",
            "minecraft:green_carpet",
            "minecraft:green_concrete",
            "minecraft:green_concrete_powder",
            "minecraft:green_glazed_terracotta",
            "minecraft:green_shulker_box",
            "minecraft:green_stained_glass",
            "minecraft:green_stained_glass_pane",
            "minecraft:green_terracotta",
            "minecraft:green_wool",
            "minecraft:grindstone",
            "minecraft:hay_block",
            "minecraft:honeycomb_block",
            "minecraft:horn_coral",
            "minecraft:horn_coral_block",
            "minecraft:ice",
            "minecraft:iron_bars",
            "minecraft:iron_block",
            "minecraft:iron_door",
            "minecraft:iron_trapdoor",
            "minecraft:jack_o_lantern",
            "minecraft:jungle_door",
            "minecraft:jungle_fence",
            "minecraft:jungle_fence_gate",
            "minecraft:jungle_leaves",
            "minecraft:jungle_log",
            "minecraft:jungle_planks",
            "minecraft:jungle_slab",
            "minecraft:jungle_stairs",
            "minecraft:jungle_trapdoor",
            "minecraft:jungle_wood",
            "minecraft:ladder",
            "minecraft:lantern",
            "minecraft:lapis_block",
            "minecraft:lectern",
            "minecraft:light_blue_carpet",
            "minecraft:light_blue_concrete",
            "minecraft:light_blue_concrete_powder",
            "minecraft:light_blue_glazed_terracotta",
            "minecraft:light_blue_shulker_box",
            "minecraft:light_blue_stained_glass",
            "minecraft:light_blue_stained_glass_pane",
            "minecraft:light_blue_terracotta",
            "minecraft:light_blue_wool",
            "minecraft:light_gray_carpet",
            "minecraft:light_gray_concrete",
            "minecraft:light_gray_concrete_powder",
            "minecraft:light_gray_glazed_terracotta",
            "minecraft:light_gray_shulker_box",
            "minecraft:light_gray_stained_glass",
            "minecraft:light_gray_stained_glass_pane",
            "minecraft:light_gray_terracotta",
            "minecraft:light_gray_wool",
            "minecraft:lime_carpet",
            "minecraft:lime_concrete",
            "minecraft:lime_concrete_powder",
            "minecraft:lime_glazed_terracotta",
            "minecraft:lime_shulker_box",
            "minecraft:lime_stained_glass",
            "minecraft:lime_stained_glass_pane",
            "minecraft:lime_terracotta",
            "minecraft:lime_wool",
            "minecraft:lodestone",
            "minecraft:loom",
            "minecraft:magenta_carpet",
            "minecraft:magenta_concrete",
            "minecraft:magenta_concrete_powder",
            "minecraft:magenta_glazed_terracotta",
            "minecraft:magenta_shulker_box",
            "minecraft:magenta_stained_glass",
            "minecraft:magenta_stained_glass_pane",
            "minecraft:magenta_terracotta",
            "minecraft:magenta_wool",
            "minecraft:magma_block",
            "minecraft:melon",
            "minecraft:mossy_cobblestone",
            "minecraft:mossy_cobblestone_slab",
            "minecraft:mossy_cobblestone_stairs",
            "minecraft:mossy_cobblestone_wall",
            "minecraft:mossy_stone_bricks",
            "minecraft:mossy_stone_brick_slab",
            "minecraft:mossy_stone_brick_stairs",
            "minecraft:mossy_stone_brick_wall",
            "minecraft:moss_block",
            "minecraft:moss_carpet",
            "minecraft:mycelium",
            "minecraft:netherite_block",
            "minecraft:netherrack",
            "minecraft:nether_bricks",
            "minecraft:nether_brick_fence",
            "minecraft:nether_brick_slab",
            "minecraft:nether_brick_stairs",
            "minecraft:nether_brick_wall",
            "minecraft:nether_wart_block",
            "minecraft:oak_door",
            "minecraft:oak_fence",
            "minecraft:oak_fence_gate",
            "minecraft:oak_leaves",
            "minecraft:oak_log",
            "minecraft:oak_planks",
            "minecraft:oak_slab",
            "minecraft:oak_stairs",
            "minecraft:oak_trapdoor",
            "minecraft:oak_wood",
            "minecraft:obsidian",
            "minecraft:orange_carpet",
            "minecraft:orange_concrete",
            "minecraft:orange_concrete_powder",
            "minecraft:orange_glazed_terracotta",
            "minecraft:orange_shulker_box",
            "minecraft:orange_stained_glass",
            "minecraft:orange_stained_glass_pane",
            "minecraft:orange_terracotta",
            "minecraft:orange_wool",
            "minecraft:oxidized_copper",
            "minecraft:oxidized_cut_copper",
            "minecraft:oxidized_cut_copper_slab",
            "minecraft:oxidized_cut_copper_stairs",
            "minecraft:packed_ice",
            "minecraft:petrified_oak_slab",
            "minecraft:pink_carpet",
            "minecraft:pink_concrete",
            "minecraft:pink_concrete_powder",
            "minecraft:pink_glazed_terracotta",
            "minecraft:pink_shulker_box",
            "minecraft:pink_stained_glass",
            "minecraft:pink_stained_glass_pane",
            "minecraft:pink_terracotta",
            "minecraft:pink_wool",
            "minecraft:podzol",
            "minecraft:polished_andesite",
            "minecraft:polished_andesite_slab",
            "minecraft:polished_andesite_stairs",
            "minecraft:polished_basalt",
            "minecraft:polished_blackstone",
            "minecraft:polished_blackstone_bricks",
            "minecraft:polished_blackstone_brick_slab",
            "minecraft:polished_blackstone_brick_stairs",
            "minecraft:polished_blackstone_brick_wall",
            "minecraft:polished_blackstone_slab",
            "minecraft:polished_blackstone_stairs",
            "minecraft:polished_blackstone_wall",
            "minecraft:polished_deepslate",
            "minecraft:polished_deepslate_slab",
            "minecraft:polished_deepslate_stairs",
            "minecraft:polished_deepslate_wall",
            "minecraft:polished_diorite",
            "minecraft:polished_diorite_slab",
            "minecraft:polished_diorite_stairs",
            "minecraft:polished_granite",
            "minecraft:polished_granite_slab",
            "minecraft:polished_granite_stairs",
            "minecraft:prismarine",
            "minecraft:prismarine_bricks",
            "minecraft:prismarine_brick_slab",
            "minecraft:prismarine_brick_stairs",
            "minecraft:prismarine_slab",
            "minecraft:prismarine_stairs",
            "minecraft:prismarine_wall",
            "minecraft:pumpkin",
            "minecraft:purple_carpet",
            "minecraft:purple_concrete",
            "minecraft:purple_concrete_powder",
            "minecraft:purple_glazed_terracotta",
            "minecraft:purple_shulker_box",
            "minecraft:purple_stained_glass",
            "minecraft:purple_stained_glass_pane",
            "minecraft:purple_terracotta",
            "minecraft:purple_wool",
            "minecraft:purpur_block",
            "minecraft:purpur_pillar",
            "minecraft:purpur_slab",
            "minecraft:purpur_stairs",
            "minecraft:quartz_block",
            "minecraft:quartz_bricks",
            "minecraft:quartz_pillar",
            "minecraft:quartz_slab",
            "minecraft:quartz_stairs",
            "minecraft:raw_copper_block",
            "minecraft:raw_gold_block",
            "minecraft:raw_iron_block",
            "minecraft:redstone_block",
            "minecraft:red_carpet",
            "minecraft:red_concrete",
            "minecraft:red_concrete_powder",
            "minecraft:red_glazed_terracotta",
            "minecraft:red_mushroom_block",
            "minecraft:red_nether_bricks",
            "minecraft:red_nether_brick_slab",
            "minecraft:red_nether_brick_stairs",
            "minecraft:red_nether_brick_wall",
            "minecraft:red_sand",
            "minecraft:red_sandstone",
            "minecraft:red_sandstone_slab",
            "minecraft:red_sandstone_stairs",
            "minecraft:red_sandstone_wall",
            "minecraft:red_shulker_box",
            "minecraft:red_stained_glass",
            "minecraft:red_stained_glass_pane",
            "minecraft:red_terracotta",
            "minecraft:red_wool",
            "minecraft:rooted_dirt",
            "minecraft:sand",
            "minecraft:sandstone",
            "minecraft:sandstone_slab",
            "minecraft:sandstone_stairs",
            "minecraft:sandstone_wall",
            "minecraft:sea_lantern",
            "minecraft:shroomlight",
            "minecraft:shulker_box",
            "minecraft:smithing_table",
            "minecraft:smoker",
            "minecraft:smooth_basalt",
            "minecraft:smooth_quartz",
            "minecraft:smooth_quartz_slab",
            "minecraft:smooth_quartz_stairs",
            "minecraft:smooth_red_sandstone",
            "minecraft:smooth_red_sandstone_slab",
            "minecraft:smooth_red_sandstone_stairs",
            "minecraft:smooth_sandstone",
            "minecraft:smooth_sandstone_slab",
            "minecraft:smooth_sandstone_stairs",
            "minecraft:smooth_stone",
            "minecraft:smooth_stone_slab",
            "minecraft:snow",
            "minecraft:snow_block",
            "minecraft:soul_campfire",
            "minecraft:soul_lantern",
            "minecraft:soul_sand",
            "minecraft:soul_soil",
            "minecraft:soul_torch",
            "minecraft:sponge",
            "minecraft:spruce_door",
            "minecraft:spruce_fence",
            "minecraft:spruce_fence_gate",
            "minecraft:spruce_leaves",
            "minecraft:spruce_log",
            "minecraft:spruce_planks",
            "minecraft:spruce_slab",
            "minecraft:spruce_stairs",
            "minecraft:spruce_trapdoor",
            "minecraft:spruce_wood",
            "minecraft:stone",
            "minecraft:stonecutter",
            "minecraft:stone_bricks",
            "minecraft:stone_brick_slab",
            "minecraft:stone_brick_stairs",
            "minecraft:stone_brick_wall",
            "minecraft:stone_slab",
            "minecraft:stone_stairs",
            "minecraft:stripped_acacia_log",
            "minecraft:stripped_acacia_wood",
            "minecraft:stripped_birch_log",
            "minecraft:stripped_birch_wood",
            "minecraft:stripped_crimson_hyphae",
            "minecraft:stripped_dark_oak_log",
            "minecraft:stripped_dark_oak_wood",
            "minecraft:stripped_jungle_log",
            "minecraft:stripped_jungle_wood",
            "minecraft:stripped_oak_log",
            "minecraft:stripped_oak_wood",
            "minecraft:stripped_spruce_log",
            "minecraft:stripped_spruce_wood",
            "minecraft:stripped_warped_hyphae",
            "minecraft:terracotta",
            "minecraft:tinted_glass",
            "minecraft:torch",
            "minecraft:trapped_chest",
            "minecraft:tube_coral",
            "minecraft:tube_coral_block",
            "minecraft:tuff",
            "minecraft:wall_torch",
            "minecraft:warped_door",
            "minecraft:warped_fence",
            "minecraft:warped_fence_gate",
            "minecraft:warped_hyphae",
            "minecraft:warped_nylium",
            "minecraft:warped_planks",
            "minecraft:warped_slab",
            "minecraft:warped_stairs",
            "minecraft:warped_trapdoor",
            "minecraft:warped_wart_block",
            "minecraft:waxed_copper_block",
            "minecraft:waxed_cut_copper",
            "minecraft:waxed_cut_copper_slab",
            "minecraft:waxed_cut_copper_stairs",
            "minecraft:waxed_exposed_copper",
            "minecraft:waxed_exposed_cut_copper",
            "minecraft:waxed_exposed_cut_copper_slab",
            "minecraft:waxed_exposed_cut_copper_stairs",
            "minecraft:waxed_oxidized_copper",
            "minecraft:waxed_oxidized_cut_copper",
            "minecraft:waxed_oxidized_cut_copper_slab",
            "minecraft:waxed_oxidized_cut_copper_stairs",
            "minecraft:waxed_weathered_copper",
            "minecraft:waxed_weathered_cut_copper",
            "minecraft:waxed_weathered_cut_copper_slab",
            "minecraft:waxed_weathered_cut_copper_stairs",
            "minecraft:weathered_copper",
            "minecraft:weathered_cut_copper",
            "minecraft:weathered_cut_copper_slab",
            "minecraft:weathered_cut_copper_stairs",
            "minecraft:wet_sponge",
            "minecraft:white_carpet",
            "minecraft:white_concrete",
            "minecraft:white_concrete_powder",
            "minecraft:white_glazed_terracotta",
            "minecraft:white_shulker_box",
            "minecraft:white_stained_glass",
            "minecraft:white_stained_glass_pane",
            "minecraft:white_terracotta",
            "minecraft:white_wool",
            "minecraft:yellow_carpet",
            "minecraft:yellow_concrete",
            "minecraft:yellow_concrete_powder",
            "minecraft:yellow_glazed_terracotta",
            "minecraft:yellow_shulker_box",
            "minecraft:yellow_stained_glass",
            "minecraft:yellow_stained_glass_pane",
            "minecraft:yellow_terracotta",
            "minecraft:yellow_wool"
        );
    }
}
