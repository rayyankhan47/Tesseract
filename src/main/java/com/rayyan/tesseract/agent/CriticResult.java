package com.rayyan.tesseract.agent;

/**
 * Result returned by {@link CriticAgent#validate}.
 *
 * Step 7 fills in the real validation checks; this record is the shared
 * contract between GenerationAgent (consumer) and CriticAgent (producer).
 */
public record CriticResult(boolean passed, String failureReason) {

    public static CriticResult pass() {
        return new CriticResult(true, null);
    }

    public static CriticResult fail(String reason) {
        return new CriticResult(false, reason);
    }
}
