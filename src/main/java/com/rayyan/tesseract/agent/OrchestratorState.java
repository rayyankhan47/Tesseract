package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's build pipeline state machine.
 *
 * Legal transitions (enforced in full by Orchestrator once assertTransition is added in Step 3):
 *   IDLE → INTERPRETING
 *   INTERPRETING → PLANNING
 *   PLANNING → GENERATING
 *   GENERATING → CRITIQUING  (critic passed)
 *   GENERATING → GENERATING  (critic failed, retry same component)
 *   CRITIQUING → PLACING
 *   PLACING → GENERATING     (next component)
 *   PLACING → COMPLETE
 *   any → FAILED
 */
public enum OrchestratorState {
    IDLE,
    INTERPRETING,
    PLANNING,
    GENERATING,
    CRITIQUING,
    PLACING,
    COMPLETE,
    FAILED;

    /**
     * Throws {@link IllegalStateException} if the {@code from → to} transition is not in the
     * allowed set. Any state may transition to {@code FAILED}.
     */
    public static void assertTransition(OrchestratorState from, OrchestratorState to) {
        if (to == FAILED) return; // any → FAILED is always allowed
        boolean allowed = switch (from) {
            case IDLE        -> to == INTERPRETING;
            case INTERPRETING -> to == PLANNING;
            case PLANNING    -> to == GENERATING;
            case GENERATING  -> to == CRITIQUING || to == GENERATING; // GENERATING = retry
            case CRITIQUING  -> to == PLACING;
            case PLACING     -> to == GENERATING || to == COMPLETE; // GENERATING = next component
            case COMPLETE, FAILED -> false; // terminal states — no exit
        };
        if (!allowed) {
            throw new IllegalStateException(
                "Illegal Orchestrator state transition: " + from + " → " + to);
        }
    }
}
