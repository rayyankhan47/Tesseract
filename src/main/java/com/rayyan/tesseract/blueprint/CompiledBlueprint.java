package com.rayyan.tesseract.blueprint;

import com.rayyan.tesseract.agent.BlockOp;

import java.util.List;
import java.util.Map;

/**
 * Output of {@link BlueprintCompiler}.
 *
 * <p>{@code ops} is a deduplicated, ordered {@link List} of blueprint-local
 * block operations ready to hand to {@code PlacementAgent}.  Later primitives
 * override earlier ones at the same position (e.g. a door opening overrides
 * the wall fill it carves into).
 *
 * <p>{@code primitiveBounds} maps each primitive {@code id} to its resolved
 * blueprint-local bounding box.  Downstream agents (VisualCriticAgent,
 * DetailAgent) use this to reason about structure layout without re-parsing.
 */
public record CompiledBlueprint(
        List<BlockOp>               ops,
        Map<String, PrimitiveBounds> primitiveBounds
) {}
