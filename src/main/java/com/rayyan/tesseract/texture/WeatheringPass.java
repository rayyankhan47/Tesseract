package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;

import java.util.HashSet;
import java.util.List;

/**
 * §9.1 — 3D Perlin sampling per block; substitutes into weathered variants
 * using {@link WeatheringPalette}. Respects age (§9.1.2) and rough
 * altitude / interior heuristics (§9.1.3).
 */
public final class WeatheringPass {

    private static final double NOISE_SCALE = 0.31;

    private WeatheringPass() {}

    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, long seed) {
        if (ops == null || ops.isEmpty()) return ops;
        WeatheringPalette pal = WeatheringPalette.loadDefault();
        double age = 0.5;
        if (state != null && state.massPlan() != null) {
            age = state.massPlan().age();
        }
        PerlinNoise3D noise = new PerlinNoise3D(seed ^ 0x9E3779B97F4A7C15L);

        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (BlockOp o : ops) {
            if (o == null) continue;
            yMin = Math.min(yMin, o.y);
            yMax = Math.max(yMax, o.y);
        }
        int ySpan = Math.max(1, yMax - yMin);

        HashSet<Long> occ = new HashSet<>(ops.size() * 2);
        for (BlockOp o : ops) {
            if (o != null) occ.add(pack(o.x, o.y, o.z));
        }

        for (BlockOp o : ops) {
            if (o == null || o.block == null) continue;
            boolean interior = isInterior(occ, o.x, o.y, o.z);
            double nx = noise.noise01(o.x * NOISE_SCALE, o.y * NOISE_SCALE, o.z * NOISE_SCALE);
            double mossBias = 1.0 - (o.y - yMin) / (double) ySpan;
            double alt01 = (o.y - yMin) / (double) ySpan;
            String next = pal.substitute(o.block, nx, age, mossBias, interior, alt01);
            if (!next.equals(o.block)) {
                o.block = next;
            }
        }
        return ops;
    }

    private static boolean isInterior(HashSet<Long> occ, int x, int y, int z) {
        return occ.contains(pack(x - 1, y, z)) && occ.contains(pack(x + 1, y, z))
                && occ.contains(pack(x, y - 1, z)) && occ.contains(pack(x, y + 1, z))
                && occ.contains(pack(x, y, z - 1)) && occ.contains(pack(x, y, z + 1));
    }

    private static long pack(int x, int y, int z) {
        long ux = ((long) x + (1 << 20)) & ((1L << 21) - 1L);
        long uy = ((long) y + (1 << 20)) & ((1L << 21) - 1L);
        long uz = ((long) z + (1 << 20)) & ((1L << 21) - 1L);
        return (ux << 42) | (uy << 21) | uz;
    }
}
