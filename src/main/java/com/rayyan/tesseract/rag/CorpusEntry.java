package com.rayyan.tesseract.rag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One architectural-knowledge row from {@code architecture_corpus.jsonl}.
 *
 * <p>Three categories: {@code style}, {@code element}, {@code exemplar}. All
 * share the same schema; unused fields can be empty. Numeric facts live in
 * {@link #canonicalDimensions} as a raw {@link JsonObject} so planners can
 * reason over them without losing structure (e.g. {@code
 * nave_height_m: 33}, {@code aisle_count: 2}).
 *
 * <p>Per REFACTOR_3 §4.1 — curated seed corpus + numeric facts preserved.
 */
public record CorpusEntry(
        String id,
        String category,
        String name,
        String style,
        String period,
        String region,
        String description,
        JsonObject canonicalDimensions,
        List<String> definingFeatures) {

    public CorpusEntry {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (name == null) name = id;
        if (category == null) category = "unknown";
        if (style == null) style = "";
        if (period == null) period = "";
        if (region == null) region = "";
        if (description == null) description = "";
        if (canonicalDimensions == null) canonicalDimensions = new JsonObject();
        definingFeatures = definingFeatures == null
                ? Collections.emptyList()
                : List.copyOf(definingFeatures);
    }

    /**
     * Produces a compact embedding string (corpus text → vector). Concatenates
     * every queryable field so {@code text-embedding-004} can attend to both
     * stylistic vocabulary and numeric facts.
     */
    public String embeddingText() {
        StringBuilder sb = new StringBuilder(512);
        sb.append(name).append(" — ").append(category);
        if (!style.isBlank())  sb.append(" · style=").append(style);
        if (!period.isBlank()) sb.append(" · period=").append(period);
        if (!region.isBlank()) sb.append(" · region=").append(region);
        if (!description.isBlank()) sb.append(". ").append(description);
        if (!definingFeatures.isEmpty()) {
            sb.append(" Features: ").append(String.join(", ", definingFeatures)).append('.');
        }
        if (canonicalDimensions.size() > 0) {
            sb.append(" Dimensions: ").append(canonicalDimensions.toString());
        }
        return sb.toString();
    }

    /**
     * Compact one-paragraph form used by L1/L2/L3 prompts (§4.3.1 context
     * injection). Trades verbosity for token economy — callers that want the
     * full record should call {@link #toPromptBlock()}.
     */
    public String toShortPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(id).append("] ").append(name);
        if (!style.isBlank()) sb.append(" (").append(style).append(")");
        if (!description.isBlank()) sb.append(": ").append(description);
        if (!definingFeatures.isEmpty() && definingFeatures.size() <= 8) {
            sb.append(" Features: ").append(String.join(", ", definingFeatures)).append('.');
        }
        return sb.toString();
    }

    /** Full prompt block — used sparingly for the top-1 retrieved entry. */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(name).append("  [").append(id).append("]\n");
        if (!category.isBlank()) sb.append("Category: ").append(category).append('\n');
        if (!style.isBlank())    sb.append("Style: ").append(style).append('\n');
        if (!period.isBlank())   sb.append("Period: ").append(period).append('\n');
        if (!region.isBlank())   sb.append("Region: ").append(region).append('\n');
        if (!description.isBlank()) sb.append("Description: ").append(description).append('\n');
        if (canonicalDimensions.size() > 0) {
            sb.append("Canonical dimensions: ").append(canonicalDimensions.toString()).append('\n');
        }
        if (!definingFeatures.isEmpty()) {
            sb.append("Defining features: ").append(String.join(", ", definingFeatures)).append('\n');
        }
        return sb.toString();
    }

    /** Parse a single JSONL line. Null if the line is blank or comment. */
    public static CorpusEntry fromJson(JsonObject obj) {
        if (obj == null) return null;
        List<String> features = new ArrayList<>();
        JsonElement featuresEl = obj.get("defining_features");
        if (featuresEl != null && featuresEl.isJsonArray()) {
            featuresEl.getAsJsonArray().forEach(e -> {
                if (!e.isJsonNull()) features.add(e.getAsString());
            });
        }
        JsonObject dims = obj.has("canonical_dimensions") && obj.get("canonical_dimensions").isJsonObject()
                ? obj.getAsJsonObject("canonical_dimensions")
                : new JsonObject();
        return new CorpusEntry(
                getString(obj, "id"),
                getString(obj, "category"),
                getString(obj, "name"),
                getString(obj, "style"),
                getString(obj, "period"),
                getString(obj, "region"),
                getString(obj, "description"),
                dims,
                features);
    }

    private static String getString(JsonObject obj, String field) {
        JsonElement el = obj.get(field);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }
}
