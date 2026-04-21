package com.rayyan.tesseract.toolbox;

import java.util.List;
import java.util.Objects;

/**
 * A user-defined function promoted from a build's L4 script into the
 * persistent extension library (§6.3.2).
 *
 * <p>Each extension carries enough provenance — build id, original
 * prompt, promotion timestamp — that later audits can always answer
 * "who suggested this function and why". {@link #usageExamples}
 * flows into the next build's L4 prompt as the community-contributed
 * tool reference (§6.3.3).
 *
 * @param name           snake_case identifier used inside scripts.
 * @param signature      human-readable signature, e.g. {@code "arched_window(x, y, z, width, height)"}.
 * @param source         the verbatim {@code def}-block body from the
 *                       original script, re-parseable by our sandbox.
 * @param description    one-line summary surfaced in the L4 prompt.
 * @param usageExamples  short snippets showing how to call the fn.
 * @param buildId        UUID of the originating build.
 * @param originalPrompt the user prompt that produced this fn.
 * @param score          the ToolPromoter's 0..1 rating at promotion time.
 * @param promotedAtMs   epoch millis when added to the library.
 */
public record ToolboxExtension(
        String name,
        String signature,
        String source,
        String description,
        List<String> usageExamples,
        String buildId,
        String originalPrompt,
        double score,
        long promotedAtMs) {

    public ToolboxExtension {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        usageExamples = usageExamples == null ? List.of() : List.copyOf(usageExamples);
    }
}
