package com.rayyan.tesseract.agent;

/**
 * States of the Orchestrator's Refactor 3 build pipeline (Step 10).
 *
 * <h3>Legal transition set</h3>
 * <pre>
 * IDLE                 → CONCEPT_SYNTHESIZING
 * CONCEPT_SYNTHESIZING → MASS_EXTRACTING | RAG_QUERYING | COMPLETE | FAILED
 * MASS_EXTRACTING      → RAG_QUERYING | COMPLETE | FAILED
 * RAG_QUERYING         → L1_ARCHITECTING | COMPLETE | FAILED
 * L1_ARCHITECTING      → L2_DECOMPOSING | COMPLETE | FAILED
 * L2_DECOMPOSING       → L3_DESIGNING | COMPLETE | FAILED
 * L3_DESIGNING         → L4_ITERATING | COMPLETE | FAILED
 * L4_ITERATING         → TEXTURING | COMPLETE | FAILED
 * TEXTURING            → PLACING | FAILED
 * PLACING              → COMPLETE | FAILED
 * any                  → FAILED
 * </pre>
 *
 * <p>{@code RAG_QUERYING} is reachable directly from {@code CONCEPT_SYNTHESIZING}
 * when {@link BuildState#textOnlyFallback} skips visual mass extraction.
 * {@code COMPLETE} mid-pipeline is used only when the global $2.00 USD budget
 * is exceeded before further LLM work (ship whatever geometry exists).
 */
public enum OrchestratorState {
    IDLE,
    CONCEPT_SYNTHESIZING,
    MASS_EXTRACTING,
    RAG_QUERYING,
    L1_ARCHITECTING,
    L2_DECOMPOSING,
    L3_DESIGNING,
    /** Runs {@link ElementScheduler#runAll} (per-element L4 REPL). */
    L4_ITERATING,
    /** {@link com.rayyan.tesseract.texture.FractalTexturePipeline#apply} (no LLM). */
    TEXTURING,
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
            case IDLE -> to == CONCEPT_SYNTHESIZING;
            case CONCEPT_SYNTHESIZING -> to == MASS_EXTRACTING || to == RAG_QUERYING || to == COMPLETE;
            case MASS_EXTRACTING -> to == RAG_QUERYING || to == COMPLETE;
            case RAG_QUERYING -> to == L1_ARCHITECTING || to == COMPLETE;
            case L1_ARCHITECTING -> to == L2_DECOMPOSING || to == COMPLETE;
            case L2_DECOMPOSING -> to == L3_DESIGNING || to == COMPLETE;
            case L3_DESIGNING -> to == L4_ITERATING || to == COMPLETE;
            case L4_ITERATING -> to == TEXTURING || to == COMPLETE;
            case TEXTURING -> to == PLACING;
            case PLACING -> to == COMPLETE;
            case COMPLETE, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "Illegal Orchestrator state transition: " + from + " → " + to);
        }
    }
}
