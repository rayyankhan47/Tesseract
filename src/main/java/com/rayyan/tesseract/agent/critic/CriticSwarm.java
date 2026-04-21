package com.rayyan.tesseract.agent.critic;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.plan.ElementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * §8.2 — fires all five critics on the same render; 8s wall-clock cap
 * per vision seat. Silhouette is synchronous and not timed.
 *
 * <p>Results are appended to {@link BuildState#timeline} for §8.2.3
 * replay/debug.
 */
public final class CriticSwarm {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic_swarm");

    /** §8.2.1 — per-critic timeout (vision seats only). */
    public static final long CRITIC_TIMEOUT_MS = 8_000L;

    private CriticSwarm() {}

    /**
     * Runs the full swarm and returns five opinions in enum order.
     */
    public static List<CriticOpinion> runSync(
            BuildState state,
            GeminiClient gemini,
            ElementSpec spec,
            Set<BlockOp> candidateOps,
            byte[] cumulativeRender,
            int turn) {

        return run(state, gemini, spec, candidateOps, cumulativeRender, turn).join();
    }

    public static CompletableFuture<List<CriticOpinion>> run(
            BuildState state,
            GeminiClient gemini,
            ElementSpec spec,
            Set<BlockOp> candidateOps,
            byte[] cumulativeRender,
            int turn) {

        CriticOpinion silhouette = SilhouetteCritic.evaluate(state, candidateOps);

        CompletableFuture<CriticOpinion> style = timed(
                StyleCritic.evaluate(state, gemini, spec, cumulativeRender),
                CriticKind.STYLE, spec.id());
        CompletableFuture<CriticOpinion> proportion = timed(
                ProportionCritic.evaluate(state, gemini, spec, cumulativeRender),
                CriticKind.PROPORTION, spec.id());
        CompletableFuture<CriticOpinion> detail = timed(
                DetailCritic.evaluate(state, gemini, spec, cumulativeRender),
                CriticKind.DETAIL, spec.id());
        CompletableFuture<CriticOpinion> reference = timed(
                ReferenceMatchCritic.evaluate(state, gemini, spec, cumulativeRender),
                CriticKind.REFERENCE_MATCH, spec.id());

        return CompletableFuture.allOf(style, proportion, detail, reference)
                .thenApply(v -> {
                    List<CriticOpinion> out = new ArrayList<>(5);
                    out.add(silhouette);
                    out.add(style.join());
                    out.add(proportion.join());
                    out.add(detail.join());
                    out.add(reference.join());
                    logTimeline(state, spec.id(), turn, out);
                    return out;
                });
    }

    private static CompletableFuture<CriticOpinion> timed(
            CompletableFuture<CriticOpinion> raw, CriticKind kind, String elementId) {
        return raw.orTimeout(CRITIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    LOGGER.warn("CRITIC_SKIPPED kind={} element={} reason={}",
                            kind, elementId, ex.getMessage());
                    return CriticOpinion.skipped(kind, "CRITIC_SKIPPED: " + ex.getMessage());
                });
    }

    private static void logTimeline(BuildState state, String elementId, int turn,
                                    List<CriticOpinion> opinions) {
        if (state == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append("L4_CRITIC_SWARM element=").append(elementId)
          .append(" turn=").append(turn);
        for (CriticOpinion o : opinions) {
            sb.append(' ').append(o.kind().name()).append('=');
            if (o.skipped()) {
                sb.append("skip");
            } else {
                sb.append(String.format("%.2f", o.score()));
            }
        }
        state.appendTimeline(sb.toString());
    }
}
