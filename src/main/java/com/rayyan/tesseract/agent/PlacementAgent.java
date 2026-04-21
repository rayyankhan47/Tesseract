package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.placement.SyncPlacer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
/**
 * Placement helper: resolves block IDs and runs {@link SyncPlacer} (§11 — no tick-drip).
 *
 * <p>Translates each op's blueprint-local coordinates to world coordinates in
 * {@code state.completedOps} after placement finishes.
 */
public final class PlacementAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.placement");

    private PlacementAgent() {}

    /**
     * Places ops relative to {@code origin} in one pass (or batched across ticks if huge).
     *
     * <p>Coordinate translation: world pos = {@code origin} + (op.x, op.y, op.z)
     */
    public static void placeOps(BuildState state,
                                ServerWorld world,
                                List<BlockOp> ops,
                                BlockPos origin,
                                String label,
                                Runnable onComplete) {
        if (ops == null || ops.isEmpty()) {
            LOGGER.warn("PlacementAgent: no ops to place for '{}'.", label);
            onComplete.run();
            return;
        }

        List<BlockOp> worldOps = new ArrayList<>(ops.size());
        for (BlockOp op : ops) {
            BlockOp w = new BlockOp();
            w.x = origin.getX() + op.x;
            w.y = origin.getY() + op.y;
            w.z = origin.getZ() + op.z;
            w.block = op.block;
            worldOps.add(w);
        }

        SyncPlacer.placeAll(world, origin, ops, world.getServer(), result -> {
            state.completedOps.addAll(worldOps);
            LOGGER.info("PlacementAgent: '{}' — {} placed, {} failed ({} blueprint ops).",
                    label, result.placed(), result.failures(), ops.size());
            onComplete.run();
        });
    }

    // -------------------------------------------------------------------------
    // Helpers — shared with SyncPlacer
    // -------------------------------------------------------------------------

    /**
     * Resolves a block ID string to a BlockState.
     *
     * <p>Supports optional block state properties in Minecraft bracket notation:
     *   "minecraft:oak_stairs[facing=north,half=bottom]"
     *   "minecraft:oak_log[axis=y]"
     *
     * <p>Unknown property keys or values are silently ignored (the default state value is kept).
     * Returns null for an unknown block ID or air.
     */
    public static BlockState toBlockState(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;

        String baseId = blockId;
        Map<String, String> props = new LinkedHashMap<>();
        int bracketStart = blockId.indexOf('[');
        if (bracketStart != -1 && blockId.endsWith("]")) {
            baseId = blockId.substring(0, bracketStart);
            String propsStr = blockId.substring(bracketStart + 1, blockId.length() - 1);
            for (String pair : propsStr.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) props.put(kv[0].trim(), kv[1].trim());
            }
        }

        Identifier identifier = Identifier.tryParse(baseId);
        if (identifier == null) return null;
        Block block = Registry.BLOCK.get(identifier);
        if (block == Blocks.AIR) return null;

        BlockState state = block.getDefaultState();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            state = applyProperty(state, entry.getKey(), entry.getValue());
        }
        return state;
    }

    /** Applies a single named property to a BlockState; ignores unknown keys/values. */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, String key, String value) {
        Property<?> prop = state.getBlock().getStateManager().getProperty(key);
        if (prop == null) return state;
        Optional<T> parsed = (Optional<T>) prop.parse(value);
        return parsed.map(v -> state.with((Property<T>) prop, v)).orElse(state);
    }
}
