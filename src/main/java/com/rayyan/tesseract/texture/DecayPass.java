package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;

import java.util.List;
import java.util.Random;

/**
 * §9.3.2 — short cellular pass: damaged cells propagate one step along
 * stone-like materials (visual clusters of cracked variants).
 */
public final class DecayPass {

    private static final int STEPS = 4;

    private DecayPass() {}

    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, long seed) {
        if (ops == null || ops.isEmpty()) return ops;
        double age = 0.5;
        if (state != null && state.massPlan() != null) age = state.massPlan().age();
        if (age < 0.35) return ops;

        Random r = new Random(seed ^ 0xCAFEF00DL);

        for (int step = 0; step < STEPS; step++) {
            boolean[] damaged = new boolean[ops.size()];
            for (int i = 0; i < ops.size(); i++) {
                BlockOp o = ops.get(i);
                if (o == null || o.block == null) continue;
                if (isCracked(o.block)) damaged[i] = true;
            }
            if (step == 0) {
                for (int i = 0; i < ops.size(); i++) {
                    BlockOp o = ops.get(i);
                    if (o == null || o.block == null) continue;
                    if (!damaged[i] && canDecay(o.block) && r.nextDouble() < 0.045 * age) {
                        damaged[i] = true;
                    }
                }
            }
            for (int i = 0; i < ops.size(); i++) {
                BlockOp o = ops.get(i);
                if (o == null || o.block == null) continue;
                if (!canDecay(o.block)) continue;
                int n = countDamagedNeighbors(ops, damaged, i);
                if (n > 0 && r.nextDouble() < 0.22 + age * 0.1) {
                    o.block = toCracked(o.block);
                }
            }
        }
        return ops;
    }

    private static boolean isCracked(String b) {
        String n = WeatheringPalette.norm(b);
        return n.contains("cracked");
    }

    private static boolean canDecay(String b) {
        String n = WeatheringPalette.norm(b);
        return (n.contains("stone_brick") || n.contains("brick")) && !n.contains("cracked");
    }

    private static String toCracked(String b) {
        String n = WeatheringPalette.norm(b);
        if (n.contains("mossy")) return "cracked_stone_bricks";
        if (n.contains("deepslate")) return "cracked_deepslate_bricks";
        return "cracked_stone_bricks";
    }

    private static int countDamagedNeighbors(List<BlockOp> ops, boolean[] damaged, int idx) {
        BlockOp o = ops.get(idx);
        int c = 0;
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        for (int k = 0; k < 6; k++) {
            int nx = o.x + dx[k];
            int ny = o.y + dy[k];
            int nz = o.z + dz[k];
            int j = indexOf(ops, nx, ny, nz);
            if (j >= 0 && damaged[j]) c++;
        }
        return c;
    }

    private static int indexOf(List<BlockOp> ops, int x, int y, int z) {
        for (int i = 0; i < ops.size(); i++) {
            BlockOp o = ops.get(i);
            if (o != null && o.x == x && o.y == y && o.z == z) return i;
        }
        return -1;
    }
}
