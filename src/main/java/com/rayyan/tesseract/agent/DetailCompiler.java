package com.rayyan.tesseract.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles {@link Detail} decoration items into {@link BlockOp} lists.
 *
 * <p>Mirrors the structure of {@code PrimitiveCompilers} but is much simpler:
 * each detail type maps to at most a handful of block ops.
 */
final class DetailCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.detail_compiler");

    private DetailCompiler() {}

    /**
     * Compiles a single detail into block ops.
     *
     * @param d the detail to compile; must not be null
     * @return list of block ops (may be empty if the detail is malformed)
     */
    static List<BlockOp> compile(Detail d) {
        if (d.type == null) {
            LOGGER.warn("DetailCompiler: skipping detail with null type");
            return List.of();
        }
        return switch (d.type) {
            case "torch"      -> compileTorch(d);
            case "decoration" -> compileSingle(d);
            case "sign"       -> compileSingle(d);
            case "fill_line"  -> compileFillLine(d);
            default -> {
                LOGGER.warn("DetailCompiler: unknown detail type '{}' — skipping", d.type);
                yield List.of();
            }
        };
    }

    // -------------------------------------------------------------------------

    private static List<BlockOp> compileTorch(Detail d) {
        if (!hasPos(d)) return List.of();
        String block = resolveBlock(d.block, "minecraft:torch");
        if (d.face != null && !d.face.equals("floor") && !block.contains("wall_torch")) {
            block = block.replace("minecraft:torch", "minecraft:wall_torch");
        }
        return List.of(op(d.pos[0], d.pos[1], d.pos[2], block));
    }

    private static List<BlockOp> compileSingle(Detail d) {
        if (!hasPos(d)) return List.of();
        return List.of(op(d.pos[0], d.pos[1], d.pos[2], resolveBlock(d.block, "minecraft:stone")));
    }

    private static List<BlockOp> compileFillLine(Detail d) {
        if (!hasPos(d) || d.to == null || d.to.length < 3) {
            LOGGER.warn("DetailCompiler: fill_line missing 'to' — skipping");
            return List.of();
        }
        String block = resolveBlock(d.block, "minecraft:stone");
        List<BlockOp> ops = new ArrayList<>();

        int x0 = d.pos[0], y0 = d.pos[1], z0 = d.pos[2];
        int x1 = d.to[0],  y1 = d.to[1],  z1 = d.to[2];

        int dx = Integer.signum(x1 - x0);
        int dy = Integer.signum(y1 - y0);
        int dz = Integer.signum(z1 - z0);

        int x = x0, y = y0, z = z0;
        int limit = Math.min(Math.abs(x1-x0) + Math.abs(y1-y0) + Math.abs(z1-z0) + 1, 256);

        for (int i = 0; i < limit; i++) {
            ops.add(op(x, y, z, block));
            if (x == x1 && y == y1 && z == z1) break;
            if (x != x1) x += dx;
            else if (y != y1) y += dy;
            else if (z != z1) z += dz;
        }
        return ops;
    }

    private static BlockOp op(int x, int y, int z, String block) {
        BlockOp o = new BlockOp();
        o.x = x; o.y = y; o.z = z; o.block = block;
        return o;
    }

    // -------------------------------------------------------------------------

    private static boolean hasPos(Detail d) {
        if (d.pos == null || d.pos.length < 3) {
            LOGGER.warn("DetailCompiler: detail type '{}' missing 'pos' — skipping", d.type);
            return false;
        }
        return true;
    }

    private static String resolveBlock(String block, String fallback) {
        if (block == null || block.isBlank()) return fallback;
        return block.contains(":") ? block : "minecraft:" + block;
    }
}
