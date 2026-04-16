package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.gumloop.GumloopPayload;
import com.rayyan.tesseract.selection.Selection;
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
    String originalPrompt;

    // ---- Optional reference image (nullable) ----
    byte[] referenceImageBytes;
    String referenceImageMimeType;

    // ---- Agent outputs (written once, then read-only) ----
    BuildSpec spec;                     // set by InterpretationAgent
    List<ComponentPlan> componentPlan;  // set by PlanningAgent

    // ---- Generation loop state ----
    int currentComponentIndex;

    // ---- Placement accumulator ----
    List<GumloopPayload.BlockOp> completedOps;
    List<String> failedComponentIds;

    // ---- Spatial context ----
    BlockPos placementOrigin;
    Selection buildSelection;

    // ---- State machine ----
    OrchestratorState state;

    // ---- Event log (CopyOnWriteArrayList — safe to read off server thread) ----
    List<BuildEvent> eventLog;

    BuildState(UUID playerId, String prompt,
               byte[] imageBytes, String imageMimeType,
               Selection selection) {
        this.playerId = playerId;
        this.originalPrompt = prompt;
        this.referenceImageBytes = imageBytes;
        this.referenceImageMimeType = imageMimeType;
        this.buildSelection = selection;
        this.placementOrigin = (selection != null && selection.isComplete())
                ? selection.getMin()
                : BlockPos.ORIGIN;
        this.state = OrchestratorState.IDLE;
        this.completedOps = new ArrayList<>();
        this.failedComponentIds = new ArrayList<>();
        this.eventLog = new CopyOnWriteArrayList<>();
        this.currentComponentIndex = 0;
    }
}
