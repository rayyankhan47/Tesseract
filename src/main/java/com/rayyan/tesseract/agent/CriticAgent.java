package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.gumloop.GumloopPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 of the build pipeline — programmatic validation of a single component's block array.
 *
 * No LLM involved. Five checks, applied in order:
 *   1. Null / empty
 *   2. Palette conformance
 *   3. In-bounds coordinates
 *   4. Floating block detection (structural support)
 *   5. Budget enforcement (non-fatal truncation — handled in Step 7.3)
 */
public final class CriticAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic");

    private CriticAgent() {}

    /**
     * Validates the generated block operations for one component.
     *
     * @param ops       block operations produced by GenerationAgent
     * @param component the component plan (provides bounding box and id)
     * @param palette   allowed block IDs (full {@code minecraft:} form)
     * @return a {@link CriticResult} — passed or failed with a human-readable reason
     */
    public static CriticResult validate(List<GumloopPayload.BlockOp> ops,
                                        ComponentPlan component,
                                        List<String> palette) {
        // Check 1 — null / empty
        if (ops == null || ops.isEmpty()) {
            return CriticResult.fail("component generated zero blocks");
        }

        // Check 2 — palette conformance
        List<String> violations = new ArrayList<>();
        for (GumloopPayload.BlockOp op : ops) {
            if (op.block == null || op.block.isBlank()) {
                violations.add("<null>");
                continue;
            }
            String id = op.block.contains(":") ? op.block : "minecraft:" + op.block;
            if (!palette.contains(id)) {
                violations.add(op.block);
            }
        }
        if (!violations.isEmpty()) {
            return CriticResult.fail("blocks not in palette: " + violations.subList(0, Math.min(5, violations.size())));
        }

        // Check 3 — in-bounds coordinates
        for (GumloopPayload.BlockOp op : ops) {
            if (op.x < 0 || op.x >= component.sizeX
                    || op.y < 0 || op.y >= component.sizeY
                    || op.z < 0 || op.z >= component.sizeZ) {
                return CriticResult.fail(String.format(
                        "block out of bounds at (%d,%d,%d) — component box is %dx%dx%d",
                        op.x, op.y, op.z, component.sizeX, component.sizeY, component.sizeZ));
            }
        }

        LOGGER.debug("CriticAgent: component '{}' passed checks 1–3 ({} blocks).", component.name, ops.size());
        return CriticResult.pass();
    }
}
