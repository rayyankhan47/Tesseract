package com.rayyan.tesseract.plan;

/**
 * Inclusive integer AABB in 16³ voxel-mass-local coordinates.
 *
 * <p>All axes are in the same space as {@link com.rayyan.tesseract.agent.VoxelMass},
 * so {@link #containsVoxel} can be checked directly against voxel indices. The
 * box is inclusive on both endpoints; a 1-voxel cube is {@code min == max}.
 *
 * <p>L1 produces these in mass-space; the orchestrator scales to world-space
 * when dispatching to L4 / placement.
 */
public record BoundingBox(int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ) {

    public BoundingBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "BoundingBox: min must be <= max on every axis, got "
                            + "[" + minX + "," + minY + "," + minZ + "] .. "
                            + "[" + maxX + "," + maxY + "," + maxZ + "]");
        }
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    /** Inclusive voxel count. */
    public int volume() { return sizeX() * sizeY() * sizeZ(); }

    public boolean containsVoxel(int x, int y, int z) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    /** Clamps the box into {@code [0, resolution)} on every axis. */
    public BoundingBox clampTo(int resolution) {
        int r = resolution - 1;
        return new BoundingBox(
                Math.max(0, Math.min(r, minX)),
                Math.max(0, Math.min(r, minY)),
                Math.max(0, Math.min(r, minZ)),
                Math.max(0, Math.min(r, maxX)),
                Math.max(0, Math.min(r, maxY)),
                Math.max(0, Math.min(r, maxZ)));
    }

    /** Smallest box enclosing both inputs. */
    public static BoundingBox union(BoundingBox a, BoundingBox b) {
        if (a == null) return b;
        if (b == null) return a;
        return new BoundingBox(
                Math.min(a.minX, b.minX),
                Math.min(a.minY, b.minY),
                Math.min(a.minZ, b.minZ),
                Math.max(a.maxX, b.maxX),
                Math.max(a.maxY, b.maxY),
                Math.max(a.maxZ, b.maxZ));
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + "]..["
                   + maxX + "," + maxY + "," + maxZ + "]";
    }
}
