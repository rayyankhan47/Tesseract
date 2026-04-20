package com.rayyan.tesseract.api;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Per-build $ accounting for LLM calls.
 *
 * <p>Each successful call increments the counters keyed by {@link TaskKind} and
 * by model ID (so e.g. a downshifted call is attributed to the model that
 * actually ran, not the tier that was asked for). Totals surface to chat at
 * {@code COMPLETE} (§3.1.3) and drive the per-phase cost audit (§12.3).
 *
 * <p>Token counts come from the {@code usageMetadata} block in the Gemini
 * response. When that's missing (older SDKs or an oddly-shaped response), the
 * call is recorded at zero tokens / zero dollars to avoid inflating numbers
 * with fabricated estimates — the phase count is the source of truth for call
 * volume, not token volume.
 *
 * <p>Thread-safe: every counter is atomic; collection mutations are guarded on
 * the instance monitor.
 */
public final class CostTracker {

    private final DoubleAdder totalUsd = new DoubleAdder();
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicInteger totalCalls = new AtomicInteger();

    private final Map<TaskKind, AtomicInteger> callsByKind = new EnumMap<>(TaskKind.class);
    private final Map<TaskKind, DoubleAdder>   usdByKind   = new EnumMap<>(TaskKind.class);
    private final Map<String, AtomicInteger>   callsByModel = new HashMap<>();

    public CostTracker() {}

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Record a single successful LLM call.
     *
     * @param kind         logical call site
     * @param modelId      model that actually served the request (post-downshift/escalation)
     * @param inputTokens  prompt token count reported by Gemini (0 if unknown)
     * @param outputTokens candidate token count reported by Gemini (0 if unknown)
     * @param inputPricePerMTok USD / 1M input tokens for this model
     * @param outputPricePerMTok USD / 1M output tokens for this model
     */
    public void record(TaskKind kind, String modelId,
                       long inputTokens, long outputTokens,
                       double inputPricePerMTok, double outputPricePerMTok) {
        double usd = (inputTokens  * inputPricePerMTok  / 1_000_000.0)
                   + (outputTokens * outputPricePerMTok / 1_000_000.0);

        totalCalls.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalUsd.add(usd);

        synchronized (this) {
            callsByKind.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            usdByKind.computeIfAbsent(kind, k -> new DoubleAdder()).add(usd);
            callsByModel.computeIfAbsent(modelId == null ? "unknown" : modelId,
                    m -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Zero-cost record (e.g. cache hit). Still counts toward call volume. */
    public void recordCacheHit(TaskKind kind, String modelId) {
        totalCalls.incrementAndGet();
        synchronized (this) {
            callsByKind.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            callsByModel.computeIfAbsent(modelId == null ? "cache" : modelId,
                    m -> new AtomicInteger()).incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public double totalUsd()          { return totalUsd.sum(); }
    public long totalInputTokens()    { return totalInputTokens.get(); }
    public long totalOutputTokens()   { return totalOutputTokens.get(); }
    public int totalCalls()           { return totalCalls.get(); }

    public synchronized int callsFor(TaskKind kind) {
        AtomicInteger c = callsByKind.get(kind);
        return c == null ? 0 : c.get();
    }

    public synchronized double usdFor(TaskKind kind) {
        DoubleAdder d = usdByKind.get(kind);
        return d == null ? 0.0 : d.sum();
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    /** One-line summary shown to the player at COMPLETE. */
    public String summaryLine() {
        return String.format("Spent $%.4f on %d LLM calls (%d in / %d out tokens)",
                totalUsd(), totalCalls(), totalInputTokens(), totalOutputTokens());
    }

    /** Multi-line phase-by-phase breakdown — logged, not spammed to chat. */
    public synchronized String fullBreakdown() {
        StringBuilder sb = new StringBuilder();
        sb.append(summaryLine()).append('\n');
        for (TaskKind kind : TaskKind.values()) {
            int c = callsFor(kind);
            if (c == 0) continue;
            sb.append(String.format("  %-20s %3d calls $%.4f%n",
                    kind.name(), c, usdFor(kind)));
        }
        for (Map.Entry<String, AtomicInteger> e : callsByModel.entrySet()) {
            sb.append(String.format("  model=%-32s %3d calls%n",
                    e.getKey(), e.getValue().get()));
        }
        return sb.toString();
    }
}
