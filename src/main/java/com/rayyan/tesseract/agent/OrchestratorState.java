package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's build pipeline state machine.
 *
 * <p><b>Current (Step 7) transition set:</b>
 * <pre>
 * IDLE → INTERPRETING
 * INTERPRETING → PLANNING
 * PLANNING    → COMPILING                 ← calls BlueprintPlanningAgent
 * COMPILING   → RENDERING | FAILED        ← deterministic; runs on server thread
 * RENDERING   → CRITIQUING_VISUAL         ← deterministic; runs on server thread
 *             → DETAILING                 ← single-iteration fast path
 * CRITIQUING_VISUAL → PATCHING            ← not satisfied & iters remaining
 *                   → DETAILING           ← satisfied | max iters | budget exceeded
 * PATCHING    → COMPILING                 ← blueprint updated; loop back
 * DETAILING   → PLACING                   ← Step 8 fills in DetailAgent; stub for now
 * PLACING     → COMPLETE
 * any         → FAILED
 * </pre>
 *
 * Step 9 will rename {@code PLANNING} → {@code BLUEPRINTING}, add an explicit
 * {@code BLUEPRINTING} state after {@code INTERPRETING}, remove the deprecated
 * {@code GENERATING} and {@code CRITIQUING} entries, and add the timeline log.
 */
public enum OrchestratorState {
    IDLE,
    INTERPRETING,
    /** Calls BlueprintPlanningAgent; renamed to BLUEPRINTING in Step 9. */
    PLANNING,
    /** Runs BlueprintCompiler deterministically on the server thread. */
    COMPILING,
    /** Runs IsoRenderer deterministically on the server thread. */
    RENDERING,
    /** Calls VisualCriticAgent (async Gemini Vision call). */
    CRITIQUING_VISUAL,
    /** Applies BlueprintPatcher patches; transitions back to COMPILING. */
    PATCHING,
    /** DetailAgent decoration pass (stub until Step 8). */
    DETAILING,
    PLACING,
    COMPLETE,
    FAILED,

    /**
     * @deprecated Old per-component generation path removed in Refactor 2.
     *             Removed in Step 9.
     */
    @Deprecated GENERATING,

    /**
     * @deprecated Old programmatic critic removed in Refactor 2.
     *             Removed in Step 9.
     */
    @Deprecated CRITIQUING;

    /**
     * Throws {@link IllegalStateException} if the {@code from → to} transition
     * is not in the allowed set. Any state may always transition to {@code FAILED}.
     */
    @SuppressWarnings("deprecation")
    public static void assertTransition(OrchestratorState from, OrchestratorState to) {
        if (to == FAILED) return;
        boolean allowed = switch (from) {
            case IDLE              -> to == INTERPRETING;
            case INTERPRETING      -> to == PLANNING;
            case PLANNING          -> to == COMPILING;
            case COMPILING         -> to == RENDERING;
            case RENDERING         -> to == CRITIQUING_VISUAL || to == DETAILING;
            case CRITIQUING_VISUAL -> to == PATCHING || to == DETAILING;
            case PATCHING          -> to == COMPILING;
            case DETAILING         -> to == PLACING;
            case PLACING           -> to == COMPLETE;
            case COMPLETE, FAILED  -> false;
            // Deprecated dead-end states — Orchestrator stubs throw before reaching here
            case GENERATING        -> to == CRITIQUING || to == GENERATING;
            case CRITIQUING        -> to == PLACING;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "Illegal Orchestrator state transition: " + from + " → " + to);
        }
    }
}
