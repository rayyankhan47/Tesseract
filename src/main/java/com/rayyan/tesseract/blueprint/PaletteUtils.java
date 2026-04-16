package com.rayyan.tesseract.blueprint;

import com.rayyan.tesseract.agent.BuildSpec;
import com.rayyan.tesseract.agent.GenerationAgent;

import java.util.List;

/**
 * Shared palette-building utility.
 *
 * Produces the focused block-ID list used by BlueprintPlanningAgent as the
 * only palette the LLM may reference, and by PlacementAgent for validation.
 *
 * The actual logic lives in {@link GenerationAgent#buildFocusedPalette(BuildSpec)}
 * for now and will be migrated here in Step 4 when that class is deleted.
 */
public final class PaletteUtils {

    private PaletteUtils() {}

    /**
     * Builds a focused palette of ~50–80 block IDs from a {@link BuildSpec}.
     *
     * @param spec the interpreted build spec; may be {@code null} (universal set only)
     */
    public static List<String> buildFocusedPalette(BuildSpec spec) {
        return GenerationAgent.buildFocusedPalette(spec);
    }
}
