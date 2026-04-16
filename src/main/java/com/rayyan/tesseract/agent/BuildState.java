package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.blueprint.CompiledBlueprint;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared context object for a single player's in-progress build.
 *
 * All agents read from and write to this object. No agent calls another
 * agent directly — all communication is through BuildState.
 *
 * Thread safety: all field mutations must happen on the Minecraft server thread
 * (preserve the existing whenComplete → player.getServer().execute() pattern).
 * The one exception is eventLog, which uses CopyOnWriteArrayList so it can be
 * safely read from any thread for logging.
 */
public final class BuildState {

    // ---- Identity ----
    UUID playerId;
    /** Nullable — may be null for web-triggered builds or after player disconnect. */
    ServerPlayerEntity player;
    String originalPrompt;

    // ---- Optional reference image (nullable) ----
    byte[] referenceImageBytes;
    String referenceImageMimeType;

    // ---- Agent outputs (written once, then read-only) ----
    BuildSpec spec;                     // set by InterpretationAgent

    // ---- Blueprint DSL pipeline (Refactor 2) ----
    /** Current best Blueprint (updated on each critic patch). Set by BlueprintPlanningAgent. */
    Blueprint blueprint;
    /** Output of the most recent BlueprintCompiler.compile() call. */
    CompiledBlueprint compiledBlueprint;
    /** Number of visual critic passes completed so far. */
    int iterationCount;
    /** Cached isometric render PNG (for critic calls and optional debug export). */
    byte[] lastRenderPng;
    /** Wall-clock ms when the iteration loop started; used for budget enforcement. */
    long iterationStartMs;

    // ---- Placement accumulator ----
    List<BlockOp> completedOps;

    // ---- Spatial context ----
    BlockPos placementOrigin;
    Selection buildSelection;

    // ---- State machine ----
    OrchestratorState state;

    /**
     * Timeline entries appended each time the state machine transitions.
     * Each entry is {@code "STATE_NAME:entryTimeMs"}.
     * Used to build the one-line timeline log at COMPLETE (Step 9.3.3).
     */
    final java.util.List<String> timeline = new java.util.ArrayList<>();
    /** Wall-clock ms when the current state was entered. */
    long stateEnteredAtMs;

    // ---- Event log (CopyOnWriteArrayList — safe to read off server thread) ----
    List<BuildEvent> eventLog;

    // ---- Web build support ----
    /** True for builds triggered via the embedded HTTP server (no real player / world). */
    boolean isWebBuild;
    /**
     * For web builds: completed with the serialised plan JSON once the pipeline
     * reaches COMPLETE, or completed exceptionally on FAILED.
     */
    java.util.concurrent.CompletableFuture<String> webBuildFuture;

    BuildState(UUID playerId, ServerPlayerEntity player, String prompt,
               byte[] imageBytes, String imageMimeType,
               Selection selection) {
        this.playerId = playerId;
        this.player = player;
        this.originalPrompt = prompt;
        this.referenceImageBytes = imageBytes;
        this.referenceImageMimeType = imageMimeType;
        this.buildSelection = selection;
        this.placementOrigin = (selection != null && selection.isComplete())
                ? selection.getMin()
                : BlockPos.ORIGIN;
        this.state = OrchestratorState.IDLE;
        this.stateEnteredAtMs = System.currentTimeMillis();
        this.completedOps = new ArrayList<>();
        this.eventLog = new CopyOnWriteArrayList<>();
        this.iterationCount = 0;
    }
}
