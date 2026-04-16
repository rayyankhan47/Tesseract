package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.paste.BuildPlan;
import com.rayyan.tesseract.jobs.BuildJobManager;
import com.rayyan.tesseract.jobs.BuildQueueManager;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the five-stage build pipeline state machine.
 *
 * All state mutations happen on the Minecraft server thread. Async agent
 * completions call back via {@link #onServerThread}, which uses the player's
 * server reference for in-game builds and {@link #currentServer} for web builds.
 *
 * Usage (in-game):
 *   Orchestrator.getInstance().run(player, buildSelection, contextSelection, prompt, null, null);
 *
 * Usage (web):
 *   String planJson = Orchestrator.getInstance().runWebBuild(prompt, null, null);
 */
public final class Orchestrator {

    private static final Orchestrator INSTANCE = new Orchestrator();
    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.orchestrator");
    private static final long BUILD_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final UUID WEB_BUILD_ID = UUID.nameUUIDFromBytes("web".getBytes());
    private static final Gson GSON = new Gson();

    /** Active builds keyed by player UUID (or WEB_BUILD_ID for web builds). */
    private final Map<UUID, BuildState> activeBuilds = new ConcurrentHashMap<>();

    /** Lazily initialised; missing GEMINI_API_KEY must not crash startup. */
    private GeminiClient gemini;

    /**
     * Set each tick by {@link #tick(MinecraftServer)} — used to re-enter the server
     * thread for web builds that have no player reference.
     */
    private volatile MinecraftServer currentServer;

    private Orchestrator() {}

    public static Orchestrator getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /**
     * Starts a new in-game build pipeline for the given player.
     *
     * @param contextSelection nullable — scanned context region
     * @param imageBytes       nullable — reference image bytes
     * @param imageMimeType    nullable — MIME type of imageBytes
     */
    public void run(ServerPlayerEntity player,
                    Selection buildSelection,
                    @SuppressWarnings("unused") Selection contextSelection,
                    String prompt,
                    byte[] imageBytes,
                    String imageMimeType) {
        UUID playerId = player.getUuid();
        BuildState state = new BuildState(playerId, player, prompt, imageBytes, imageMimeType, buildSelection);
        activeBuilds.put(playerId, state);

        emit(state, "Orchestrator", "Starting: \"" + prompt + "\"");
        AgentProgressManager.start(player, "interpreting");
        transition(state, OrchestratorState.INTERPRETING);
    }

    /**
     * Starts a web-triggered build (no real player or world).
     * Blocks until the pipeline completes (or times out after 120 s).
     *
     * @return serialised plan JSON {@code {"meta":{...},"ops":[...]}} ready for plan_server.py
     */
    public String runWebBuild(String prompt, byte[] imageBytes, String imageMimeType)
            throws Exception {
        if (currentServer == null) {
            throw new IllegalStateException("Minecraft server not ready yet.");
        }
        if (activeBuilds.containsKey(WEB_BUILD_ID)) {
            throw new IllegalStateException("A web build is already in progress. Try again shortly.");
        }

        Selection syntheticSelection = new Selection();
        syntheticSelection.setCornerA(BlockPos.ORIGIN);
        syntheticSelection.setCornerB(new BlockPos(15, 11, 15)); // 16×12×16

        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        BuildState state = new BuildState(WEB_BUILD_ID, null, prompt, imageBytes, imageMimeType, syntheticSelection);
        state.isWebBuild = true;
        state.webBuildFuture = future;
        activeBuilds.put(WEB_BUILD_ID, state);
        BuildJobManager.start(WEB_BUILD_ID);

        currentServer.execute(() -> {
            emit(state, "Orchestrator", "Web build starting: \"" + prompt + "\"");
            transition(state, OrchestratorState.INTERPRETING);
        });

        return future.get(120, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    void transition(BuildState state, OrchestratorState next) {
        OrchestratorState.assertTransition(state.state, next);
        state.state = next;

        switch (next) {
            case INTERPRETING -> {
                emit(state, "Orchestrator", "→ INTERPRETING");
                AgentProgressManager.updateLabel(state.playerId, "Interpreting prompt…");
                InterpretationAgent.run(state, getGemini(),
                    () -> onServerThread(state, () -> {
                        boolean usedImage = state.referenceImageBytes != null;
                        String prefix = usedImage ? "Interpreted with visual reference: " : "Interpreted: ";
                        emit(state, "InterpretationAgent",
                                prefix + state.spec.type + " (" + state.spec.style + "), "
                                + state.spec.width + "×" + state.spec.height + "×" + state.spec.depth
                                + (state.spec.features != null && !state.spec.features.isEmpty()
                                        ? ", features: " + String.join(", ", state.spec.features) : ""));
                        synthesizeSelectionFromSpec(state);
                        transition(state, OrchestratorState.PLANNING);
                    }),
                    err -> onServerThread(state, () -> failBuild(state, err)));
            }
            case PLANNING -> {
                emit(state, "Orchestrator", "→ BLUEPRINTING (via BlueprintPlanningAgent)");
                AgentProgressManager.updateLabel(state.playerId, "Planning blueprint…");
                BlueprintPlanningAgent.run(state, getGemini(),
                    () -> onServerThread(state, () -> {
                        emit(state, "BlueprintPlanningAgent",
                                "Blueprint '" + state.blueprint.name + "': "
                                + state.blueprint.primitives.size() + " primitives, "
                                + state.compiledBlueprint.ops().size() + " blocks");
                        state.completedOps = new java.util.ArrayList<>(state.compiledBlueprint.ops());
                        transition(state, OrchestratorState.PLACING);
                    }),
                    err -> onServerThread(state, () -> failBuild(state, err)));
            }
            case GENERATING -> throw new UnsupportedOperationException(
                    "GENERATING: old per-component pipeline removed in Refactor 2. " +
                    "This path should not be reachable until the Step-9 state machine rewrite.");
            case CRITIQUING -> throw new UnsupportedOperationException(
                    "CRITIQUING: old programmatic critic removed in Refactor 2. " +
                    "This path should not be reachable until the Step-9 state machine rewrite.");
            case PLACING -> {
                if (state.completedOps.isEmpty()) {
                    emit(state, "Orchestrator", "No ops to place — blueprint produced zero blocks.");
                    transition(state, OrchestratorState.COMPLETE);
                    return;
                }
                emit(state, "Orchestrator", "→ PLACING (" + state.completedOps.size() + " blocks)");

                if (state.isWebBuild) {
                    emit(state, "Placement", "Web build: collected "
                            + state.completedOps.size() + " blocks for plan export.");
                    transition(state, OrchestratorState.COMPLETE);
                    return;
                }

                ServerWorld world = (ServerWorld) state.player.getWorld();
                AgentProgressManager.updateLabel(state.playerId, "Placing blocks…");
                BuildQueueManager.startComponentBuild(
                    state.playerId, world, state.placementOrigin, state.completedOps,
                    () -> onServerThread(state, () -> {
                        emit(state, "Placement", "Placed " + state.completedOps.size() + " blocks.");
                        transition(state, OrchestratorState.COMPLETE);
                    }));
            }
            case COMPLETE -> {
                emit(state, "Build", "Complete — " + state.completedOps.size() + " blocks.");
                AgentProgressManager.stop(state.playerId);
                BuildJobManager.finish(state.playerId);
                activeBuilds.remove(state.playerId);
                if (state.webBuildFuture != null) {
                    state.webBuildFuture.complete(buildPlanJson(state));
                }
            }
            case FAILED -> { /* failBuild() handles cleanup */ }
            case IDLE    -> { /* start state */ }
        }
    }

    void failBuild(BuildState state, String reason) {
        state.state = OrchestratorState.FAILED;
        emit(state, "Orchestrator", "Build failed: " + reason);
        AgentProgressManager.stop(state.playerId);
        BuildJobManager.finish(state.playerId);
        activeBuilds.remove(state.playerId);
        if (state.webBuildFuture != null) {
            state.webBuildFuture.completeExceptionally(new RuntimeException("Build failed: " + reason));
        }
    }

    /**
     * Discards all active build state — called on server start so stale locks
     * from a previous world load (within the same game session) do not block new builds.
     * Any pending web build futures are completed exceptionally.
     */
    public void reset() {
        for (BuildState state : activeBuilds.values()) {
            AgentProgressManager.stop(state.playerId);
            if (state.webBuildFuture != null) {
                state.webBuildFuture.completeExceptionally(
                        new RuntimeException("Server restarted."));
            }
        }
        activeBuilds.clear();
    }

    public void cancelBuild(UUID playerId) {
        BuildState state = activeBuilds.get(playerId);
        if (state == null) return;
        state.state = OrchestratorState.FAILED;
        state.eventLog.add(BuildEvent.of("Orchestrator", "Build cancelled."));
        AgentProgressManager.stop(playerId);
        BuildJobManager.finish(playerId);
        activeBuilds.remove(playerId);
    }

    // -------------------------------------------------------------------------
    // Event forwarding
    // -------------------------------------------------------------------------

    void emit(BuildState state, String agentName, String message) {
        BuildEvent event = BuildEvent.of(agentName, message);
        state.eventLog.add(event);
        LOGGER.info("[{}] {}", agentName, message);
        ServerPlayerEntity player = state.player;
        if (player != null && player.networkHandler != null) {
            player.sendMessage(Text.of(event.toString()), false);
        }
    }

    // -------------------------------------------------------------------------
    // Tick — save server reference, clean up timed-out builds
    // -------------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        if (server == null) return;
        this.currentServer = server;
        AgentProgressManager.tick(server);

        long now = System.currentTimeMillis();
        activeBuilds.entrySet().removeIf(entry -> {
            BuildState state = entry.getValue();
            if (state.eventLog.isEmpty()) return false;
            long lastEventTime = state.eventLog.get(state.eventLog.size() - 1).timestamp();
            if (now - lastEventTime > BUILD_TIMEOUT_MS) {
                LOGGER.warn("Build for {} timed out in state {}. Cleaning up.",
                        entry.getKey(), state.state);
                BuildJobManager.finish(entry.getKey());
                AgentProgressManager.stop(entry.getKey());
                if (state.webBuildFuture != null) {
                    state.webBuildFuture.completeExceptionally(
                            new RuntimeException("Build timed out in state " + state.state));
                }
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Queues {@code action} on the Minecraft server thread.
     * Uses the player's server for in-game builds, {@link #currentServer} for web builds.
     */
    private void onServerThread(BuildState state, Runnable action) {
        MinecraftServer srv = (state.player != null && state.player.getServer() != null)
                ? state.player.getServer()
                : currentServer;
        if (srv != null) {
            srv.execute(action);
        } else {
            action.run(); // last-resort fallback — should not occur in normal usage
        }
    }

    /**
     * Replaces the 1×1×1 anchor selection with a real bounding box derived from
     * the BuildSpec dimensions, centered horizontally on the anchor point.
     * Y starts at the anchor block (builds upward).
     *
     * Called on the server thread immediately after InterpretationAgent completes.
     * No-op for web builds, which have their own synthetic selection.
     */
    private static void synthesizeSelectionFromSpec(BuildState state) {
        if (state.isWebBuild || state.buildSelection == null) return;
        BlockPos anchor = state.buildSelection.getMin(); // was cornerA == cornerB
        if (anchor == null) return;

        int w = Math.max(4, state.spec.width);
        int h = Math.max(4, state.spec.height);
        int d = Math.max(4, state.spec.depth);

        // Centre the footprint horizontally on the anchor; build upward from anchor.y
        int halfW = w / 2;
        int halfD = d / 2;
        BlockPos newMin = new BlockPos(anchor.getX() - halfW, anchor.getY(), anchor.getZ() - halfD);
        BlockPos newMax = new BlockPos(anchor.getX() + (w - 1 - halfW), anchor.getY() + h - 1, anchor.getZ() + (d - 1 - halfD));

        Selection synth = new Selection();
        synth.setCornerA(newMin);
        synth.setCornerB(newMax);
        state.buildSelection = synth;
        state.placementOrigin = newMin;

        LOGGER.info("Synthesised selection {}×{}×{} at origin ({},{},{})",
                w, h, d, newMin.getX(), newMin.getY(), newMin.getZ());
    }

    private GeminiClient getGemini() {
        if (gemini == null) {
            gemini = GeminiClient.fromEnv();
        }
        return gemini;
    }

    /** Serialises the completed build as a { meta, ops } JSON string for the web dashboard. */
    private static String buildPlanJson(BuildState state) {
        BuildPlan plan = new BuildPlan();
        plan.ops = new ArrayList<>(state.completedOps);
        plan.meta = new BuildPlan.Meta();
        plan.meta.blockCount = state.completedOps.size();
        plan.meta.theme = state.spec != null ? state.spec.type : "web_build";
        return GSON.toJson(plan);
    }
}
