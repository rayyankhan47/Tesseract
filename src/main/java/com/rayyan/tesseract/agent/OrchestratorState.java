package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's build pipeline state machine.
 *
 * Legal transitions (enforced in full by Orchestrator once assertTransition is added in Step 3):
 *   IDLE → INTERPRETING
 *   INTERPRETING → PLANNING
 *   PLANNING → GENERATING
 *   GENERATING → CRITIQUING
 *   CRITIQUING → PLACING
 *   CRITIQUING → GENERATING  (retry)
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
    FAILED
}
