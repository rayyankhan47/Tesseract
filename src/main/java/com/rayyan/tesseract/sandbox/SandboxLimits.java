package com.rayyan.tesseract.sandbox;

/**
 * Resource ceilings for a single sandbox invocation (§6.2.2). Defaults
 * match the plan: 50k AST steps, 5s wall, modest collection sizes.
 * Tests override with smaller values to exercise the guards.
 */
public record SandboxLimits(
        long maxSteps,
        long maxWallMs,
        int maxCollectionSize,
        int maxLoopIterations) {

    public static SandboxLimits defaults() {
        return new SandboxLimits(50_000L, 5_000L, 50_000, 20_000);
    }

    public static SandboxLimits forTests() {
        return new SandboxLimits(20_000L, 2_000L, 10_000, 10_000);
    }
}
