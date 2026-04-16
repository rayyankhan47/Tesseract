package com.rayyan.tesseract.blueprint;

import java.util.List;

/**
 * Root Blueprint DSL object.
 *
 * <p>Immutable after construction via {@link BlueprintParser#parse(String)}.
 *
 * <p>A Blueprint describes a Minecraft building using semantic
 * {@link Primitive} entries.  The deterministic {@link BlueprintCompiler}
 * expands these into a flat {@link CompiledBlueprint} containing block ops
 * ready for {@code PlacementAgent}.
 */
public final class Blueprint {

    /**
     * Bounding box of the entire blueprint in blueprint-local space.
     * All primitives must fit within [0, sizeX) × [0, sizeY) × [0, sizeZ).
     */
    public record Bounds(int sizeX, int sizeY, int sizeZ) {
        @Override
        public String toString() {
            return sizeX + "×" + sizeY + "×" + sizeZ;
        }
    }

    // ---- Fields ----

    /** Human label used for logging. */
    public final String name;

    /** Bounding box; all primitive coordinates must stay inside these limits. */
    public final Bounds bounds;

    /**
     * Ordered list of primitives.  The first entry must have no {@code on}
     * reference.  Every {@code on} must resolve to an earlier entry.
     */
    public final List<Primitive> primitives;

    /**
     * Raw JSON string returned by the planner — kept for debugging and
     * for passing to the VisualCriticAgent.
     */
    public final String rawJson;

    Blueprint(String name, Bounds bounds, List<Primitive> primitives, String rawJson) {
        this.name       = name;
        this.bounds     = bounds;
        this.primitives = List.copyOf(primitives); // defensive copy
        this.rawJson    = rawJson;
    }

    @Override
    public String toString() {
        return "Blueprint{name='" + name + "', bounds=" + bounds
               + ", primitives=" + primitives.size() + "}";
    }
}
