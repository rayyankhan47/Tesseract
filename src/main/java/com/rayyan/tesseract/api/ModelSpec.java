package com.rayyan.tesseract.api;

import java.util.List;

/**
 * Everything needed to pin a Gemini call to a single model invocation.
 *
 * <p>Values reflect the tiering table in REFACTOR_3 §3.1.1 and the failure-class
 * chains in §3.2.
 *
 * @param modelId            Gemini model id (e.g. {@code gemini-2.5-pro}).
 * @param temperature        sampling temperature in [0, 2]. Null → API default.
 * @param maxOutputTokens    hard cap on candidate tokens. Null → API default.
 * @param timeoutMs          per-request HTTP timeout in ms.
 * @param downshiftTarget    model to use for a one-shot downshift on 429 /
 *                           quota. Null → no downshift (call just fails).
 * @param escalationChain    ordered list of progressively heavier models used
 *                           on parse failure / refusal / empty. The first
 *                           entry is the baseline model itself; subsequent
 *                           entries are tried in order with a stricter reminder
 *                           appended. Max 3 entries (baseline + 2 escalations).
 * @param isOptional         when true, downstream orchestrator should log
 *                           {@code CRITIC_SKIPPED} / similar on terminal
 *                           failure and continue rather than aborting.
 * @param inputPricePerMTok  USD / million input tokens for {@link CostTracker}.
 * @param outputPricePerMTok USD / million output tokens for {@link CostTracker}.
 */
public record ModelSpec(
        String modelId,
        Double temperature,
        Integer maxOutputTokens,
        long timeoutMs,
        String downshiftTarget,
        List<String> escalationChain,
        boolean isOptional,
        double inputPricePerMTok,
        double outputPricePerMTok) {

    public ModelSpec {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId required");
        }
        if (timeoutMs <= 0) timeoutMs = 60_000L;
        if (escalationChain == null || escalationChain.isEmpty()) {
            escalationChain = List.of(modelId);
        } else {
            escalationChain = List.copyOf(escalationChain);
        }
    }

    /** Convenience builder for call sites that don't care about pricing / escalation. */
    public static ModelSpec basic(String modelId, double temperature, int maxOutputTokens) {
        return new ModelSpec(modelId, temperature, maxOutputTokens,
                60_000L, null, List.of(modelId), false, 0.0, 0.0);
    }

    /** Returns a {@link GeminiClient.GenerationConfig} view of this spec. */
    public GeminiClient.GenerationConfig toGenerationConfig() {
        return new GeminiClient.GenerationConfig(temperature, maxOutputTokens, null, null);
    }
}
