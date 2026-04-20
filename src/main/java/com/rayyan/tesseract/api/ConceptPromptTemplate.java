package com.rayyan.tesseract.api;

import java.util.List;

/**
 * Produces the four stylistic concept-prompt variations fed to {@link ImagenClient}
 * for Phase 0. Kept as a standalone helper so the prompts can be tuned without
 * touching the client.
 *
 * <p>Variations (by convention, fixed order for downstream indexing):
 * <ol>
 *   <li>{@code MINIMALIST}</li>
 *   <li>{@code ORNATE}</li>
 *   <li>{@code WEATHERED_ANCIENT}</li>
 *   <li>{@code CLEAN_NEW}</li>
 * </ol>
 *
 * <p>Each variation wraps the user prompt in a stylistic frame and appends a
 * shared set of rendering cues that make the output useful as architectural
 * reference rather than illustration. The shared negative prompt (see
 * {@link #DEFAULT_NEGATIVE_PROMPT}) strips cartoons, signage, text overlays,
 * watermarks, and other artefacts that would confuse the downstream vision
 * critic and mass extractor.
 */
public final class ConceptPromptTemplate {

    /** The four stylistic labels, index-aligned with {@link #expand(String)}. */
    public enum Variation {
        MINIMALIST,
        ORNATE,
        WEATHERED_ANCIENT,
        CLEAN_NEW;

        public String label() {
            return switch (this) {
                case MINIMALIST        -> "minimalist";
                case ORNATE            -> "ornate";
                case WEATHERED_ANCIENT -> "weathered ancient";
                case CLEAN_NEW         -> "clean new";
            };
        }
    }

    /** Negative prompt shared across all variations. */
    public static final String DEFAULT_NEGATIVE_PROMPT =
        "cartoon, anime, illustration, sketch, painting, concept art, "
      + "blurry, low quality, jpeg artifacts, watermark, signature, logo, "
      + "text, typography, captions, subtitles, signs, labels, ui overlay, "
      + "people, characters, vehicles, animals, interior view, floor plan, "
      + "diagram, blueprint lines, isometric diagram, schematic";

    /** Single shared trailer appended to every variation. */
    private static final String RENDER_TRAILER =
        " Architectural exterior photograph, photoreal, natural daylight, "
      + "clean sky background, clear silhouette against sky, full building "
      + "visible from a three-quarter front elevation, centered composition, "
      + "high detail on structural features and materials.";

    private ConceptPromptTemplate() {}

    /**
     * Expands the user's prompt into four concept prompts, one per
     * {@link Variation}, in the order declared by the enum.
     */
    public static List<String> expand(String userPrompt) {
        String clean = userPrompt == null ? "" : userPrompt.strip();
        return List.of(
            minimalist(clean),
            ornate(clean),
            weatheredAncient(clean),
            cleanNew(clean)
        );
    }

    /**
     * Returns the positive prompt for a single variation. Exposed so
     * {@link com.rayyan.tesseract.agent.ConceptAgent} (or tests) can re-build
     * the full prompt for debug logging without re-expanding all four.
     */
    public static String promptFor(Variation v, String userPrompt) {
        String clean = userPrompt == null ? "" : userPrompt.strip();
        return switch (v) {
            case MINIMALIST        -> minimalist(clean);
            case ORNATE            -> ornate(clean);
            case WEATHERED_ANCIENT -> weatheredAncient(clean);
            case CLEAN_NEW         -> cleanNew(clean);
        };
    }

    public static String negativePrompt() {
        return DEFAULT_NEGATIVE_PROMPT;
    }

    // -------------------------------------------------------------------------
    // Per-variation frames
    // -------------------------------------------------------------------------

    private static String minimalist(String p) {
        return "Minimalist interpretation of: " + p + ". "
             + "Pared-back geometry, restrained palette, strong primary forms, "
             + "no unnecessary ornament, clean hard edges."
             + RENDER_TRAILER;
    }

    private static String ornate(String p) {
        return "Highly ornate interpretation of: " + p + ". "
             + "Maximal decorative detail, rich sculptural articulation, "
             + "layered surface relief, intricate mouldings, every facade "
             + "densely populated with features."
             + RENDER_TRAILER;
    }

    private static String weatheredAncient(String p) {
        return "Weathered ancient interpretation of: " + p + ". "
             + "Heavily aged stone and timber, moss and ivy creeping over "
             + "surfaces, cracks and spalling, missing fragments, deep "
             + "patina, centuries-old wear, overgrown but structurally intact."
             + RENDER_TRAILER;
    }

    private static String cleanNew(String p) {
        return "Freshly-built pristine interpretation of: " + p + ". "
             + "Bright crisp materials, sharp edges, unstained surfaces, "
             + "recently completed construction, no weathering or damage, "
             + "presentation-quality render."
             + RENDER_TRAILER;
    }
}
