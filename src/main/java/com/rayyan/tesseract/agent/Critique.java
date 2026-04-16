package com.rayyan.tesseract.agent;

import java.util.List;

/**
 * Structured response from {@link VisualCriticAgent}.
 *
 * @param satisfied {@code true} if the build looks acceptable and no further
 *                  iteration is needed; the patch list will be empty
 * @param issues    human-readable descriptions of visual problems; emitted to
 *                  the player as {@code BuildEvent}s
 * @param patch     ordered list of {@link Patch} operations to apply to the
 *                  current {@link com.rayyan.tesseract.blueprint.Blueprint}
 */
public record Critique(boolean satisfied, List<String> issues, List<Patch> patch) {

    /** Factory for the "converged" case — no further iteration needed. */
    public static Critique converged() {
        return new Critique(true, List.of(), List.of());
    }

    /** Factory for the "needs work" case. */
    public static Critique needsWork(List<String> issues, List<Patch> patch) {
        return new Critique(false,
                issues != null ? List.copyOf(issues) : List.of(),
                patch  != null ? List.copyOf(patch)  : List.of());
    }
}
