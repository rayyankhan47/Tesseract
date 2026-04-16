package com.rayyan.tesseract.paste;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.TesseractMod;
import com.rayyan.tesseract.gumloop.GumloopPayload;
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
        public final GumloopPayload.Response plan;
        public final String error;

        private PlanValidationResult(GumloopPayload.Response plan, String error) {
            this.plan = plan;
            this.error = error;
        }

        public static PlanValidationResult success(GumloopPayload.Response plan) {
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
        GumloopPayload.Response plan = GSON.fromJson(planJson, GumloopPayload.Response.class);
        if (plan == null || plan.ops == null) {
            return PlanValidationResult.error("Parsed plan is missing ops.");
        }
        if (plan.meta == null) {
            plan.meta = new GumloopPayload.Meta();
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
            "minecraft:oak_log",
            "minecraft:oak_planks",
            "minecraft:cobblestone",
            "minecraft:stone_bricks",
            "minecraft:oak_stairs",
            "minecraft:cobblestone_stairs",
            "minecraft:stone_brick_stairs",
            "minecraft:oak_slab",
            "minecraft:cobblestone_slab",
            "minecraft:stone_brick_slab",
            "minecraft:oak_fence",
            "minecraft:cobblestone_wall",
            "minecraft:oak_door",
            "minecraft:oak_trapdoor",
            "minecraft:torch",
            "minecraft:lantern",
            "minecraft:glass"
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
