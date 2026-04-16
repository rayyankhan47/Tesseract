package com.rayyan.tesseract.jobs;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.paste.BuildPlan;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildQueueManager {
	private static final int BLOCKS_PER_TICK = 20;
	private static final long PROGRESS_INTERVAL_MS = 1000L;
	private static final Map<UUID, BuildJob> ACTIVE_JOBS = new ConcurrentHashMap<>();

	private BuildQueueManager() {}

	public static boolean startBuild(ServerPlayerEntity player, Selection selection, BuildPlan plan) {
		if (player == null || selection == null || plan == null || plan.ops == null) {
			return false;
		}
		UUID playerId = player.getUuid();
		if (ACTIVE_JOBS.containsKey(playerId)) {
			player.sendMessage(Text.of("Error: build already running for this player."), false);
			return false;
		}
		BlockPos origin = selection.getMin();
		if (origin == null) {
			player.sendMessage(Text.of("Error: invalid build origin."), false);
			return false;
		}
		// 1.18.2: ServerPlayerEntity#getWorld() returns a ServerWorld on the server.
		BuildJob job = new BuildJob(playerId, (ServerWorld) player.getWorld(), origin, plan.ops);
		ACTIVE_JOBS.put(playerId, job);
		player.sendMessage(Text.of("Build started (" + plan.ops.size() + " ops)."), false);
		return true;
	}

	public static boolean startInstantBuild(ServerPlayerEntity player, Selection selection, BuildPlan plan) {
		if (player == null || selection == null || plan == null || plan.ops == null) {
			return false;
		}
		BlockPos origin = selection.getMin();
		if (origin == null) {
			player.sendMessage(Text.of("Error: invalid build origin."), false);
			return false;
		}
		ServerWorld world = (ServerWorld) player.getWorld();
		int placed = 0;
		for (int i = 0; i < plan.ops.size(); i++) {
			BlockOp op = plan.ops.get(i);
			BlockPos pos = origin.add(op.x, op.y, op.z);
			if (!world.isChunkLoaded(pos)) {
				player.sendMessage(Text.of("Error: build halted, chunk not loaded near " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
				return false;
			}
			BlockState state = toBlockState(op.block);
			if (state == null) {
				player.sendMessage(Text.of("Error: unknown block id " + op.block), false);
				return false;
			}
			world.setBlockState(pos, state, Block.NOTIFY_ALL);
			placed++;
		}
		player.sendMessage(Text.of("Build complete: " + placed + " blocks."), false);
		return true;
	}

	/**
	 * Queues one component's ops for throttled placement (20 blocks/tick).
	 * Calls {@code onComplete} on the server thread when all ops are placed,
	 * instead of calling {@link BuildJobManager#finish} — used by PlacementAgent.
	 */
	public static void startComponentBuild(UUID playerId, ServerWorld world, BlockPos origin,
	                                        List<BlockOp> ops, Runnable onComplete) {
		if (playerId == null || world == null || origin == null || ops == null) return;
		BuildJob job = new BuildJob(playerId, world, origin, ops, onComplete);
		ACTIVE_JOBS.put(playerId, job);
	}

	public static void tick(MinecraftServer server) {
		for (Map.Entry<UUID, BuildJob> entry : ACTIVE_JOBS.entrySet()) {
			BuildJob job = entry.getValue();
			if (job == null) {
				ACTIVE_JOBS.remove(entry.getKey());
				continue;
			}
			boolean done = job.tick(server);
			if (done) {
				ACTIVE_JOBS.remove(entry.getKey());
				// Agent-path jobs supply onComplete callback which handles Orchestrator advancement.
				// Only call BuildJobManager.finish for legacy paste-path jobs (no callback).
				if (job.onComplete == null) {
					BuildJobManager.finish(entry.getKey());
				}
			}
		}
	}

	private static final class BuildJob {
		private final UUID playerId;
		private final ServerWorld world;
		private final BlockPos origin;
		private final List<BlockOp> ops;
		/** Non-null for agent-path builds: called instead of BuildJobManager.finish on completion. */
		private final Runnable onComplete;
		private int index;
		private int placed;
		private long lastProgressAt;

		/** Legacy constructor — calls BuildJobManager.finish on completion (paste path). */
		private BuildJob(UUID playerId, ServerWorld world, BlockPos origin, List<BlockOp> ops) {
			this(playerId, world, origin, ops, null);
		}

		/** Agent-path constructor — calls onComplete callback on completion. */
		private BuildJob(UUID playerId, ServerWorld world, BlockPos origin,
		                 List<BlockOp> ops, Runnable onComplete) {
			this.playerId = playerId;
			this.world = world;
			this.origin = origin;
			this.ops = ops;
			this.onComplete = onComplete;
			this.index = 0;
			this.placed = 0;
			this.lastProgressAt = System.currentTimeMillis();
		}

		private boolean tick(MinecraftServer server) {
			if (world == null || server == null) {
				return true;
			}
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
			if (player == null) {
				return true;
			}
			int opsThisTick = 0;
			while (opsThisTick < BLOCKS_PER_TICK && index < ops.size()) {
				BlockOp op = ops.get(index);
				BlockPos pos = origin.add(op.x, op.y, op.z);
				if (!world.isChunkLoaded(pos)) {
					player.sendMessage(Text.of("Error: build halted, chunk not loaded near " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
					return true;
				}
				BlockState state = toBlockState(op.block);
				if (state == null) {
					player.sendMessage(Text.of("Error: unknown block id " + op.block), false);
					return true;
				}
				world.setBlockState(pos, state, Block.NOTIFY_ALL);
				index++;
				placed++;
				opsThisTick++;
			}
			long now = System.currentTimeMillis();
			if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
				player.sendMessage(Text.of("Progress: " + placed + "/" + ops.size() + " blocks"), false);
				lastProgressAt = now;
			}
			if (index >= ops.size()) {
				if (onComplete != null) {
					onComplete.run();
				} else {
					player.sendMessage(Text.of("Build complete: " + placed + " blocks."), false);
				}
				return true;
			}
			return false;
		}
	}

	private static BlockState toBlockState(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return null;
		}
		Identifier identifier = Identifier.tryParse(blockId);
		if (identifier == null) {
			return null;
		}
		Block block = Registry.BLOCK.get(identifier);
		if (block == Blocks.AIR) {
			return null;
		}
		return block.getDefaultState();
	}
}
