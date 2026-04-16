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

    // -------------------------------------------------------------------------
    // Prompts and output schema
    // -------------------------------------------------------------------------

    /**
     * Output schema — a flat JSON array of block ops with coordinates relative
     * to the component's origin (NOT world coordinates):
     * [
     *   { "x": 0, "y": 0, "z": 0, "block": "minecraft:stone_bricks" },
     *   { "x": 1, "y": 0, "z": 0, "block": "minecraft:stone_bricks" }
     * ]
     */
    static final String SYSTEM_PROMPT =
        "You are the Generation Agent for a Minecraft building assistant.\n" +
        "Your ONLY job is to generate the exact block placements for ONE named structural component.\n" +
        "\n" +
        "Respond with ONLY a valid JSON array — no markdown fences, no prose, no explanation.\n" +
        "\n" +
        "Each element in the array must have exactly this schema:\n" +
        "{ \"x\": integer, \"y\": integer, \"z\": integer, \"block\": string }\n" +
        "\n" +
        "Rules:\n" +
        "- Coordinates are relative to THIS component's origin — start from (0, 0, 0).\n" +
        "- Use ONLY block IDs from the provided materials list (full 'minecraft:' form).\n" +
        "- Every block must be at a valid coordinate within the component's bounding box.\n" +
        "- Build structurally — walls need foundations, roofs need walls, etc.\n" +
        "- Do NOT place blocks outside the component's bounding box dimensions.\n" +
        "- Do NOT use 'minecraft:air' for intentional gaps — just omit those positions.\n" +
        "- Stay within the max block budget given in context.\n" +
        "IMPORTANT: Respond with ONLY the JSON array. No other text whatsoever.";

    static String buildUserPrompt(ComponentPlan component, BuildSpec spec,
                                   List<ComponentPlan> allComponents,
                                   String priorFailureReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("Component to generate:\n");
        sb.append("  Name: ").append(component.name).append("\n");
        sb.append("  Description: ").append(component.description).append("\n");
        sb.append("  Bounding box: ").append(component.sizeX).append("×")
          .append(component.sizeY).append("×").append(component.sizeZ).append(" blocks\n");
        sb.append("  Origin within build: (").append(component.originX).append(", ")
          .append(component.originY).append(", ").append(component.originZ).append(")\n");

        sb.append("\nAvailable block IDs (use ONLY these):\n");
        List<String> palette = buildFullPaletteFromSpec(spec);
        for (String id : palette) {
            sb.append("  ").append(id).append("\n");
        }

        sb.append("\nMax blocks for this component: ").append(component.sizeX * component.sizeY * component.sizeZ);

        if (allComponents != null && allComponents.size() > 1) {
            sb.append("\n\nOther components in this build (for spatial context — do not place blocks meant for them):\n");
            for (ComponentPlan other : allComponents) {
                if (!other.id.equals(component.id)) {
                    sb.append("  ").append(other.name).append(": ").append(other.description).append("\n");
                }
            }
        }

        if (priorFailureReason != null) {
            sb.append("\n\nPrevious attempt was rejected — fix this issue:\n  ").append(priorFailureReason);
        }

        sb.append("\n\nReturn only the JSON array of block placements.");
        return sb.toString();
    }

    private static List<String> buildFullPaletteFromSpec(BuildSpec spec) {
        List<String> safe = defaultPalette();
        if (spec == null || spec.materials == null) return safe;
        java.util.List<String> combined = new java.util.ArrayList<>(safe);
        for (String mat : spec.materials) {
            String full = mat.contains(":") ? mat : "minecraft:" + mat;
            if (!combined.contains(full)) combined.add(full);
        }
        return java.util.Collections.unmodifiableList(combined);
    }

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
