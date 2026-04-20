package com.rayyan.tesseract.api;

/**
 * The complete set of LLM call sites in the v3 pipeline. Every
 * {@link GeminiClient#call(TaskKind, String, String, java.util.List, GeminiClient.Validator)}
 * invocation names one of these and lets {@link ModelRegistry} pick the model,
 * temperature, token cap, timeout, and failure-class chains.
 *
 * <p>Per REFACTOR_3 §3.1.1 — agents never hard-code model IDs.
 */
public enum TaskKind {
    /** Imagen concept prompt synthesis is not routed through Gemini — included for symmetry. */
    CONCEPT_SYNTHESIS,
    /** Gemini 2.5 Pro multimodal: concept image → 16³ voxel mass. */
    MASS_EXTRACTION,
    /** Auto-selects the primary concept from the four Imagen variations. */
    CONCEPT_SELECT,
    /** RAG step: turn prompt + concept caption into a retrieval query. */
    RAG_PLAN,
    /** L1 Architect: mass plan from prompt + concept + voxel + RAG. */
    L1_ARCHITECT,
    /** L2 Decomposer: zones per major mass. */
    L2_DECOMPOSE,
    /** L3 Element Designer: element specs per zone. */
    L3_ELEMENT,
    /** L4 REPL agent: writes sandbox scripts for each element. */
    L4_REPL,
    /** Inner-loop critic calls (silhouette, style, proportion, detail, reference-match). */
    CRITIC_INNER,
    /** Critic reconciler — merges critic opinions into a consolidated patch list. */
    CRITIC_RECONCILE,
    /** Cheap material / palette pick calls. */
    MATERIAL_PICK,
    /** Tool promoter — reviews build's user-defined toolbox fns. */
    TOOL_PROMOTE,
    /** Caption generator — one-liner image description for RAG query. */
    IMAGE_CAPTION;
}
