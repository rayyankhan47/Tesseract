package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.plan.ElementSpec;
import com.rayyan.tesseract.toolbox.ToolboxExtensionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §7.2 driver — iterates a build's {@link ElementSpec}s through the
 * {@link L4ReplAgent} in dependency order, committing each result
 * into the shared {@link CumulativeBuild}.
 *
 * <p>Responsibilities split with {@link L4ReplAgent}:
 * <ul>
 *   <li>Scheduler: ordering (§7.2.1), cross-element bookkeeping (lock
 *       list on {@link BuildState}, cumulative render cache, script
 *       capture for the Tool Promoter).</li>
 *   <li>REPL agent: per-element budget + sandbox fallback (§7.2.2,
 *       §7.2.3) — already implemented there.</li>
 * </ul>
 *
 * <p>Order is a deterministic extension of {@link ElementSpec}'s
 * already-topologically-sorted list: L3 hands us elements with
 * {@code dependsOn} and {@code orderHint} pre-resolved, so the
 * scheduler only has to guard against cycles / unknown deps one
 * last time (defence-in-depth — L3 is supposed to filter).
 *
 * <p>All work is sequential for Step 7. §7.2.1 explicitly notes
 * parallelisation of dependency-free elements as a future
 * optimisation; today's contract is "foundation before walls before
 * roof", which requires serial commit order for overlap detection
 * to be meaningful.
 */
public final class ElementScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l4_sched");

    private ElementScheduler() {}

    /**
     * Runs every element spec in {@link BuildState#elementSpecs}
     * through the L4 REPL, committing each into a freshly-created
     * cumulative build. Idempotent in the sense that calling twice
     * just replays the pipeline; use on a fresh {@link BuildState}.
     *
     * @return the final list of {@link ElementLock}s (also stored on
     *         {@link BuildState#elementLocks}).
     */
    public static List<ElementLock> runAll(BuildState state,
                                           GeminiClient gemini,
                                           ToolboxExtensionStore extensionStore) {
        if (state == null) throw new IllegalArgumentException("state required");
        List<ElementSpec> specs = new ArrayList<>(state.elementSpecs);
        if (specs.isEmpty()) {
            LOGGER.info("L4_SCHED_EMPTY — no elements from L3, skipping REPL");
            return List.of();
        }

        CumulativeBuild cumulative = new CumulativeBuild();
        state.cumulativeBuild = cumulative;
        state.elementLocks.clear();

        List<ElementSpec> ordered = orderSpecs(specs);
        String extensionPrompt = extensionStore == null
                ? "" : extensionStore.renderForPrompt();

        int index = 0;
        for (ElementSpec spec : ordered) {
            index++;
            long startMs = System.currentTimeMillis();
            LOGGER.info("L4_ELEMENT_START {}/{} id={} zone={} mass={}",
                    index, ordered.size(), spec.id(), spec.zoneLabel(), spec.massLabel());

            ElementLock candidate = L4ReplAgent.runElement(state, gemini, cumulative, spec, extensionPrompt);

            // §7.3.3 — commit through the cumulative so overlaps are trimmed.
            Set<BlockOp> committedOps = cumulative.commit(candidate);
            ElementLock locked = withCommitted(candidate, committedOps);
            state.elementLocks.add(locked);
            if (locked.lastScript() != null && !locked.lastScript().isBlank()) {
                state.buildScripts.add(locked.lastScript());
            }

            long elapsed = System.currentTimeMillis() - startMs;
            LOGGER.info("L4_ELEMENT_LOCK id={} turns={} score={} proposed={} committed={} "
                    + "fallback={} budget_exceeded={} elapsed_ms={}",
                    spec.id(), locked.turnsUsed(), locked.lastCriticScore(),
                    locked.proposedOps().size(), locked.committedOps().size(),
                    locked.wasFallback(), locked.budgetExceeded(), elapsed);
        }

        return List.copyOf(state.elementLocks);
    }

    // -------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------

    /**
     * Defensive topological sort over {@code dependsOn} + {@code orderHint}.
     * Unknown dependencies are dropped with a log; cycles fall back to the
     * original order for the affected ids.
     */
    static List<ElementSpec> orderSpecs(List<ElementSpec> specs) {
        Map<String, ElementSpec> byId = new HashMap<>();
        for (ElementSpec s : specs) byId.put(s.id(), s);

        // Stable fallback sort: orderHint first, then original index.
        List<ElementSpec> input = new ArrayList<>(specs);
        input.sort((a, b) -> {
            int cmp = Integer.compare(a.orderHint(), b.orderHint());
            if (cmp != 0) return cmp;
            return Integer.compare(specs.indexOf(a), specs.indexOf(b));
        });

        List<ElementSpec> out = new ArrayList<>(input.size());
        Set<String> placed = new LinkedHashSet<>();
        Set<String> visiting = new HashSet<>();
        for (ElementSpec spec : input) {
            visit(spec, byId, placed, visiting, out);
        }
        return out;
    }

    private static void visit(ElementSpec spec, Map<String, ElementSpec> byId,
                              Set<String> placed, Set<String> visiting, List<ElementSpec> out) {
        if (spec == null || placed.contains(spec.id())) return;
        if (!visiting.add(spec.id())) {
            LOGGER.warn("L4_SCHED_CYCLE element={} dependsOn={}", spec.id(), spec.dependsOn());
            placed.add(spec.id());
            out.add(spec);
            return;
        }
        for (String depId : spec.dependsOn()) {
            ElementSpec dep = byId.get(depId);
            if (dep == null) {
                LOGGER.warn("L4_SCHED_MISSING_DEP element={} missing={}", spec.id(), depId);
                continue;
            }
            visit(dep, byId, placed, visiting, out);
        }
        visiting.remove(spec.id());
        if (placed.add(spec.id())) out.add(spec);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ElementLock withCommitted(ElementLock candidate, Set<BlockOp> committedOps) {
        return new ElementLock(candidate.spec(),
                candidate.proposedOps(), committedOps,
                candidate.lastRenderPng(), candidate.lastScript(),
                candidate.turnsUsed(), candidate.sandboxErrors(),
                candidate.wasFallback(), candidate.budgetExceeded(),
                candidate.lastCriticScore(), candidate.notes());
    }
}
