package com.rayyan.tesseract.jobs;

import com.rayyan.tesseract.gumloop.GumloopPayload;
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

	public static boolean startBuild(ServerPlayerEntity player, Selection selection, GumloopPayload.Response plan) {
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
		BuildJob job = new BuildJob(playerId, player.getServerWorld(), origin, plan.ops);
		ACTIVE_JOBS.put(playerId, job);
		player.sendMessage(Text.of("Build started (" + plan.ops.size() + " ops)."), false);
		return true;
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
				BuildJobManager.finish(entry.getKey());
			}
		}
	}

	private static final class BuildJob {
		private final UUID playerId;
		private final ServerWorld world;
		private final BlockPos origin;
		private final List<GumloopPayload.BlockOp> ops;
		private int index;
		private int placed;
		private long lastProgressAt;

		private BuildJob(UUID playerId, ServerWorld world, BlockPos origin, List<GumloopPayload.BlockOp> ops) {
			this.playerId = playerId;
			this.world = world;
			this.origin = origin;
			this.ops = ops;
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
				GumloopPayload.BlockOp op = ops.get(index);
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
				player.sendMessage(Text.of("Build complete: " + placed + " blocks."), false);
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
