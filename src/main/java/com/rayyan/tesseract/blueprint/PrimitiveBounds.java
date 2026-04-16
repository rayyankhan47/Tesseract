package com.rayyan.tesseract.blueprint;

/**
 * Resolved blueprint-local bounding box for one compiled primitive.
 *
 * Produced by {@link BlueprintCompiler} and stored in
 * {@link CompiledBlueprint#primitiveBounds()} so downstream stages
 * (VisualCriticAgent, DetailAgent) can reason about where each primitive
 * landed without re-parsing the blueprint.
 *
 * All coordinates are blueprint-local (not world coordinates).
 */
public record PrimitiveBounds(
        int originX, int originY, int originZ,
        int sizeX,   int sizeY,   int sizeZ
) {
    /** Exclusive max X in blueprint space. */
    public int maxX() { return originX + sizeX; }
    /** Exclusive max Y in blueprint space. */
    public int maxY() { return originY + sizeY; }
    /** Exclusive max Z in blueprint space. */
    public int maxZ() { return originZ + sizeZ; }

    /** Y coordinate of the top face (one block above the topmost block). */
    public int topFaceY() { return originY + sizeY; }

    @Override
    public String toString() {
        return "(" + originX + "," + originY + "," + originZ + ")+" +
               sizeX + "×" + sizeY + "×" + sizeZ;
    }
}
