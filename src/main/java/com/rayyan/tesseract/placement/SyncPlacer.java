package com.rayyan.tesseract.placement;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.PlacementAgent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * §11 — synchronous block placement on the server world (no tick-drip queue).
 *
 * <p>Small builds ({@code <=}{@link #LARGE_BUILD_THRESHOLD}) complete in one server tick.
 * Larger builds place in {@link #BATCH_SIZE} op chunks with {@link MinecraftServer#execute}
 * between chunks so the server can breathe between batches.
 */
public final class SyncPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.sync_placer");

    /** Ops per batch when {@link #LARGE_BUILD_THRESHOLD} is exceeded. */
    public static final int BATCH_SIZE = 2000;
    /** Above this, placement is split across ticks (§11.1.3). */
    public static final int LARGE_BUILD_THRESHOLD = 10_000;

    public record PlacementResult(int placed, int failures) {}

    private SyncPlacer() {}

    /**
     * Places every op relative to {@code origin}. Invokes {@code onComplete} on the server thread
     * with aggregate stats (after the last batch for large builds).
     */
    public static void placeAll(ServerWorld world,
                                BlockPos origin,
                                List<BlockOp> ops,
                                MinecraftServer server,
                                Consumer<PlacementResult> onComplete) {
        if (world == null || origin == null || server == null || onComplete == null) {
            throw new IllegalArgumentException("world, origin, server, onComplete required");
        }
        if (ops == null || ops.isEmpty()) {
            server.execute(() -> onComplete.accept(new PlacementResult(0, 0)));
            return;
        }
        if (ops.size() <= LARGE_BUILD_THRESHOLD) {
            PlacementResult r = placeRange(world, origin, ops, 0, ops.size());
            server.execute(() -> onComplete.accept(r));
            return;
        }
        int[] acc = new int[2];
        placeBatched(world, origin, ops, 0, server, acc, () ->
                server.execute(() -> onComplete.accept(new PlacementResult(acc[0], acc[1]))));
    }

    private static void placeBatched(ServerWorld world,
                                     BlockPos origin,
                                     List<BlockOp> ops,
                                     int start,
                                     MinecraftServer server,
                                     int[] acc,
                                     Runnable onAllBatchesDone) {
        int end = Math.min(start + BATCH_SIZE, ops.size());
        PlacementResult r = placeRange(world, origin, ops, start, end);
        acc[0] += r.placed();
        acc[1] += r.failures();
        if (end < ops.size()) {
            server.execute(() -> placeBatched(world, origin, ops, end, server, acc, onAllBatchesDone));
        } else {
            server.execute(onAllBatchesDone);
        }
    }

    /**
     * Places ops in {@code [start, end)} relative to {@code origin}. Safe to call only on the server thread.
     */
    public static PlacementResult placeRange(ServerWorld world,
                                             BlockPos origin,
                                             List<BlockOp> ops,
                                             int start,
                                             int end) {
        int placed = 0;
        int failures = 0;
        for (int i = start; i < end; i++) {
            BlockOp op = ops.get(i);
            if (op == null) {
                failures++;
                continue;
            }
            BlockPos pos = origin.add(op.x, op.y, op.z);
            if (!world.isChunkLoaded(pos)) {
                LOGGER.warn("SyncPlacer: chunk not loaded at {} — skipping", pos);
                failures++;
                continue;
            }
            BlockState state = PlacementAgent.toBlockState(op.block);
            if (state == null) {
                LOGGER.warn("SyncPlacer: unknown block id {} — skipping", op.block);
                failures++;
                continue;
            }
            try {
                world.setBlockState(pos, state, Block.NOTIFY_ALL);
                placed++;
            } catch (Exception e) {
                LOGGER.warn("SyncPlacer: setBlockState failed at {}: {}", pos, e.getMessage());
                failures++;
            }
        }
        return new PlacementResult(placed, failures);
    }
}
