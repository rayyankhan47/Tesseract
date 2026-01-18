package com.rayyan.tesseract.gumloop;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows an indeterminate (animated) bossbar while we wait for Gumloop/LLM output.
 *
 * We do NOT have real % progress from Gumloop, so we intentionally animate this as a "working…" indicator.
 */
public final class GumloopProgressManager {
	private static final Map<UUID, DraftingBar> BARS = new ConcurrentHashMap<>();

	private GumloopProgressManager() {}

	public static void startDrafting(ServerPlayerEntity player, String requestId) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUuid();

		// Replace any existing bar (shouldn't happen in MVP because we lock builds, but safe).
		stopDrafting(playerId);

		ServerBossBar bar = new ServerBossBar(
			Text.of("Tesseract drafting… (" + requestId + ")"),
			BossBar.Color.PURPLE,
			BossBar.Style.PROGRESS
		);
		bar.addPlayer(player);
		bar.setPercent(0.1f);
		BARS.put(playerId, new DraftingBar(bar, System.currentTimeMillis(), requestId));
	}

	public static void stopDrafting(UUID playerId) {
		if (playerId == null) {
			return;
		}
		DraftingBar existing = BARS.remove(playerId);
		if (existing != null) {
			existing.bar.clearPlayers();
		}
	}

	public static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, DraftingBar> entry : BARS.entrySet()) {
			UUID playerId = entry.getKey();
			DraftingBar drafting = entry.getValue();
			if (drafting == null) {
				BARS.remove(playerId);
				continue;
			}

			ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
			if (player == null) {
				stopDrafting(playerId);
				continue;
			}

			// Triangle wave between 10% and 90% every ~2.5 seconds.
			float t = (now - drafting.startedAtMs) / 2500.0f;
			float phase = t - (float) Math.floor(t); // [0,1)
			float tri = phase < 0.5f ? (phase * 2.0f) : (2.0f - phase * 2.0f); // [0,1]
			float percent = 0.1f + tri * 0.8f;
			drafting.bar.setPercent(percent);
		}
	}

	private static final class DraftingBar {
		private final ServerBossBar bar;
		private final long startedAtMs;
		@SuppressWarnings("unused")
		private final String requestId;

		private DraftingBar(ServerBossBar bar, long startedAtMs, String requestId) {
			this.bar = bar;
			this.startedAtMs = startedAtMs;
			this.requestId = requestId;
		}
	}
}

