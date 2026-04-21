package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.rayyan.tesseract.api.EmbeddingClient;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.ImagenClient;
import com.rayyan.tesseract.paste.BuildPlan;
import com.rayyan.tesseract.jobs.BuildJobManager;
import com.rayyan.tesseract.jobs.BuildQueueManager;
import com.rayyan.tesseract.selection.Selection;
import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.texture.FractalTexturePipeline;
import com.rayyan.tesseract.toolbox.ToolboxExtensionStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the Refactor 3 build pipeline state machine (linear phases 0→9, L4 inner loop).
 *
 * <p>All state mutations happen on the Minecraft server thread. Async agent
 * completions call back via {@link #onServerThread}, which uses the player's
 * server reference for in-game builds and {@link #currentServer} for web builds.
 */
public final class Orchestrator {

    private static final Orchestrator INSTANCE = new Orchestrator();
    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.orchestrator");
    private static final long BUILD_TIMEOUT_MS = 5 * 60 * 1_000L;
    private static final UUID WEB_BUILD_ID = UUID.nameUUIDFromBytes("web".getBytes());
    private static final Gson GSON = new Gson();

    /** §10.3.2 — global LLM spend cap (USD) for the whole build. */
    private static final double GLOBAL_BUDGET_USD = 2.0;

    /** Active builds keyed by player UUID (or WEB_BUILD_ID for web builds). */
    private final Map<UUID, BuildState> activeBuilds = new ConcurrentHashMap<>();

    private GeminiClient gemini;
    private ImagenClient imagen;
    private EmbeddingClient embedding;

    /**
     * Set each tick by {@link #tick(MinecraftServer)} — used to re-enter the server
     * thread for web builds that have no player reference.
     */
    private volatile MinecraftServer currentServer;

    private Orchestrator() {}

    public static Orchestrator getInstance() {
        return INSTANCE;
    }

    private GeminiClient getGemini() {
        if (gemini == null) {
            gemini = GeminiClient.fromEnv();
        }
        return gemini;
    }

    private ImagenClient getImagen() {
        if (imagen == null) {
            imagen = ImagenClient.fromEnv();
        }
        return imagen;
    }

    private EmbeddingClient getEmbedding() {
        if (embedding == null) {
            embedding = EmbeddingClient.fromEnv();
        }
        return embedding;
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /**
     * Starts a new in-game build pipeline for the given player.
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
        AgentProgressManager.start(player, "concept");
        transition(state, OrchestratorState.CONCEPT_SYNTHESIZING);
    }

    /**
     * Starts a web-triggered build (no real player or world).
     * Blocks until the pipeline completes (or times out after 120 s).
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
        syntheticSelection.setCornerB(new BlockPos(15, 11, 15));

        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        BuildState state = new BuildState(WEB_BUILD_ID, null, prompt, imageBytes, imageMimeType, syntheticSelection);
        state.isWebBuild = true;
        state.webBuildFuture = future;
        activeBuilds.put(WEB_BUILD_ID, state);
        BuildJobManager.start(WEB_BUILD_ID);

        currentServer.execute(() -> {
            emit(state, "Orchestrator", "Web build starting: \"" + prompt + "\"");
            transition(state, OrchestratorState.CONCEPT_SYNTHESIZING);
        });

        return future.get(120, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    void transition(BuildState state, OrchestratorState next) {
        OrchestratorState.assertTransition(state.state, next);

        long now = System.currentTimeMillis();
        long durationMs = now - state.stateEnteredAtMs;
        state.timeline.add(state.state.name() + ":" + durationMs);
        state.stateEnteredAtMs = now;

        state.state = next;

        switch (next) {

            case CONCEPT_SYNTHESIZING -> {
                emit(state, "Orchestrator", "→ CONCEPT_SYNTHESIZING");
                AgentProgressManager.updateLabel(state.playerId, "Interpreting prompt…");
                if (state.spec == null) {
                    InterpretationAgent.run(state, getGemini(),
                            () -> onServerThread(state, () -> {
                                emitInterpreted(state);
                                synthesizeSelectionFromSpec(state);
                                transition(state, OrchestratorState.CONCEPT_SYNTHESIZING);
                            }),
                            err -> onServerThread(state, () -> failBuild(state, err)));
                    return;
                }
                if (budgetExceeded(state)) {
                    completeBudgetEarly(state);
                    return;
                }
                if (state.textOnlyFallback) {
                    ensureFallbackMass(state);
                    AgentProgressManager.updateLabel(state.playerId, "RAG retrieval…");
                    transition(state, OrchestratorState.RAG_QUERYING);
                    return;
                }
                AgentProgressManager.updateLabel(state.playerId, "Concept synthesis…");
                ConceptAgent.run(state, getImagen(), getGemini(),
                        () -> onServerThread(state, () -> {
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.MASS_EXTRACTING);
                        }),
                        err -> onServerThread(state, () -> {
                            enterTextOnlyFallback(state, err);
                            ensureFallbackMass(state);
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.RAG_QUERYING);
                        }));
            }

            case MASS_EXTRACTING -> {
                emit(state, "Orchestrator", "→ MASS_EXTRACTING");
                AgentProgressManager.updateLabel(state.playerId, "3D mass extraction…");
                if (budgetExceeded(state)) {
                    completeBudgetEarly(state);
                    return;
                }
                MassExtractionAgent.run(state, getGemini(),
                        () -> onServerThread(state, () -> {
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.RAG_QUERYING);
                        }),
                        err -> onServerThread(state, () -> {
                            enterTextOnlyFallback(state, err);
                            ensureFallbackMass(state);
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.RAG_QUERYING);
                        }));
            }

            case RAG_QUERYING -> {
                emit(state, "Orchestrator", "→ RAG_QUERYING");
                AgentProgressManager.updateLabel(state.playerId, "Architectural RAG…");
                RagAgent.run(state, getGemini(), getEmbedding())
                        .whenComplete((ok, ex) -> onServerThread(state, () -> {
                            if (ex != null) {
                                LOGGER.warn("RagAgent: {}", ex.getMessage());
                            }
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.L1_ARCHITECTING);
                        }));
            }

            case L1_ARCHITECTING -> {
                emit(state, "Orchestrator", "→ L1_ARCHITECTING");
                AgentProgressManager.updateLabel(state.playerId, "L1 architect…");
                L1ArchitectAgent.run(state, getGemini())
                        .whenComplete((plan, ex) -> onServerThread(state, () -> {
                            if (ex != null) {
                                failBuild(state, "L1: " + ex.getMessage());
                                return;
                            }
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.L2_DECOMPOSING);
                        }));
            }

            case L2_DECOMPOSING -> {
                emit(state, "Orchestrator", "→ L2_DECOMPOSING");
                AgentProgressManager.updateLabel(state.playerId, "L2 zones…");
                L2DecomposerAgent.run(state, getGemini())
                        .whenComplete((zones, ex) -> onServerThread(state, () -> {
                            if (ex != null) {
                                failBuild(state, "L2: " + ex.getMessage());
                                return;
                            }
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.L3_DESIGNING);
                        }));
            }

            case L3_DESIGNING -> {
                emit(state, "Orchestrator", "→ L3_DESIGNING");
                AgentProgressManager.updateLabel(state.playerId, "L3 elements…");
                L3ElementDesignerAgent.run(state, getGemini())
                        .whenComplete((elements, ex) -> onServerThread(state, () -> {
                            if (ex != null) {
                                failBuild(state, "L3: " + ex.getMessage());
                                return;
                            }
                            if (budgetExceeded(state)) {
                                completeBudgetEarly(state);
                                return;
                            }
                            transition(state, OrchestratorState.L4_ITERATING);
                        }));
            }

            case L4_ITERATING -> {
                emit(state, "Orchestrator", "→ L4_ITERATING");
                AgentProgressManager.updateLabel(state.playerId, "L4 geometry…");
                if (budgetExceeded(state)) {
                    completeBudgetEarly(state);
                    return;
                }
                try {
                    ToolboxExtensionStore ext = ToolboxExtensionStore.atDefaultPath().load();
                    ElementScheduler.runAll(state, getGemini(), ext);
                    if (state.compiledBlueprint == null && state.cumulativeBuild != null) {
                        state.compiledBlueprint = state.cumulativeBuild.toCompiledBlueprint();
                    }
                    reportDrift(state);
                    if (budgetExceeded(state)) {
                        completeBudgetEarly(state);
                        return;
                    }
                    transition(state, OrchestratorState.TEXTURING);
                } catch (Exception e) {
                    failBuild(state, "L4: " + e.getMessage());
                }
            }

            case TEXTURING -> {
                emit(state, "Orchestrator", "→ TEXTURING");
                AgentProgressManager.updateLabel(state.playerId, "Texture pass…");
                java.util.List<BlockOp> base = state.compiledBlueprint != null
                        ? new ArrayList<>(state.compiledBlueprint.ops())
                        : new ArrayList<>();
                String seed = String.valueOf(state.playerId)
                        + ":" + (state.originalPrompt == null ? "" : state.originalPrompt.hashCode());
                java.util.List<BlockOp> textured = FractalTexturePipeline.apply(state, base, seed);
                state.completedOps = new ArrayList<>(textured);
                emit(state, "FractalTexturePipeline", textured.size() + " ops after texture pass.");
                transition(state, OrchestratorState.PLACING);
            }

            case PLACING -> {
                if (state.completedOps == null || state.completedOps.isEmpty()) {
                    emit(state, "Orchestrator", "No ops to place — geometry produced zero blocks.");
                    transition(state, OrchestratorState.COMPLETE);
                    return;
                }
                emit(state, "Orchestrator",
                        "→ PLACING (" + state.completedOps.size() + " blocks)");

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

            case COMPLETE -> finalizeComplete(state);
            case FAILED -> { /* failBuild() handles cleanup */ }
            case IDLE -> { /* initial constructor only */ }
        }
    }

    private void emitInterpreted(BuildState state) {
        boolean usedImage = state.referenceImageBytes != null;
        String pfx = usedImage ? "Interpreted with visual reference: " : "Interpreted: ";
        emit(state, "InterpretationAgent",
                pfx + state.spec.type + " (" + state.spec.style + "), "
                        + state.spec.width + "×" + state.spec.height + "×" + state.spec.depth
                        + (state.spec.features != null && !state.spec.features.isEmpty()
                        ? ", features: " + String.join(", ", state.spec.features) : ""));
    }

    private static boolean budgetExceeded(BuildState state) {
        return state.costTracker().totalUsd() > GLOBAL_BUDGET_USD;
    }

    /**
     * §10.3.2 — ship early with chat copy; texture+place if L4 already produced ops.
     */
    private void completeBudgetEarly(BuildState state) {
        double spent = state.costTracker().totalUsd();
        emit(state, "Orchestrator", String.format(Locale.US,
                "$%.2f spent — global $%.2f budget exceeded; shipping early.",
                spent, GLOBAL_BUDGET_USD));

        boolean hasGeometry = state.compiledBlueprint != null
                && state.compiledBlueprint.ops() != null
                && !state.compiledBlueprint.ops().isEmpty();

        if (hasGeometry) {
            transition(state, OrchestratorState.TEXTURING);
        } else {
            transition(state, OrchestratorState.COMPLETE);
        }
    }

    private void finalizeComplete(BuildState state) {
        long completedAt = System.currentTimeMillis();
        state.timeline.add("COMPLETE:" + (completedAt - state.stateEnteredAtMs));

        emit(state, "Build", "Complete — " + state.completedOps.size() + " blocks, "
                + state.elementLocks.size() + " element lock(s).");
        if (state.costTracker.totalCalls() > 0) {
            emit(state, "Cost", state.costTracker.summaryLine());
            LOGGER.info("Cost breakdown:\n{}", state.costTracker.fullBreakdown());
        }
        if (!state.ragCitations.isEmpty()) {
            LOGGER.info("RAG citations: {}", String.join(", ", state.ragCitations));
        }
        LOGGER.info("Timeline: {}", buildTimeline(state));

        AgentProgressManager.flashComplete(state.playerId);
        BuildJobManager.finish(state.playerId);
        activeBuilds.remove(state.playerId);
        if (state.webBuildFuture != null) {
            state.webBuildFuture.complete(buildPlanJson(state));
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
     * §3.3.3 — when a vision-dependent phase fails catastrophically, flip text-only mode.
     */
    void enterTextOnlyFallback(BuildState state, String reason) {
        if (state.textOnlyFallback) return;
        state.textOnlyFallback = true;
        LOGGER.warn("PHASE_FALLBACK mode=text_only reason={}", reason);
        emit(state, "Orchestrator",
                "Visual pipeline unavailable — falling back to text-only planning (" + reason + ")");
    }

    private void ensureFallbackMass(BuildState state) {
        if (state.massSketch != null) return;
        if (state.spec == null) {
            LOGGER.warn("ensureFallbackMass: no BuildSpec — using default envelope");
        }
        state.massSketch = VoxelMass.syntheticFootprintFromSpec(state.spec);
        emit(state, "Orchestrator", "Synthetic 16³ silhouette for text-only path ("
                + state.massSketch.filledCount() + " voxels).");
    }

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

    private void onServerThread(BuildState state, Runnable action) {
        MinecraftServer srv = (state.player != null && state.player.getServer() != null)
                ? state.player.getServer()
                : currentServer;
        if (srv != null) {
            srv.execute(action);
        } else {
            action.run();
        }
    }

    private static void synthesizeSelectionFromSpec(BuildState state) {
        if (state.isWebBuild || state.buildSelection == null) return;
        BlockPos anchor = state.buildSelection.getMin();
        if (anchor == null) return;
        int w = Math.max(4, state.spec.width);
        int h = Math.max(4, state.spec.height);
        int d = Math.max(4, state.spec.depth);

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

    private void reportDrift(BuildState state) {
        if (state.massSketch == null || state.compiledBlueprint == null) return;
        Blueprint.Bounds bounds = state.blueprint != null ? state.blueprint.bounds : null;
        if (bounds == null && state.buildSelection != null && state.buildSelection.isComplete()) {
            bounds = VoxelMassRenderer.deriveBounds(state.buildSelection);
        }
        try {
            double drift = SilhouetteMetrics.drift(
                    state.compiledBlueprint.ops(),
                    state.massSketch,
                    bounds);
            state.lastSilhouetteDrift = drift;
            String tag = SilhouetteMetrics.classify(drift);
            String msg = String.format("Silhouette drift: %.1f%% (%s)", drift * 100.0, tag);
            if (SilhouetteMetrics.shouldRollback(drift)) {
                msg += " — DRIFT_ROLLBACK_REQUESTED";
            }
            emit(state, "SilhouetteMetrics", msg);
        } catch (Exception e) {
            LOGGER.warn("SilhouetteMetrics: failed to compute drift — {}", e.getMessage());
        }
    }

    private static String buildTimeline(BuildState state) {
        if (state.timeline.isEmpty()) return "IDLE → COMPLETE";
        StringBuilder sb = new StringBuilder();
        for (String entry : state.timeline) {
            int sep = entry.lastIndexOf(':');
            if (sep < 0) {
                sb.append(entry).append(" → ");
                continue;
            }
            String name = entry.substring(0, sep);
            long dms = Long.parseLong(entry.substring(sep + 1));
            sb.append(name).append("(").append(dms / 1000.0).append("s) → ");
        }
        if (sb.length() >= 4) sb.setLength(sb.length() - 4);
        return sb.toString();
    }

    private static String buildPlanJson(BuildState state) {
        BuildPlan plan = new BuildPlan();
        plan.ops = new ArrayList<>(state.completedOps);
        plan.meta = new BuildPlan.Meta();
        plan.meta.blockCount = state.completedOps.size();
        plan.meta.theme = state.spec != null ? state.spec.type : "web_build";
        return GSON.toJson(plan);
    }
}
