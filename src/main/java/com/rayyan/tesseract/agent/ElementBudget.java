package com.rayyan.tesseract.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §3.3.2 budget for a single L4 REPL element.
 *
 * <p>Tracks how many LLM turns and wall-clock ms an element has consumed.
 * When either cap is hit, {@link #shouldStop()} returns true and the caller
 * commits the last-known-good op-set with a {@code ELEMENT_BUDGET_EXCEEDED}
 * log — the build continues, the element just stops iterating.
 *
 * <p>L4 is introduced in Step 7; this class ships early so future code can
 * depend on a stable helper.
 */
public final class ElementBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.budget");

    public static final int  DEFAULT_MAX_TURNS    = 6;
    public static final long DEFAULT_MAX_WALL_MS  = 90_000L;

    private final String elementId;
    private final int maxTurns;
    private final long maxWallMs;
    private final long startedAtMs;

    private int turnsUsed;

    public ElementBudget(String elementId) {
        this(elementId, DEFAULT_MAX_TURNS, DEFAULT_MAX_WALL_MS);
    }

    public ElementBudget(String elementId, int maxTurns, long maxWallMs) {
        this.elementId = elementId == null ? "unknown" : elementId;
        this.maxTurns = Math.max(1, maxTurns);
        this.maxWallMs = Math.max(1_000L, maxWallMs);
        this.startedAtMs = System.currentTimeMillis();
    }

    /** Record one LLM turn against the budget. */
    public void recordTurn() { turnsUsed++; }

    public boolean turnsExhausted()  { return turnsUsed >= maxTurns; }
    public boolean wallExhausted()   { return (System.currentTimeMillis() - startedAtMs) >= maxWallMs; }

    /** @return true when either budget dimension has been crossed. */
    public boolean shouldStop() {
        return turnsExhausted() || wallExhausted();
    }

    /** Emit the canonical log tag for §3.3.2 when the budget is hit. */
    public void logExceeded() {
        LOGGER.warn("ELEMENT_BUDGET_EXCEEDED element={} turns={}/{} wallMs={}/{}",
                elementId, turnsUsed, maxTurns,
                System.currentTimeMillis() - startedAtMs, maxWallMs);
    }

    public String elementId()   { return elementId; }
    public int turnsUsed()      { return turnsUsed; }
    public long elapsedMs()     { return System.currentTimeMillis() - startedAtMs; }
}
