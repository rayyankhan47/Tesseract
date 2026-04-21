package com.rayyan.tesseract.agent;

/**
 * A single concept/reference image plus the metadata needed to trace it back
 * to the call that produced it.
 *
 * <p>Introduced by {@code ConceptAgent} (Phase 0); consumed by every downstream
 * agent that calls Gemini Vision. Critics compare the current render to the
 * {@code selectedConceptIndex} entry of {@code BuildState.referenceImages}
 * every iteration.
 *
 * @param bytes        PNG bytes (always {@code image/png} in v3 — Imagen and
 *                     IsoRenderer both emit PNG)
 * @param mimeType     always {@code "image/png"} for now; kept for forward-compat
 * @param prompt       the exact Imagen prompt used; useful for debug dumps
 * @param sourceModel  model id that produced the image ({@code imagen-3.0-generate-002}
 *                     for Phase 0; free-form for future sources)
 * @param variation    short label describing the stylistic variation
 *                     ({@code "minimalist"}, {@code "ornate"}, …) — may be null
 */
public record ReferenceImage(
        byte[] bytes,
        String mimeType,
        String prompt,
        String sourceModel,
        String variation) {

    public ReferenceImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("ReferenceImage requires non-empty bytes");
        }
        if (mimeType == null || mimeType.isBlank()) mimeType = "image/png";
    }

    /** Convenience constructor for sources without a variation label. */
    public static ReferenceImage of(byte[] bytes, String prompt, String sourceModel) {
        return new ReferenceImage(bytes, "image/png", prompt, sourceModel, null);
    }
}
