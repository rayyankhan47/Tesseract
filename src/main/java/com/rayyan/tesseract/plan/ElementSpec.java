package com.rayyan.tesseract.plan;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * L3 output — one architectural element living inside a single
 * {@link StructuralZone} (§5.3.1).
 *
 * <p>An {@code ElementSpec} is half natural-language (a description the
 * downstream L4 REPL agent reads) and half structured parameters (a
 * free-form {@link JsonObject} the REPL can key into when scripting the
 * element's geometry). L3 is intentionally vague on block ids — that's
 * L4's responsibility, along with resolving material_families to the
 * actual palette.
 *
 * <p>{@code dependsOn} lists the ids of other {@link ElementSpec}s that
 * must be placed before this one (e.g. foundation-course before wall
 * courses). {@code orderHint} is a secondary sort key (smaller = earlier)
 * used when no explicit dependency edge exists; it usually tracks the
 * element's Y position so lower elements build first.
 */
public record ElementSpec(
        String id,
        String zoneLabel,
        String massLabel,
        String description,
        JsonObject parameters,
        List<String> dependsOn,
        int orderHint,
        List<String> citing) {

    public ElementSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ElementSpec requires an id");
        }
        if (zoneLabel == null || zoneLabel.isBlank()) {
            throw new IllegalArgumentException("ElementSpec requires a zoneLabel");
        }
        if (massLabel == null) massLabel = "";
        if (description == null) description = "";
        if (parameters == null) parameters = new JsonObject();
        dependsOn = dependsOn == null ? Collections.emptyList() : List.copyOf(dependsOn);
        citing = citing == null ? Collections.emptyList() : List.copyOf(citing);
    }
}
