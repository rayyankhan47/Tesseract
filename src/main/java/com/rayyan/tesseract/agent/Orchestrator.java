package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.jobs.BuildJobManager;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the five-stage build pipeline state machine.
 *
 * All state mutations happen on the Minecraft server thread. Async agent
 * completions call back via {@code player.getServer().execute(() -> ...)},
 * exactly matching the existing pattern in PlanPasteClient.
 *
 * Usage:
 *   Orchestrator.getInstance().run(player, buildSelection, contextSelection, prompt, null, null);
 */
public final class Orchestrator {

    private static final Orchestrator INSTANCE = new Orchestrator();
    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.orchestrator");
    private static final long BUILD_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes

    /** Active builds keyed by player UUID. */
    private final Map<UUID, BuildState> activeBuilds = new ConcurrentHashMap<>();

    /** Lazily initialised on first run() call so missing GEMINI_API_KEY doesn't crash startup. */
    private GeminiClient gemini;

    private Orchestrator() {}

    public static Orchestrator getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Starts a new build pipeline for the given player.
     *
     * @param contextSelection nullable — scanned context region (passed to agents for reference)
     * @param imageBytes       nullable — reference image for multimodal interpretation
     * @param imageMimeType    nullable — MIME type of imageBytes (e.g. "image/png")
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

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    /**
     * Validates the transition, advances state, emits an event, then dispatches
     * to the correct agent. Always called on the Minecraft server thread.
     */
    void transition(BuildState state, OrchestratorState next) {
        OrchestratorState.assertTransition(state.state, next);
        state.state = next;

        switch (next) {
            case INTERPRETING -> {
                emit(state, "Orchestrator", "→ INTERPRETING");
                AgentProgressManager.updateLabel(state.playerId, "Interpreting prompt…");
                InterpretationAgent.run(state, getGemini(),
                    () -> state.player.getServer().execute(() -> {
                        emit(state, "InterpretationAgent",
                                "Interpreted: " + state.spec.type + " (" + state.spec.style + "), "
                                + state.spec.width + "×" + state.spec.height + "×" + state.spec.depth
                                + (state.spec.features != null && !state.spec.features.isEmpty()
                                        ? ", features: " + String.join(", ", state.spec.features) : ""));
                        transition(state, OrchestratorState.PLANNING);
                    }),
                    err -> state.player.getServer().execute(() -> failBuild(state, err)));
            }
            case PLANNING -> {
                emit(state, "Orchestrator", "→ PLANNING");
                AgentProgressManager.updateLabel(state.playerId, "Planning structure…");
                PlanningAgent.run(state, getGemini(),
                    () -> state.player.getServer().execute(() -> {
                        emit(state, "PlanningAgent",
                                "Plan: " + state.componentPlan.stream()
                                        .map(c -> c.name).reduce((a, b) -> a + " → " + b).orElse("(empty)")
                                + " (" + state.componentPlan.size() + " components)");
                        state.currentComponentIndex = 0;
                        transition(state, OrchestratorState.GENERATING);
                    }),
                    err -> state.player.getServer().execute(() -> failBuild(state, err)));
            }
            case GENERATING -> {
                ComponentPlan comp = currentComponent(state);
                String label = comp != null
                        ? "Generating " + (state.currentComponentIndex + 1) + "/"
                          + state.componentPlan.size() + ": " + comp.name + "…"
                        : "Generating…";
                emit(state, "Generation", "Generating component "
                        + (state.currentComponentIndex + 1)
                        + (state.componentPlan != null ? "/" + state.componentPlan.size() : "")
                        + (comp != null ? ": " + comp.name : ""));
                AgentProgressManager.updateLabel(state.playerId, label);
                int retryAttempt = comp != null ? comp.retryCount : 0;
                String priorReason = comp != null ? comp.lastFailureReason : null;
                GenerationAgent.runComponent(state, getGemini(), retryAttempt, priorReason,
                    () -> state.player.getServer().execute(() ->
                            transition(state, OrchestratorState.CRITIQUING)),
                    (reason, shouldRetry) -> state.player.getServer().execute(() -> {
                        emit(state, "Critic", "Component failed: " + reason);
                        if (shouldRetry) {
                            transition(state, OrchestratorState.GENERATING);
                        } else {
                            // Max retries reached — component already added to failedComponentIds.
                            // Advance to the next component (or COMPLETE if all done).
                            advanceOrComplete(state);
                        }
                    }));
            }
            case CRITIQUING -> {
                // CriticAgent validation has already passed inside GenerationAgent before
                // onCriticPass was called. This state is a logical marker — transition
                // immediately to PLACING.
                ComponentPlan approved = currentComponent(state);
                emit(state, "Critic", "Component '" + (approved != null ? approved.name : "?") + "' passed"
                        + (approved != null && approved.pendingOps != null
                                ? " (" + approved.pendingOps.size() + " blocks)" : "") + ".");
                transition(state, OrchestratorState.PLACING);
            }
            case PLACING -> {
                emit(state, "Orchestrator", "→ PLACING");
                // TODO(Step 8): replace stub with PlacementAgent.placeComponent(state, world, ops, comp,
                //     () -> state.player.getServer().execute(() -> advanceOrComplete(state)),
                //     err -> state.player.getServer().execute(() -> failBuild(state, err)));
                failBuild(state, "PlacementAgent not yet implemented (Step 8).");
            }
            case COMPLETE -> {
                int totalBlocks = state.completedOps.size();
                int totalComps  = state.componentPlan != null ? state.componentPlan.size() : 0;
                int skipped     = state.failedComponentIds.size();
                emit(state, "Build", "Complete — " + totalBlocks + " blocks across "
                        + totalComps + " components" + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ".");
                AgentProgressManager.stop(state.playerId);
                BuildJobManager.finish(state.playerId);
                activeBuilds.remove(state.playerId);
            }
            case FAILED -> {
                // failBuild() handles cleanup; this case is unreachable via normal flow
                // but included for switch exhaustiveness.
            }
            case IDLE -> { /* start state — no action */ }
        }
    }

    /**
     * Advances to the next component or transitions to COMPLETE when all components are done.
     * Called by PlacementAgent after a successful component placement (Step 8).
     */
    void advanceOrComplete(BuildState state) {
        state.currentComponentIndex++;
        if (state.componentPlan != null && state.currentComponentIndex < state.componentPlan.size()) {
            transition(state, OrchestratorState.GENERATING);
        } else {
            transition(state, OrchestratorState.COMPLETE);
        }
    }

    void failBuild(BuildState state, String reason) {
        state.state = OrchestratorState.FAILED;
        emit(state, "Orchestrator", "Build failed: " + reason);
        AgentProgressManager.stop(state.playerId);
        BuildJobManager.finish(state.playerId);
        activeBuilds.remove(state.playerId);
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
    // Event forwarding (3.3)
    // -------------------------------------------------------------------------

    /**
     * Appends an event to the log and, if the player is still online, sends it
     * to their chat as {@code [agentName] message}.
     */
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
    // Tick — clean up builds stuck > BUILD_TIMEOUT_MS
    // -------------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        if (server == null) return;
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
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GeminiClient getGemini() {
        if (gemini == null) {
            gemini = GeminiClient.fromEnv();
        }
        return gemini;
    }

    private static ComponentPlan currentComponent(BuildState state) {
        if (state.componentPlan == null) return null;
        if (state.currentComponentIndex < 0 || state.currentComponentIndex >= state.componentPlan.size()) return null;
        return state.componentPlan.get(state.currentComponentIndex);
    }
}
