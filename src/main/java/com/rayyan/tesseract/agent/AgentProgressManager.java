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
 * Active from INTERPRETING through PLACING.
 * On COMPLETE: briefly flashes green before hiding (2 s).
 * On FAILED / cancellation / world switch: hidden immediately via {@link #stop}.
 *
 * Triangle-wave animation: oscillates between 10 % and 90 % every ~2.5 s,
 * giving an "indeterminate progress" feel (we have no real % from the LLM).
 */
public final class AgentProgressManager {

    /** How long the green "complete" flash lasts before the bar disappears. */
    private static final long FLASH_DURATION_MS = 2_000L;

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

    /** Remove and hide the bar for the given player immediately. */
    public static void stop(UUID playerId) {
        if (playerId == null) return;
        ActiveBar existing = BARS.remove(playerId);
        if (existing != null) {
            existing.bar.clearPlayers();
        }
    }

    /**
     * Briefly flashes the bar green at 100 % with the label "Build complete!"
     * then hides it after {@value #FLASH_DURATION_MS} ms.
     * The removal happens on the next {@link #tick} call after the flash expires.
     */
    public static void flashComplete(UUID playerId) {
        if (playerId == null) return;
        ActiveBar active = BARS.get(playerId);
        if (active == null) return;
        active.bar.setColor(BossBar.Color.GREEN);
        active.bar.setPercent(1.0f);
        active.bar.setName(Text.of("Tesseract: Build complete!"));
        active.flashUntilMs = System.currentTimeMillis() + FLASH_DURATION_MS;
    }

    /**
     * Called every server tick from {@code Orchestrator.tick()}.
     * Advances the triangle-wave animation; removes bars whose flash has expired.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        BARS.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            ActiveBar active = entry.getValue();
            if (active == null) return true;

            // Expire the green flash once its window closes
            if (active.flashUntilMs > 0 && now >= active.flashUntilMs) {
                active.bar.clearPlayers();
                return true;
            }
            // While flashing, leave the bar as-is (green, 100 %, "Build complete!")
            if (active.flashUntilMs > 0) return false;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
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
        /** When > 0, the bar is in "flash complete" mode and will be removed after this time. */
        volatile long flashUntilMs = 0;

        ActiveBar(ServerBossBar bar, long startedAtMs) {
            this.bar = bar;
            this.startedAtMs = startedAtMs;
        }
    }
}
