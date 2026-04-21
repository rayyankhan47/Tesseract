package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.render.IsoRenderer;
import com.rayyan.tesseract.selection.Selection;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for turning a {@link VoxelMass} into placement-ready artefacts:
 * a {@link com.rayyan.tesseract.blueprint.Blueprint.Bounds}, a block-op list
 * (useful for compile-path downstream agents), and a debug isometric PNG.
 *
 * <p>Per REFACTOR_3 §2.2.1 the bounds come from the user's selected region
 * when present, falling back to the canonical {@value #DEFAULT_SIZE}³ box.
 */
public final class VoxelMassRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.voxel_mass");

    /** Default cubic size when no selection is provided (§2.2.1). */
    public static final int DEFAULT_SIZE = 24;

    /** Block id used for debug renders. "Stone" reads as a clean silhouette. */
    private static final String DEBUG_BLOCK = "minecraft:stone";

    private VoxelMassRenderer() {}

    // -------------------------------------------------------------------------
    // Bounds derivation (2.2.1)
    // -------------------------------------------------------------------------

    /**
     * Derives blueprint bounds from an optional selection:
     * <ul>
     *   <li>Complete selection → use its {@code getSize()}.</li>
     *   <li>Missing selection → {@value #DEFAULT_SIZE}³.</li>
     * </ul>
     * Dimensions are clamped to at least 4 on every axis.
     */
    public static Blueprint.Bounds deriveBounds(Selection sel) {
        if (sel != null && sel.isComplete()) {
            BlockPos size = sel.getSize();
            if (size != null) {
                return new Blueprint.Bounds(
                        Math.max(4, size.getX()),
                        Math.max(4, size.getY()),
                        Math.max(4, size.getZ()));
            }
        }
        return new Blueprint.Bounds(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE);
    }

    // -------------------------------------------------------------------------
    // Voxel mass → block ops
    // -------------------------------------------------------------------------

    /**
     * Scales the voxel mass into the given blueprint-local bounds and emits a
     * block-op per occupied voxel cell. Voxel resolution usually does not match
     * bounds exactly — each voxel covers {@code bounds/resolution} blocks on
     * each axis, rounded down. Every target block in that cell is painted.
     *
     * @param block block id to fill with (e.g. {@code "minecraft:stone"})
     */
    public static List<BlockOp> toBlockOps(VoxelMass mass, Blueprint.Bounds bounds, String block) {
        if (mass == null || mass.filledCount() == 0) return List.of();
        int r = mass.resolution();
        // Cell extents (blocks per voxel) and offsets. We use integer floor and
        // distribute the remainder so the last cell absorbs any slack.
        int[] xEdges = linspaceEdges(bounds.sizeX(), r);
        int[] yEdges = linspaceEdges(bounds.sizeY(), r);
        int[] zEdges = linspaceEdges(bounds.sizeZ(), r);

        List<BlockOp> ops = new ArrayList<>();
        for (int vx = 0; vx < r; vx++) {
            for (int vy = 0; vy < r; vy++) {
                for (int vz = 0; vz < r; vz++) {
                    if (!mass.isFilled(vx, vy, vz)) continue;
                    for (int x = xEdges[vx]; x < xEdges[vx + 1]; x++) {
                        for (int y = yEdges[vy]; y < yEdges[vy + 1]; y++) {
                            for (int z = zEdges[vz]; z < zEdges[vz + 1]; z++) {
                                BlockOp op = new BlockOp();
                                op.x = x; op.y = y; op.z = z; op.block = block;
                                ops.add(op);
                            }
                        }
                    }
                }
            }
        }
        return ops;
    }

    /**
     * Computes {@code resolution + 1} edge positions dividing the axis range
     * {@code [0, length)} into {@code resolution} contiguous cells. Remainder
     * blocks are distributed starting from the lowest cells, so the cell sizes
     * differ by at most 1.
     */
    static int[] linspaceEdges(int length, int resolution) {
        int[] edges = new int[resolution + 1];
        int base = length / resolution;
        int rem  = length % resolution;
        int pos  = 0;
        for (int i = 0; i < resolution; i++) {
            edges[i] = pos;
            pos += base + (i < rem ? 1 : 0);
        }
        edges[resolution] = length;
        return edges;
    }

    // -------------------------------------------------------------------------
    // Debug PNG (2.2.3)
    // -------------------------------------------------------------------------

    /**
     * Renders the voxel mass as an isometric PNG via {@link IsoRenderer}.
     * The mass is scaled to the supplied bounds and every occupied cell is
     * painted with {@value #DEBUG_BLOCK}.
     */
    public static byte[] renderPng(VoxelMass mass, Blueprint.Bounds bounds, int pixelsPerBlock) {
        List<BlockOp> ops = toBlockOps(mass, bounds, DEBUG_BLOCK);
        if (ops.isEmpty()) return new byte[0];
        return IsoRenderer.renderPng(ops, bounds, pixelsPerBlock);
    }

    /**
     * Writes a {@code voxel_mass.png} debug copy under
     * {@code run/tesseract_debug/concepts/<buildId>/} when the debug system
     * property is set. No-op otherwise.
     */
    public static void writeDebugCopy(byte[] png, String buildId) {
        if (!IsoRenderer.isDebugEnabled()) return;
        if (png == null || png.length == 0) return;
        try {
            String safeId = buildId == null ? "unknown"
                    : buildId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            Path dir = IsoRenderer.debugDir().resolve("concepts").resolve(safeId);
            Files.createDirectories(dir);
            Files.write(dir.resolve("voxel_mass.png"), png);
        } catch (Exception e) {
            LOGGER.warn("VoxelMassRenderer: debug dump failed: {}", e.getMessage());
        }
    }
}
