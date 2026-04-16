package com.rayyan.tesseract.blueprint;

import com.rayyan.tesseract.agent.BlockOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Blueprint → block-ops compiler.
 *
 * <p>Primitives are already topologically ordered by the Blueprint parser
 * (each {@code on} reference must point to an earlier primitive). The compiler
 * iterates in array order, resolving parent bounds on demand. No LLM calls;
 * no randomness; compiling the same blueprint twice produces byte-identical output.
 *
 * <p>Deduplication: all ops are inserted into a {@link LinkedHashMap} keyed by
 * blueprint-local position. Later primitives override earlier ones at the same
 * position (openings, doors, windows "win" against the wall fill behind them).
 * Insertion order is preserved so the placement animation remains spatially coherent.
 *
 * <p>Bounds clamping: any op whose blueprint-local coordinate is outside
 * {@code [0, bounds.sizeX/Y/Z)} is clamped to the nearest edge. If more than
 * 20% of a primitive's emitted ops were clamped a WARNING is logged.
 */
public final class BlueprintCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.blueprint.compiler");
    private static final float CLAMP_WARN_RATIO = 0.20f;

    private BlueprintCompiler() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Compiles a validated {@link Blueprint} into a {@link CompiledBlueprint}.
     *
     * @param bp a parsed, structurally valid Blueprint
     * @return compiled result with block ops and per-primitive bounds
     * @throws BlueprintCompileException on unresolvable reference, unknown type, or empty output
     */
    public static CompiledBlueprint compile(Blueprint bp) throws BlueprintCompileException {
        long t0 = System.currentTimeMillis();
        CompileContext ctx = new CompileContext(bp.bounds);

        for (Primitive p : bp.primitives) {
            ctx.beginPrimitive(p.id);
            PrimitiveBounds bounds = dispatchCompile(p, ctx);
            ctx.resolvedBounds.put(p.id, bounds);
            warnIfHighClampRatio(ctx, p);
        }

        if (ctx.accumulator.isEmpty()) {
            throw new BlueprintCompileException(
                    "Blueprint '" + bp.name + "' compiled to zero block ops. "
                    + "Check primitive params and ensure they fit inside bounds " + bp.bounds + ".");
        }

        List<BlockOp> ops = List.copyOf(ctx.accumulator.values());
        Map<String, PrimitiveBounds> boundsMap = Map.copyOf(ctx.resolvedBounds);

        LOGGER.info("BlueprintCompiler: '{}' → {} ops, {} primitives, {}ms",
                bp.name, ops.size(), bp.primitives.size(),
                System.currentTimeMillis() - t0);
        return new CompiledBlueprint(ops, boundsMap);
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private static PrimitiveBounds dispatchCompile(Primitive p, CompileContext ctx)
            throws BlueprintCompileException {
        return switch (p.type) {
            case "platform"      -> PrimitiveCompilers.compilePlatform(p, ctx);
            case "walls"         -> PrimitiveCompilers.compileWalls(p, ctx);
            case "wall_segment"  -> PrimitiveCompilers.compileWallSegment(p, ctx);
            case "gable_roof"    -> PrimitiveCompilers.compileGableRoof(p, ctx);
            case "hip_roof"      -> PrimitiveCompilers.compileHipRoof(p, ctx);
            case "flat_roof"     -> PrimitiveCompilers.compileFlatRoof(p, ctx);
            case "column"        -> PrimitiveCompilers.compileColumn(p, ctx);
            case "arch"          -> PrimitiveCompilers.compileArch(p, ctx);
            case "staircase"     -> PrimitiveCompilers.compileStaircase(p, ctx);
            case "frame"         -> PrimitiveCompilers.compileFrame(p, ctx);
            default -> throw new BlueprintCompileException(
                    "Unknown primitive type '" + p.type + "' on '" + p.id + "'");
        };
    }

    private static void warnIfHighClampRatio(CompileContext ctx, Primitive p) {
        if (ctx.emittedForPrimitive > 0
                && ctx.clampRatio() > CLAMP_WARN_RATIO) {
            LOGGER.warn("BlueprintCompiler: primitive '{}' (type='{}') had {:.0f}% ops clamped to bounds "
                    + "({}/{} ops). Check its params.",
                    p.id, p.type,
                    ctx.clampRatio() * 100,
                    ctx.clampedForPrimitive, ctx.emittedForPrimitive);
        }
    }

    // -------------------------------------------------------------------------
    // CompileContext — shared mutable accumulator passed to all primitive compilers
    // -------------------------------------------------------------------------

    /**
     * Package-accessible context object threaded through every primitive compiler.
     * Holds the accumulator, the per-primitive clamp counters, and the resolved
     * parent bounds map.
     */
    static final class CompileContext {

        final Blueprint.Bounds bounds;

        /** Tracks resolved PrimitiveBounds after each primitive is compiled. */
        final Map<String, PrimitiveBounds> resolvedBounds = new LinkedHashMap<>();

        /** Position-keyed accumulator; later ops override earlier at the same position. */
        final LinkedHashMap<String, BlockOp> accumulator = new LinkedHashMap<>();

        /** Diagnostic counters reset per primitive. */
        int emittedForPrimitive;
        int clampedForPrimitive;

        CompileContext(Blueprint.Bounds bounds) {
            this.bounds = bounds;
        }

        void beginPrimitive(String id) {
            emittedForPrimitive  = 0;
            clampedForPrimitive  = 0;
        }

        /**
         * Adds a block op at blueprint-local coordinates.
         * Coordinates outside the blueprint bounds are clamped (not dropped) so the
         * build remains structurally intact even when the LLM slightly over-sizes a primitive.
         */
        void emit(int x, int y, int z, String block) {
            emittedForPrimitive++;
            int cx = clamp(x, 0, bounds.sizeX() - 1);
            int cy = clamp(y, 0, bounds.sizeY() - 1);
            int cz = clamp(z, 0, bounds.sizeZ() - 1);
            if (cx != x || cy != y || cz != z) clampedForPrimitive++;

            String key = cx + "," + cy + "," + cz;
            BlockOp op = new BlockOp();
            op.x = cx; op.y = cy; op.z = cz; op.block = block;
            accumulator.put(key, op);
        }

        float clampRatio() {
            return emittedForPrimitive == 0 ? 0f
                    : (float) clampedForPrimitive / emittedForPrimitive;
        }

        /** Returns the parent's resolved bounds, or null if this primitive has no {@code on}. */
        PrimitiveBounds parentBounds(Primitive p) {
            if (p.on == null) return null;
            return resolvedBounds.get(p.on);
        }

        /**
         * Returns the parent's resolved bounds.
         * @throws BlueprintCompileException if {@code on} is null or unresolved.
         */
        PrimitiveBounds requireParentBounds(Primitive p) throws BlueprintCompileException {
            if (p.on == null) {
                throw new BlueprintCompileException(
                        "Primitive '" + p.id + "' (type='" + p.type + "') requires an 'on' parent "
                        + "reference but none was provided.");
            }
            PrimitiveBounds pb = resolvedBounds.get(p.on);
            if (pb == null) {
                throw new BlueprintCompileException(
                        "Primitive '" + p.id + "' references parent '" + p.on
                        + "' which has not been compiled yet (ordering bug).");
            }
            return pb;
        }

        private static int clamp(int val, int min, int max) {
            return Math.max(min, Math.min(max, val));
        }
    }
}
