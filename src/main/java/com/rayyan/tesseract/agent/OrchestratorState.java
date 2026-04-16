package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's build pipeline state machine.
 *
 * <h3>Legal transition set</h3>
 * <pre>
 * IDLE              → INTERPRETING
 * INTERPRETING      → BLUEPRINTING
 * BLUEPRINTING      → COMPILING
 * COMPILING         → RENDERING
 * COMPILING         → FAILED          (compile error)
 * RENDERING         → CRITIQUING_VISUAL
 * RENDERING         → DETAILING       (single-iteration fast path / iterate=false)
 * CRITIQUING_VISUAL → PATCHING        (not satisfied, iterations remaining)
 * CRITIQUING_VISUAL → DETAILING       (satisfied | max iterations | budget exceeded)
 * PATCHING          → COMPILING       (loop back)
 * DETAILING         → PLACING
 * PLACING           → COMPLETE
 * any               → FAILED
 * </pre>
 */
public enum OrchestratorState {
    IDLE,
    INTERPRETING,
    /** Calls {@code BlueprintPlanningAgent}; renamed from {@code PLANNING} in Step 9. */
    BLUEPRINTING,
    /** Runs {@code BlueprintCompiler} deterministically on the server thread. */
    COMPILING,
    /** Runs {@code IsoRenderer} deterministically on the server thread. */
    RENDERING,
    /** Calls {@code VisualCriticAgent} (async Gemini Vision). */
    CRITIQUING_VISUAL,
    /** Applies {@code BlueprintPatcher} patches; immediately transitions back to {@code COMPILING}. */
    PATCHING,
    /** {@code DetailAgent} decoration pass. */
    DETAILING,
    PLACING,
    COMPLETE,
    FAILED;

    /**
     * Throws {@link IllegalStateException} if the {@code from → to} transition
     * is not in the allowed set. Any state may always transition to {@code FAILED}.
     */
    public static void assertTransition(OrchestratorState from, OrchestratorState to) {
        if (to == FAILED) return;
        boolean allowed = switch (from) {
            case IDLE              -> to == INTERPRETING;
            case INTERPRETING      -> to == BLUEPRINTING;
            case BLUEPRINTING      -> to == COMPILING;
            case COMPILING         -> to == RENDERING;
            case RENDERING         -> to == CRITIQUING_VISUAL || to == DETAILING;
            case CRITIQUING_VISUAL -> to == PATCHING || to == DETAILING;
            case PATCHING          -> to == COMPILING;
            case DETAILING         -> to == PLACING;
            case PLACING           -> to == COMPLETE;
            case COMPLETE, FAILED  -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "Illegal Orchestrator state transition: " + from + " → " + to);
        }
    }
}
