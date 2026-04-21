package com.rayyan.tesseract.plan;

import java.util.Collections;
import java.util.List;

/**
 * L2 output — a vertical band of structural function within a single
 * {@link MajorMass} (§5.2.1).
 *
 * <p>Classic decomposition: {@code foundation → body → crown}, plus
 * {@code rhythm} zones that mark repeating horizontal courses (window
 * bands, cornices, belt courses).
 *
 * <p>{@code yMin} / {@code yMax} are inclusive voxel-local Y coordinates
 * within the parent mass's bounding box, in the same 16³ space as the
 * L1 plan. Feature hints are natural-language phrases L3 reads as
 * suggestions ("window course at y=6,10,14", "arcaded ground floor").
 * Material families steer the L3 / material-pick prompt but don't pin
 * specific block ids — that's done in the L4 scripting step.
 */
public record StructuralZone(
        String label,
        String role,
        String massLabel,
        int yMin,
        int yMax,
        List<String> featureHints,
        List<String> materialFamilies,
        List<String> citing) {

    public StructuralZone {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("StructuralZone requires a label");
        }
        if (massLabel == null || massLabel.isBlank()) {
            throw new IllegalArgumentException("StructuralZone requires a massLabel");
        }
        if (role == null) role = "unspecified";
        if (yMin > yMax) {
            throw new IllegalArgumentException("StructuralZone yMin > yMax: " + yMin + " > " + yMax);
        }
        featureHints     = featureHints     == null ? Collections.emptyList() : List.copyOf(featureHints);
        materialFamilies = materialFamilies == null ? Collections.emptyList() : List.copyOf(materialFamilies);
        citing           = citing           == null ? Collections.emptyList() : List.copyOf(citing);
    }

    public int height() { return yMax - yMin + 1; }
}
