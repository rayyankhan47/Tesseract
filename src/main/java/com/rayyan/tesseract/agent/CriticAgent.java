package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.agent.BlockOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 of the build pipeline — programmatic validation of a single component's block array.
 *
 * No LLM involved. Four checks, applied in order:
 *   1. Null / empty
 *   2. Palette conformance
 *   3. Floating block detection (structural support)
 *   4. Budget enforcement (non-fatal truncation)
 */
public final class CriticAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic");

    private CriticAgent() {}

    /**
     * Validates the generated block operations for one component.
     *
     * @param ops            block operations produced by GenerationAgent (may be mutated by Check 5)
     * @param component      the component plan (provides bounding box and id)
     * @param palette        allowed block IDs (full {@code minecraft:} form)
     * @param totalMaxBlocks total budget for the whole build (used to derive per-component budget)
     * @param componentCount total number of components (used to derive per-component budget)
     * @return a {@link CriticResult} — passed or failed with a human-readable reason
     */
    public static CriticResult validate(List<BlockOp> ops,
                                        ComponentPlan component,
                                        List<String> palette,
                                        int totalMaxBlocks,
                                        int componentCount) {
        // Check 1 — null / empty
        if (ops == null || ops.isEmpty()) {
            return CriticResult.fail("component generated zero blocks");
        }

        // Check 2 — palette conformance (strip block state properties before lookup)
        List<String> violations = new ArrayList<>();
        for (BlockOp op : ops) {
            if (op.block == null || op.block.isBlank()) {
                violations.add("<null>");
                continue;
            }
            String id = op.block.contains(":") ? op.block : "minecraft:" + op.block;
            // Strip "[facing=north,...]" bracket notation before palette check
            int bracket = id.indexOf('[');
            if (bracket != -1) id = id.substring(0, bracket);
            if (!palette.contains(id)) {
                violations.add(op.block);
            }
        }
        if (!violations.isEmpty()) {
            return CriticResult.fail("blocks not in palette: " + violations.subList(0, Math.min(5, violations.size())));
        }

        // Check 3 — bounds enforcement: remove out-of-bounds ops (non-fatal), fail only if all removed.
        if (component.sizeX > 0 && component.sizeY > 0 && component.sizeZ > 0) {
            int before = ops.size();
            ops.removeIf(op -> op.x < 0 || op.x >= component.sizeX
                             || op.y < 0 || op.y >= component.sizeY
                             || op.z < 0 || op.z >= component.sizeZ);
            int removed = before - ops.size();
            if (removed > 0) {
                LOGGER.warn("CriticAgent: component '{}' had {} out-of-bounds op(s) removed (of {}).",
                        component.name, removed, before);
            }
            if (ops.isEmpty()) {
                return CriticResult.fail(
                        "all generated blocks were outside the component's bounds " +
                        "(x: 0–" + (component.sizeX - 1) +
                        ", y: 0–" + (component.sizeY - 1) +
                        ", z: 0–" + (component.sizeZ - 1) + "). " +
                        "Ensure all y coordinates are between 0 and " + (component.sizeY - 1) + ".");
            }
        }

        // Check 4 — floating block detection (structural support)
        CriticResult floatCheck = checkFloating(ops, component);
        if (!floatCheck.passed()) return floatCheck;

        // Check 5 — budget enforcement (non-fatal: truncate ops, log warning)
        if (componentCount > 0 && totalMaxBlocks > 0) {
            int budget = Math.max(1, totalMaxBlocks / componentCount);
            if (ops.size() > budget) {
                int dropped = ops.size() - budget;
                LOGGER.warn("CriticAgent: component '{}' exceeded budget ({} > {}). Truncating {} blocks.",
                        component.name, ops.size(), budget, dropped);
                // ops is an ArrayList from GenerationAgent.parseOps — safe to mutate via subList
                ops.subList(budget, ops.size()).clear();
            }
        }

        LOGGER.debug("CriticAgent: component '{}' passed all checks ({} blocks).", component.name, ops.size());
        return CriticResult.pass();
    }

    /**
     * Convenience overload — skips budget check when total/count are unknown.
     */
    public static CriticResult validate(List<BlockOp> ops,
                                        ComponentPlan component,
                                        List<String> palette) {
        return validate(ops, component, palette, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Check 4 — floating block detection
    // -------------------------------------------------------------------------

    /**
     * For any block where {@code op.y > 0}, there must exist another op in the
     * array at {@code (op.x, op.y - 1, op.z)} OR the block is on the ground row
     * of this component ({@code component.originY == 0 && op.y == 0} — already
     * excluded by the y>0 guard). Decorative blocks exempt from this check are
     * torches, lanterns, and any block whose ID ends in _slab, _stairs, _fence,
     * or _trapdoor.
     *
     * If more than 10 % of non-exempt blocks are floating, the check fails.
     */
    private static CriticResult checkFloating(List<BlockOp> ops, ComponentPlan component) {
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        for (BlockOp op : ops) {
            occupied.add(key(op.x, op.y, op.z));
        }

        int floating = 0;
        int nonExempt = 0;
        for (BlockOp op : ops) {
            if (op.y == 0) continue; // resting on the component's base row — always supported
            if (isExempt(op.block)) continue;
            nonExempt++;
            // Supported if there's a block directly below, OR the component's origin
            // is at y=0 in the build and op.y==1 sits on the world ground (y-1 == ground).
            boolean hasSupport = occupied.contains(key(op.x, op.y - 1, op.z));
            if (!hasSupport) floating++;
        }

        if (nonExempt == 0) return CriticResult.pass();
        double ratio = (double) floating / nonExempt;
        if (ratio > 0.10) {
            return CriticResult.fail(String.format(
                    "too many floating blocks (%d/%d non-exempt blocks have no support below them). " +
                    "Ensure structural blocks at y>0 have a block at y-1.",
                    floating, nonExempt));
        }
        return CriticResult.pass();
    }

    private static boolean isExempt(String blockId) {
        if (blockId == null) return false;
        String id = blockId.toLowerCase();
        return id.contains("torch") || id.contains("lantern")
                || id.endsWith("_slab") || id.endsWith("_stairs")
                || id.endsWith("_fence") || id.endsWith("_trapdoor");
    }

    /** Pack (x, y, z) into a single long for use as a hash-set key. */
    private static long key(int x, int y, int z) {
        return ((long)(x + 512) << 20) | ((long)(y + 512) << 10) | (z + 512);
    }
}
