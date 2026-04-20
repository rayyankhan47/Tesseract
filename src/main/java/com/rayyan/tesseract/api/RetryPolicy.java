package com.rayyan.tesseract.api;

/**
 * Constants and classifier helpers for the three failure classes defined in
 * REFACTOR_3 §3.2. Kept as a separate file so the policy is greppable and easy
 * to tune without spelunking through {@link GeminiClient}.
 *
 * <ul>
 *   <li>§3.2.1 {@link FailureClass#TRANSIENT} — same model, exponential backoff,
 *       capped at 3 attempts.</li>
 *   <li>§3.2.2 {@link FailureClass#RATE_LIMIT} — one-shot downshift to the
 *       {@link ModelSpec#downshiftTarget}; the next unrelated call still uses
 *       the original tier.</li>
 *   <li>§3.2.3 {@link FailureClass#PARSE_REFUSAL} — escalate upward along
 *       {@link ModelSpec#escalationChain} with a stricter reminder appended
 *       (max 2 escalations).</li>
 *   <li>{@link FailureClass#FATAL} — propagate to the caller; orchestrator
 *       routes to {@code failBuild} unless the call site is flagged optional.</li>
 * </ul>
 */
public final class RetryPolicy {

    /** Per-attempt backoff delays in ms for transient failures (§3.2.1). */
    public static final long[] TRANSIENT_BACKOFF_MS = { 500L, 2_000L, 8_000L };

    /** Random jitter window added on each transient retry (ms). */
    public static final long JITTER_MS = 600L;

    /** Maximum number of upward escalations before giving up (§3.2.3). */
    public static final int MAX_ESCALATIONS = 2;

    private RetryPolicy() {}

    /**
     * Classifies an HTTP status (+ optional response body snippet) into one of
     * the four failure classes. Called once per HTTP response to decide which
     * chain applies.
     *
     * <p>Broadening beyond pure status codes:
     * <ul>
     *   <li>403 bodies containing "RESOURCE_EXHAUSTED" or "quota" are treated
     *       as {@link FailureClass#RATE_LIMIT}, because Gemini sometimes
     *       reports quota exhaustion as a 403 with those phrases.</li>
     *   <li>408 and 503 are transient like 5xx.</li>
     * </ul>
     */
    public static FailureClass classify(int status, String bodyPreview) {
        if (status >= 200 && status < 300) return FailureClass.OK;
        if (status == 429) return FailureClass.RATE_LIMIT;
        if (status == 408 || status == 503) return FailureClass.TRANSIENT;
        if (status >= 500) return FailureClass.TRANSIENT;
        if (status == 403 && bodyPreview != null) {
            String lower = bodyPreview.toLowerCase();
            if (lower.contains("resource_exhausted")
                    || lower.contains("quota")
                    || lower.contains("rate limit")) {
                return FailureClass.RATE_LIMIT;
            }
        }
        return FailureClass.FATAL;
    }

    public enum FailureClass {
        /** 2xx — response is usable (subject to validator). */
        OK,
        /** 5xx / 408 / 503 / I/O — retry same model with exp backoff (§3.2.1). */
        TRANSIENT,
        /** 429 / 403 RESOURCE_EXHAUSTED — one-shot downshift (§3.2.2). */
        RATE_LIMIT,
        /** Parse failure / refusal / empty — escalate upward (§3.2.3). */
        PARSE_REFUSAL,
        /** 4xx other than those above — propagate; no retry. */
        FATAL
    }
}
