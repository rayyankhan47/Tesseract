package com.rayyan.tesseract.plan;

import java.util.Collections;
import java.util.List;

/**
 * A single primary volume in the L1 {@link MassPlan}.
 *
 * <p>Labels are semantic free-form strings ({@code "central_tower"},
 * {@code "east_wing"}). Roles are a small vocabulary used by L2/L3 to
 * pattern-match behaviour ({@code "primary_vertical"},
 * {@code "secondary_horizontal"}, {@code "connector"}). The {@link #bounds}
 * is in 16³ voxel-mass-local space and is guaranteed non-null.
 *
 * <p>{@code citing} holds the corpus-entry ids the LLM used to justify this
 * mass (§4.3.3). Empty if the LLM did not cite.
 */
public record MajorMass(
        String label,
        String role,
        BoundingBox bounds,
        List<String> citing) {

    public MajorMass {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("MajorMass requires a non-blank label");
        }
        if (role == null) role = "unspecified";
        if (bounds == null) {
            throw new IllegalArgumentException("MajorMass requires bounds");
        }
        citing = citing == null ? Collections.emptyList() : List.copyOf(citing);
    }

    /** Convenience constructor without citations. */
    public static MajorMass of(String label, String role, BoundingBox bounds) {
        return new MajorMass(label, role, bounds, Collections.emptyList());
    }
}
