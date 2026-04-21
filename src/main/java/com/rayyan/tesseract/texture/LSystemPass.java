package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * §9.3.1 — lightweight L-system expansion; grows short moss columns on
 * eligible exterior stone faces when {@link com.rayyan.tesseract.plan.MassPlan#age()} &gt; 0.5.
 */
public final class LSystemPass {

    private LSystemPass() {}

    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, long seed) {
        double age = 0.5;
        if (state != null && state.massPlan() != null) age = state.massPlan().age();
        if (age <= 0.5 || ops == null || ops.isEmpty()) return ops;

        Random r = new Random(seed ^ 0xDEECE5L);
        HashSet<Long> occ = new HashSet<>(ops.size() * 2);
        for (BlockOp o : ops) {
            if (o != null) occ.add(pack(o.x, o.y, o.z));
        }

        List<BlockOp> add = new ArrayList<>();
        String pattern = expandLSystem(5);
        for (BlockOp o : ops) {
            if (o == null || o.block == null) continue;
            if (!isStoneFamily(o.block)) continue;
            if (!isExterior(occ, o.x, o.y, o.z)) continue;
            if (r.nextDouble() > age * 0.12) continue;
            int y = o.y + 1;
            int len = Math.min(pattern.length(), 5);
            for (int k = 0; k < len; k++) {
                if (pattern.charAt(k) != 'F' && pattern.charAt(k) != '+') continue;
                long pk = pack(o.x, y, o.z);
                if (occ.contains(pk)) {
                    y++;
                    continue;
                }
                BlockOp m = new BlockOp();
                m.x = o.x;
                m.y = y;
                m.z = o.z;
                m.block = "moss_block";
                add.add(m);
                occ.add(pk);
                y++;
            }
        }
        ops.addAll(add);
        return ops;
    }

    /** Deterministic expansion capped in length (rule F → F[+F][-F]F). */
    static String expandLSystem(int depth) {
        String s = "F";
        for (int i = 0; i < depth && s.length() < 120; i++) {
            StringBuilder n = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c == 'F') n.append("F[+F][-F]F");
                else n.append(c);
            }
            s = n.toString();
        }
        return s;
    }

    private static boolean isStoneFamily(String b) {
        String n = WeatheringPalette.norm(b);
        return n.contains("stone") || n.contains("brick") || n.contains("cobble");
    }

    private static boolean isExterior(HashSet<Long> occ, int x, int y, int z) {
        return !occ.contains(pack(x - 1, y, z)) || !occ.contains(pack(x + 1, y, z))
                || !occ.contains(pack(x, y - 1, z)) || !occ.contains(pack(x, y + 1, z))
                || !occ.contains(pack(x, y, z - 1)) || !occ.contains(pack(x, y, z + 1));
    }

    private static long pack(int x, int y, int z) {
        long ux = ((long) x + (1 << 20)) & ((1L << 21) - 1L);
        long uy = ((long) y + (1 << 20)) & ((1L << 21) - 1L);
        long uz = ((long) z + (1 << 20)) & ((1L << 21) - 1L);
        return (ux << 42) | (uy << 21) | uz;
    }
}
