package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.plan.ElementSpec;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable record of one L4 element that finished its REPL and
 * committed into the cumulative build (§7.1.3 / §7.3).
 *
 * <p>Fields track both the element's proposed ops (everything the
 * script emitted) and its <em>committed</em> ops (what actually
 * survived overlap detection — earlier elements win at a given
 * voxel, §7.3.3). The two collections differ only when the element
 * ran into already-owned voxels.
 *
 * <p>Diagnostic flags ({@link #wasFallback}, {@link #budgetExceeded})
 * are surfaced to the timeline at COMPLETE so we can see which
 * elements triggered §7.2's graceful-degradation paths without
 * breaking the build.
 */
public record ElementLock(
        ElementSpec spec,
        Set<BlockOp> proposedOps,
        Set<BlockOp> committedOps,
        byte[] lastRenderPng,
        String lastScript,
        int turnsUsed,
        int sandboxErrors,
        boolean wasFallback,
        boolean budgetExceeded,
        double lastCriticScore,
        String notes) {

    public ElementLock {
        Objects.requireNonNull(spec, "spec");
        proposedOps = proposedOps == null ? new LinkedHashSet<>() : new LinkedHashSet<>(proposedOps);
        committedOps = committedOps == null ? new LinkedHashSet<>() : new LinkedHashSet<>(committedOps);
        if (notes == null) notes = "";
        if (lastScript == null) lastScript = "";
    }

    /** Convenience — true when the element was committed cleanly (no fallback, no budget overrun). */
    public boolean clean() { return !wasFallback && !budgetExceeded; }
}
