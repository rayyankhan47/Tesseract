package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.gumloop.GumloopPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Stage 3 of the build pipeline.
 *
 * Generates block coordinates for one component at a time. For each component,
 * one focused LLM call uses the full context window to produce a flat JSON array
 * of block operations relative to the component's origin.
 *
 * Retry logic:
 *   - CriticAgent validates the generated ops before any blocks are placed.
 *   - On failure, retries up to MAX_RETRIES times, appending the critic's failure
 *     reason to the next prompt so the model can self-correct.
 *   - After MAX_RETRIES consecutive failures the component is skipped
 *     (added to state.failedComponentIds) and the pipeline advances.
 */
public final class GenerationAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.generation");
    static final int MAX_RETRIES = 3;

    private GenerationAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates and validates blocks for the component at {@code state.currentComponentIndex}.
     *
     * @param state             shared build context
     * @param gemini            Gemini API client
     * @param retryAttempt      0-based attempt count for the current component
     * @param priorFailureReason critic failure reason from the previous attempt, or null
     * @param onCriticPass      called (already on server thread) when CriticAgent approves —
     *                          approved ops are stored on {@code component.pendingOps}
     * @param onCriticFail      called (already on server thread) with (reason, shouldRetry);
     *                          {@code shouldRetry=false} means max retries reached: skip component
     */
    public static void runComponent(BuildState state,
                                    GeminiClient gemini,
                                    int retryAttempt,
                                    String priorFailureReason,
                                    Runnable onCriticPass,
                                    BiConsumer<String, Boolean> onCriticFail) {
        ComponentPlan component = state.componentPlan.get(state.currentComponentIndex);

        // Max retries exceeded — skip this component.
        if (retryAttempt >= MAX_RETRIES) {
            String reason = priorFailureReason != null ? priorFailureReason : "max retries exceeded";
            LOGGER.warn("GenerationAgent: component '{}' failed after {} attempts. Skipping. Reason: {}",
                    component.name, MAX_RETRIES, reason);
            state.failedComponentIds.add(component.id);
            onCriticFail.accept(reason, false);
            return;
        }

        // TODO(Step 6.3): replace stub with actual Gemini call and JSON parsing.
        // The stub returns an empty list, which CriticAgent will reject, triggering
        // the retry path. This keeps the compile/test cycle intact until 6.3.
        List<GumloopPayload.BlockOp> ops = List.of();
        handleCriticResult(state, gemini, component, ops, retryAttempt, onCriticPass, onCriticFail);
    }

    // -------------------------------------------------------------------------
    // Critic integration and retry dispatch
    // -------------------------------------------------------------------------

    static void handleCriticResult(BuildState state,
                                    GeminiClient gemini,
                                    ComponentPlan component,
                                    List<GumloopPayload.BlockOp> ops,
                                    int retryAttempt,
                                    Runnable onCriticPass,
                                    BiConsumer<String, Boolean> onCriticFail) {
        List<String> palette = buildFullPalette(state);
        CriticResult result = CriticAgent.validate(ops, component, palette);

        if (result.passed()) {
            LOGGER.info("GenerationAgent: component '{}' passed critic ({} blocks).",
                    component.name, ops.size());
            component.pendingOps = ops;
            component.retryCount = retryAttempt;
            component.lastFailureReason = null;
            onCriticPass.run();
        } else {
            int nextAttempt = retryAttempt + 1;
            boolean shouldRetry = nextAttempt < MAX_RETRIES;
            LOGGER.warn("GenerationAgent: component '{}' failed critic (attempt {}/{}): {}",
                    component.name, retryAttempt + 1, MAX_RETRIES, result.failureReason());
            component.retryCount = nextAttempt;
            component.lastFailureReason = result.failureReason();
            onCriticFail.accept(result.failureReason(), shouldRetry);
        }
    }

    // -------------------------------------------------------------------------
    // Palette helpers
    // -------------------------------------------------------------------------

    static List<String> buildFullPalette(BuildState state) {
        // The canonical safe palette — always allowed.
        List<String> safe = defaultPalette();
        if (state.spec == null || state.spec.materials == null) return safe;

        // Expand spec materials (fragments like "stone_bricks") to full IDs.
        java.util.List<String> combined = new java.util.ArrayList<>(safe);
        for (String mat : state.spec.materials) {
            String full = mat.contains(":") ? mat : "minecraft:" + mat;
            if (!combined.contains(full)) combined.add(full);
        }
        return java.util.Collections.unmodifiableList(combined);
    }

    static List<String> defaultPalette() {
        return List.of(
            "minecraft:stone",
            "minecraft:stone_bricks",
            "minecraft:cobblestone",
            "minecraft:oak_planks",
            "minecraft:oak_log",
            "minecraft:oak_slab",
            "minecraft:oak_stairs",
            "minecraft:oak_fence",
            "minecraft:glass_pane",
            "minecraft:glass",
            "minecraft:dirt",
            "minecraft:gravel",
            "minecraft:sand",
            "minecraft:bricks",
            "minecraft:torch",
            "minecraft:lantern",
            "minecraft:air"
        );
    }
}
