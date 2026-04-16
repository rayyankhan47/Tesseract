package com.rayyan.tesseract.agent;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows an animated purple boss bar while the Orchestrator pipeline is running.
 *
 * Active during INTERPRETING, PLANNING, and GENERATING states.
 * Hidden automatically on COMPLETE or FAILED.
 *
 * Triangle-wave animation: oscillates between 10 % and 90 % every ~2.5 s,
 * giving an "indeterminate progress" feel (we have no real % from the LLM).
 */
public final class AgentProgressManager {

    private static final Map<UUID, ActiveBar> BARS = new ConcurrentHashMap<>();

    private AgentProgressManager() {}

    /** Start (or restart) the boss bar for the given player. */
    public static void start(ServerPlayerEntity player, String initialLabel) {
        if (player == null) return;
        UUID id = player.getUuid();
        stop(id); // remove any stale bar first

        ServerBossBar bar = new ServerBossBar(
                Text.of("Tesseract: " + initialLabel),
                BossBar.Color.PURPLE,
                BossBar.Style.PROGRESS
        );
        bar.addPlayer(player);
        bar.setPercent(0.1f);
        BARS.put(id, new ActiveBar(bar, System.currentTimeMillis()));
    }

    /** Update the bar title without restarting the animation. */
    public static void updateLabel(UUID playerId, String label) {
        ActiveBar active = BARS.get(playerId);
        if (active != null) {
            active.bar.setName(Text.of("Tesseract: " + label));
        }
    }

    /** Remove and hide the bar for the given player. */
    public static void stop(UUID playerId) {
        if (playerId == null) return;
        ActiveBar existing = BARS.remove(playerId);
        if (existing != null) {
            existing.bar.clearPlayers();
        }
    }

    /**
     * Called every server tick from {@code Orchestrator.tick()}.
     * Advances the triangle-wave animation for all active bars.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        BARS.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            ActiveBar active = entry.getValue();
            if (active == null) return true;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                // Player went offline — clean up silently.
                active.bar.clearPlayers();
                return true;
            }

            // Triangle wave: 10 %–90 % over 2.5 s
            float t = (now - active.startedAtMs) / 2500.0f;
            float phase = t - (float) Math.floor(t); // [0, 1)
            float tri = phase < 0.5f ? (phase * 2.0f) : (2.0f - phase * 2.0f); // [0, 1]
            active.bar.setPercent(0.1f + tri * 0.8f);
            return false;
        });
    }

    private static final class ActiveBar {
        final ServerBossBar bar;
        final long startedAtMs;

        ActiveBar(ServerBossBar bar, long startedAtMs) {
            this.bar = bar;
            this.startedAtMs = startedAtMs;
        }
    }
}
