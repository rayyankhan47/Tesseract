package com.rayyan.tesseract.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D mass sketch extracted from the concept image by {@code MassExtractionAgent}.
 *
 * <p>A {@code VoxelMass} is a boolean occupancy grid of fixed resolution
 * (default 16³). Voxel indices are {@code [x][y][z]}; {@code true} means
 * "this cell is part of the building silhouette". The mass is intentionally
 * coarse — it captures the envelope of the structure, not interior detail.
 *
 * <p>This class is the shared truth source for:
 * <ul>
 *   <li>{@link Orchestrator} — derives {@link com.rayyan.tesseract.blueprint.Blueprint.Bounds}
 *       by scaling the mass to the user's selected region.</li>
 *   <li>L1 Architect — sees the mass as an ASCII layer-by-layer diagram.</li>
 *   <li>Every downstream critic — computes silhouette drift vs. this envelope.</li>
 * </ul>
 */
public final class VoxelMass {

    /** Default resolution used when the plan doesn't override it. */
    public static final int DEFAULT_RESOLUTION = 16;

    /** Validation thresholds per REFACTOR_3 §2.1.2. */
    public static final int MIN_FILLED = 20;
    public static final int MAX_FILLED = 2048;

    private final int resolution;
    private final boolean[][][] filled;
    private int filledCount;

    public VoxelMass() {
        this(DEFAULT_RESOLUTION);
    }

    public VoxelMass(int resolution) {
        if (resolution < 2 || resolution > 64) {
            throw new IllegalArgumentException("VoxelMass resolution must be in [2, 64]: " + resolution);
        }
        this.resolution = resolution;
        this.filled = new boolean[resolution][resolution][resolution];
    }

    /**
     * Text-only / Phase-fallback envelope: a centered box in {@link #DEFAULT_RESOLUTION}³
     * sized from {@link BuildSpec} footprint so L1 has a valid silhouette when
     * Imagen and mass extraction are skipped.
     */
    public static VoxelMass syntheticFootprintFromSpec(BuildSpec spec) {
        int w = spec != null ? Math.max(4, spec.width) : 8;
        int h = spec != null ? Math.max(4, spec.height) : 8;
        int d = spec != null ? Math.max(4, spec.depth) : 8;
        int res = DEFAULT_RESOLUTION;
        int sx = Math.max(3, Math.min(res, Math.round(w * (res / 32f))));
        int sy = Math.max(3, Math.min(res, Math.round(h * (res / 32f))));
        int sz = Math.max(3, Math.min(res, Math.round(d * (res / 32f))));
        VoxelMass m = new VoxelMass(res);
        int ox = (res - sx) / 2;
        int oy = 0;
        int oz = (res - sz) / 2;
        for (int x = ox; x < ox + sx && x < res; x++) {
            for (int y = oy; y < oy + sy && y < res; y++) {
                for (int z = oz; z < oz + sz && z < res; z++) {
                    m.add(x, y, z);
                }
            }
        }
        if (!m.isValidDensity()) {
            m = new VoxelMass(res);
            for (int x = 4; x < 12; x++) {
                for (int y = 0; y < 12; y++) {
                    for (int z = 4; z < 12; z++) {
                        m.add(x, y, z);
                    }
                }
            }
        }
        return m;
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /** Marks a voxel as filled. Out-of-range coordinates are silently dropped. */
    public void add(int x, int y, int z) {
        if (!inBounds(x, y, z)) return;
        if (!filled[x][y][z]) {
            filled[x][y][z] = true;
            filledCount++;
        }
    }

    /** Clears a voxel. Out-of-range coordinates are silently dropped. */
    public void remove(int x, int y, int z) {
        if (!inBounds(x, y, z)) return;
        if (filled[x][y][z]) {
            filled[x][y][z] = false;
            filledCount--;
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public int resolution() { return resolution; }
    public int filledCount() { return filledCount; }

    public boolean isFilled(int x, int y, int z) {
        if (!inBounds(x, y, z)) return false;
        return filled[x][y][z];
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < resolution && y >= 0 && y < resolution && z >= 0 && z < resolution;
    }

    /** Returns the filled-voxel count is within [MIN_FILLED, MAX_FILLED]. */
    public boolean isValidDensity() {
        return filledCount >= MIN_FILLED && filledCount <= MAX_FILLED;
    }

    /**
     * Tight bounding box of filled voxels, as {@code [minX, minY, minZ, maxX, maxY, maxZ]}
     * inclusive. Returns null if the mass is empty.
     */
    public int[] tightBounds() {
        if (filledCount == 0) return null;
        int minX = resolution, minY = resolution, minZ = resolution;
        int maxX = -1,         maxY = -1,         maxZ = -1;
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    if (!filled[x][y][z]) continue;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
                }
            }
        }
        return new int[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    /**
     * Flattens filled voxels into {@code [x,y,z]} integer triples.
     * Iteration order is {@code x → y → z}, matching the extraction prompt's
     * documented convention so re-serialisation is deterministic.
     */
    public List<int[]> filledCoords() {
        List<int[]> coords = new ArrayList<>(filledCount);
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    if (filled[x][y][z]) coords.add(new int[] { x, y, z });
                }
            }
        }
        return coords;
    }

    // -------------------------------------------------------------------------
    // ASCII rendering (for L1/L2 prompts per §2.2.2)
    // -------------------------------------------------------------------------

    /**
     * Returns a human-readable layer-by-layer top-down diagram. Each Y layer
     * is printed as a resolution × resolution grid of {@code #} (filled) and
     * {@code .} (empty), z increasing downward, x increasing rightward.
     *
     * <p>Layers are printed bottom-to-top (y=0 first) so the structure reads
     * like a stack.
     */
    public String toAsciiLayers() {
        StringBuilder sb = new StringBuilder();
        sb.append("VoxelMass ").append(resolution).append("³, filled=")
          .append(filledCount).append('\n');
        for (int y = 0; y < resolution; y++) {
            sb.append("-- y=").append(y).append(" --\n");
            for (int z = 0; z < resolution; z++) {
                for (int x = 0; x < resolution; x++) {
                    sb.append(filled[x][y][z] ? '#' : '.');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Compact variant: only prints Y layers that contain at least one filled voxel.
     * Used when prompt token budget matters.
     */
    public String toAsciiLayersCompact() {
        StringBuilder sb = new StringBuilder();
        sb.append("VoxelMass ").append(resolution).append("³, filled=")
          .append(filledCount).append(" (non-empty layers only)\n");
        for (int y = 0; y < resolution; y++) {
            boolean any = false;
            outer:
            for (int z = 0; z < resolution && !any; z++) {
                for (int x = 0; x < resolution; x++) {
                    if (filled[x][y][z]) { any = true; break outer; }
                }
            }
            if (!any) continue;
            sb.append("-- y=").append(y).append(" --\n");
            for (int z = 0; z < resolution; z++) {
                for (int x = 0; x < resolution; x++) {
                    sb.append(filled[x][y][z] ? '#' : '.');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
