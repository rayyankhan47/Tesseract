package com.rayyan.tesseract.paste;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.TesseractMod;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Parses and validates a plan JSON body (the { meta, ops } format) against a
 * build selection. Extracted from the old GumloopClient so that PlanPasteClient
 * can continue to work without any Gumloop dependency.
 */
public final class PlanParser {
    private static final int MAX_BLOCKS = 600;
    private static final int DEFAULT_BUILD_HEIGHT = 12;
    private static final int LOG_BODY_PREVIEW = 240;
    private static final Gson GSON = new Gson();

    private PlanParser() {}

    // -------------------------------------------------------------------------
    // Public result type
    // -------------------------------------------------------------------------

    public static final class PlanValidationResult {
        public final BuildPlan plan;
        public final String error;

        private PlanValidationResult(BuildPlan plan, String error) {
            this.plan = plan;
            this.error = error;
        }

        public static PlanValidationResult success(BuildPlan plan) {
            return new PlanValidationResult(plan, null);
        }

        public static PlanValidationResult error(String error) {
            return new PlanValidationResult(null, error);
        }
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    public static PlanValidationResult parsePlanForSelection(String body, Selection buildSelection, String requestId) {
        BlockPos size = effectiveBuildSize(buildSelection);
        if (size == null) {
            return PlanValidationResult.error("Invalid build selection size.");
        }
        JsonObject planJson = extractPlanJson(body);
        if (planJson == null) {
            return PlanValidationResult.error("Could not find build plan in response.");
        }
        return parseAndValidate(planJson, size, requestId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static PlanValidationResult parseAndValidate(JsonObject planJson, BlockPos size, String requestId) {
        String validationError = validatePlan(planJson, size, defaultPalette(), MAX_BLOCKS);
        if (validationError != null) {
            TesseractMod.LOGGER.warn("PlanParser {} -> validation failed: {}", requestId, validationError);
            return PlanValidationResult.error(validationError);
        }
        BuildPlan plan = GSON.fromJson(planJson, BuildPlan.class);
        if (plan == null || plan.ops == null) {
            return PlanValidationResult.error("Parsed plan is missing ops.");
        }
        if (plan.meta == null) {
            plan.meta = new BuildPlan.Meta();
            plan.meta.blockCount = plan.ops.size();
        }
        return PlanValidationResult.success(plan);
    }

    private static BlockPos effectiveBuildSize(Selection buildSelection) {
        BlockPos size = buildSelection.getSize();
        if (size == null) {
            return null;
        }
        if (size.getY() <= 1) {
            return new BlockPos(size.getX(), DEFAULT_BUILD_HEIGHT, size.getZ());
        }
        return size;
    }

    private static JsonObject extractPlanJson(String body) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (JsonSyntaxException ex) {
            return null;
        }
        return findPlan(root, 0);
    }

    private static JsonObject findPlan(JsonElement element, int depth) {
        if (element == null || element.isJsonNull() || depth > 6) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("meta") || obj.has("ops")) {
                return obj;
            }
            if (obj.has("response")) {
                JsonObject found = findPlan(obj.get("response"), depth + 1);
                if (found != null) {
                    return found;
                }
            }
            for (String key : obj.keySet()) {
                JsonObject found = findPlan(obj.get(key), depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                JsonObject found = findPlan(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                try {
                    return findPlan(JsonParser.parseString(primitive.getAsString()), depth + 1);
                } catch (JsonSyntaxException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String validatePlan(JsonObject planJson, BlockPos size, List<String> palette, int maxBlocks) {
        if (!planJson.has("meta") || !planJson.has("ops")) {
            return "Plan missing required fields (meta, ops).";
        }
        if (!planJson.get("meta").isJsonObject()) {
            return "Plan meta must be an object.";
        }
        if (!planJson.get("ops").isJsonArray()) {
            return "Plan ops must be an array.";
        }
        JsonObject meta = planJson.getAsJsonObject("meta");
        JsonArray ops = planJson.getAsJsonArray("ops");
        Integer blockCount = getInt(meta.get("blockCount"));
        if (blockCount == null) {
            return "Plan meta.blockCount must be an integer.";
        }
        if (ops.size() > maxBlocks) {
            return "Plan has too many ops (" + ops.size() + " > " + maxBlocks + ").";
        }
        int width = size.getX();
        int height = size.getY();
        int length = size.getZ();
        for (int i = 0; i < ops.size(); i++) {
            JsonElement rawOp = ops.get(i);
            if (!rawOp.isJsonObject()) {
                return "Op " + i + " is not an object.";
            }
            JsonObject op = rawOp.getAsJsonObject();
            Integer x = getInt(op.get("x"));
            Integer y = getInt(op.get("y"));
            Integer z = getInt(op.get("z"));
            String block = getString(op.get("block"));
            if (x == null || y == null || z == null || block == null) {
                return "Op " + i + " missing x/y/z/block.";
            }
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
                return "Op " + i + " out of bounds (" + x + "," + y + "," + z + ").";
            }
            if (!palette.contains(block)) {
                return "Op " + i + " uses disallowed block: " + block;
            }
        }
        if (blockCount != ops.size()) {
            return "meta.blockCount does not match ops length.";
        }
        return null;
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

    private static Integer getInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) return null;
        try {
            return primitive.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String getString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) return null;
        return primitive.getAsString();
    }
}
