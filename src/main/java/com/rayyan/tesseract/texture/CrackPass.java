package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * §9.3.3 — a few Bresenham 3D fracture lines across large runs of
 * {@code stone_bricks} / {@code stone}.
 */
public final class CrackPass {

    private static final int MAX_LINES = 6;

    private CrackPass() {}

    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, long seed) {
        if (ops == null || ops.isEmpty()) return ops;
        double age = 0.5;
        if (state != null && state.massPlan() != null) age = state.massPlan().age();
        if (age < 0.4) return ops;

        Random r = new Random(seed ^ 0xBADC0DEL);
        List<BlockOp> candidates = new ArrayList<>();
        for (BlockOp o : ops) {
            if (o == null || o.block == null) continue;
            String n = WeatheringPalette.norm(o.block);
            if (n.contains("stone_brick") || n.equals("stone")) candidates.add(o);
        }
        if (candidates.size() < 2) return ops;

        for (int line = 0; line < MAX_LINES; line++) {
            BlockOp a = candidates.get(r.nextInt(candidates.size()));
            BlockOp b = candidates.get(r.nextInt(candidates.size()));
            if (a == b) continue;
            fractureLine(ops, a.x, a.y, a.z, b.x, b.y, b.z, age, r);
        }
        return ops;
    }

    /** Evenly sampled segment — cheap Bresenham-style coverage for §9.3.3. */
    private static void fractureLine(List<BlockOp> ops, int x0, int y0, int z0,
                                     int x1, int y1, int z1, double age, Random r) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int dz = z1 - z0;
        int steps = Math.max(1, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        for (int i = 0; i <= steps; i++) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            int z = z0 + dz * i / steps;
            if (r.nextDouble() < 0.2 + age * 0.45) {
                replaceAt(ops, x, y, z);
            }
        }
    }

    private static void replaceAt(List<BlockOp> ops, int x, int y, int z) {
        for (BlockOp o : ops) {
            if (o != null && o.x == x && o.y == y && o.z == z) {
                if (o.block != null && WeatheringPalette.norm(o.block).contains("stone")) {
                    o.block = "cracked_stone_bricks";
                }
                return;
            }
        }
    }
}
