package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's build pipeline state machine.
 *
 * Refactor-2 in-progress transition set (Steps 4–8 bridge):
 *   IDLE → INTERPRETING
 *   INTERPRETING → PLANNING
 *   PLANNING → PLACING          ← temporary bridge; replaced by BLUEPRINTING chain in Step 9
 *   PLACING → COMPLETE
 *   any → FAILED
 *
 * Full target transition set (wired in Step 9):
 *   IDLE → INTERPRETING → BLUEPRINTING → COMPILING → RENDERING →
 *   CRITIQUING_VISUAL → PATCHING (loop) → DETAILING → PLACING → COMPLETE
 */
public enum OrchestratorState {
    IDLE,
    INTERPRETING,
    PLANNING,
    /**
     * @deprecated Old per-component generation path removed in Refactor 2.
     *             Retained as enum value until the full Step-9 state machine rewrite.
     */
    @Deprecated GENERATING,
    /**
     * @deprecated Old programmatic critic path removed in Refactor 2.
     *             Retained as enum value until the full Step-9 state machine rewrite.
     */
    @Deprecated CRITIQUING,
    PLACING,
    COMPLETE,
    FAILED;

    /**
     * Throws {@link IllegalStateException} if the {@code from → to} transition is not in the
     * allowed set. Any state may transition to {@code FAILED}.
     */
    @SuppressWarnings("deprecation")
    public static void assertTransition(OrchestratorState from, OrchestratorState to) {
        if (to == FAILED) return;
        boolean allowed = switch (from) {
            case IDLE         -> to == INTERPRETING;
            case INTERPRETING -> to == PLANNING;
            // PLACING: temporary bridge while Steps 5–9 are being added.
            // Step 9 replaces this with PLANNING → BLUEPRINTING.
            case PLANNING     -> to == PLACING;
            // Dead paths — Orchestrator stubs throw UnsupportedOperationException before reaching here.
            case GENERATING   -> to == CRITIQUING || to == GENERATING;
            case CRITIQUING   -> to == PLACING;
            case PLACING      -> to == COMPLETE;
            case COMPLETE, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                "Illegal Orchestrator state transition: " + from + " → " + to);
        }
    }
}
