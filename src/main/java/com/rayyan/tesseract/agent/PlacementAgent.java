package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.jobs.BuildQueueManager;
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
import java.util.function.Consumer;

/**
 * Stage 5 of the build pipeline.
 *
 * Wraps {@link BuildQueueManager}'s throttled placement loop. Translates each
 * op's component-relative coordinates to world coordinates, queues them into
 * BuildQueueManager at 20 blocks/tick, accumulates placed ops in
 * {@code state.completedOps}, and calls {@code onComplete} when the component
 * is fully placed so the Orchestrator can advance to the next component.
 */
public final class PlacementAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.placement");

    private PlacementAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Queues the approved ops for one component into the throttled placement loop.
     *
     * Coordinate translation: world pos = state.placementOrigin
     *                                      + (component.originX, component.originY, component.originZ)
     *                                      + (op.x, op.y, op.z)
     *
     * @param state     shared build context (provides placementOrigin and completedOps)
     * @param world     server world to place blocks in
     * @param ops       approved ops from CriticAgent (component-relative coordinates)
     * @param component the component being placed (provides origin offsets)
     * @param onComplete called (on the server thread) after all blocks in this component are placed
     * @param onError    called (on the server thread) with a human-readable reason if placement fails
     */
    public static void placeComponent(BuildState state,
                                       ServerWorld world,
                                       List<BlockOp> ops,
                                       ComponentPlan component,
                                       Runnable onComplete,
                                       Consumer<String> onError) {
        if (ops == null || ops.isEmpty()) {
            LOGGER.warn("PlacementAgent: no ops to place for component '{}'.", component.name);
            onComplete.run();
            return;
        }

        BlockPos componentOrigin = state.placementOrigin
                .add(component.originX, component.originY, component.originZ);

        // Validate chunk availability and block IDs before queuing.
        for (BlockOp op : ops) {
            BlockPos worldPos = componentOrigin.add(op.x, op.y, op.z);
            if (!world.isChunkLoaded(worldPos)) {
                onError.accept("chunk not loaded near " + worldPos.getX()
                        + " " + worldPos.getY() + " " + worldPos.getZ());
                return;
            }
            if (toBlockState(op.block) == null) {
                onError.accept("unknown block id: " + op.block);
                return;
            }
        }

        // Build world-coordinate copies for state.completedOps accumulation.
        List<BlockOp> worldOps = new ArrayList<>(ops.size());
        for (BlockOp op : ops) {
            BlockOp w = new BlockOp();
            w.x = componentOrigin.getX() + op.x;
            w.y = componentOrigin.getY() + op.y;
            w.z = componentOrigin.getZ() + op.z;
            w.block = op.block;
            worldOps.add(w);
        }

        // Wrap onComplete: first append to completedOps, then signal the Orchestrator.
        Runnable completionCallback = () -> {
            state.completedOps.addAll(worldOps);
            LOGGER.info("PlacementAgent: component '{}' placed ({} blocks).", component.name, ops.size());
            onComplete.run();
        };

        // Queue into BuildQueueManager at 20 blocks/tick.
        // componentOrigin is the world-space base; ops are component-relative.
        BuildQueueManager.startComponentBuild(
                state.playerId, world, componentOrigin, ops, completionCallback);
    }

    // -------------------------------------------------------------------------
    // Helpers — copied exactly from BuildQueueManager
    // -------------------------------------------------------------------------

    /**
     * Resolves a block ID string to a BlockState.
     *
     * Supports optional block state properties in Minecraft bracket notation:
     *   "minecraft:oak_stairs[facing=north,half=bottom]"
     *   "minecraft:oak_log[axis=y]"
     *
     * Unknown property keys or values are silently ignored (the default state value is kept).
     * Returns null for an unknown block ID or air.
     */
    public static BlockState toBlockState(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;

        // Split base ID from optional "[key=value,...]" properties.
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
