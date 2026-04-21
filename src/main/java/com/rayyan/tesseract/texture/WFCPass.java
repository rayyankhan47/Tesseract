package com.rayyan.tesseract.texture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;
import com.rayyan.tesseract.plan.BoundingBox;
import com.rayyan.tesseract.plan.ElementSpec;
import com.rayyan.tesseract.plan.MajorMass;
import com.rayyan.tesseract.plan.StructuralZone;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * §9.2 — 2D WFC-style tiling on horizontal floors of zones tagged via
 * {@code ElementSpec.parameters.wfc_tileset}. Deterministic from
 * {@code seed ^ spec.id().hashCode()} (§9.2.3).
 */
public final class WFCPass {

    private static final String PREFIX = "/wfc_tilesets/";

    private WFCPass() {}

    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, long seed) {
        if (state == null || ops == null || ops.isEmpty()) return ops;
        for (ElementSpec spec : state.elementSpecsView()) {
            if (spec.parameters() == null || !spec.parameters().has("wfc_tileset")) continue;
            String name = spec.parameters().get("wfc_tileset").getAsString();
            Tileset ts = load(name);
            if (ts == null) continue;
            StructuralZone zone = findZone(state, spec.zoneLabel());
            MajorMass mass = findMass(state, spec.massLabel());
            if (zone == null || mass == null) continue;
            BoundingBox b = mass.bounds().clampTo(16);
            int yFloor = zone.yMin();
            long cellSeed = seed ^ (long) spec.id().hashCode() * 0x9E3779B97F4A7C15L;
            Random r = new Random(cellSeed);
            int gw = b.maxX() - b.minX() + 1;
            int gh = b.maxZ() - b.minZ() + 1;
            if (gw < 1 || gh < 1) continue;
            int[][] grid = collapse(gw, gh, ts, r);
            for (BlockOp op : ops) {
                if (op == null || op.y != yFloor) continue;
                if (op.x < b.minX() || op.x > b.maxX() || op.z < b.minZ() || op.z > b.maxZ()) continue;
                int gi = op.x - b.minX();
                int gj = op.z - b.minZ();
                if (gi >= 0 && gi < gw && gj >= 0 && gj < gh) {
                    int t = grid[gi][gj];
                    op.block = ts.blocks.get(Math.min(t, ts.blocks.size() - 1));
                }
            }
        }
        return ops;
    }

    private static int[][] collapse(int gw, int gh, Tileset ts, Random r) {
        int n = ts.blocks.size();
        int[][] g = new int[gw][gh];
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                int left = i > 0 ? g[i - 1][j] : -1;
                int up = j > 0 ? g[i][j - 1] : -1;
                List<Integer> ok = new ArrayList<>();
                for (int t = 0; t < n; t++) {
                    if (left >= 0 && !ts.compat[left][t]) continue;
                    if (up >= 0 && !ts.compat[up][t]) continue;
                    ok.add(t);
                }
                if (ok.isEmpty()) g[i][j] = r.nextInt(n);
                else g[i][j] = ok.get(r.nextInt(ok.size()));
            }
        }
        return g;
    }

    private static Tileset load(String name) {
        String path = PREFIX + name + ".json";
        try (InputStream in = WFCPass.class.getResourceAsStream(path)) {
            if (in == null) {
                try (InputStream def = WFCPass.class.getResourceAsStream(PREFIX + "default.json")) {
                    if (def == null) return null;
                    return parse(new String(def.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Tileset parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<String> blocks = new ArrayList<>();
        JsonArray ba = o.getAsJsonArray("blocks");
        for (var el : ba) blocks.add(el.getAsString());
        int n = blocks.size();
        boolean[][] compat = new boolean[n][n];
        JsonArray ca = o.getAsJsonArray("compat");
        for (int i = 0; i < n; i++) {
            JsonArray row = ca.get(i).getAsJsonArray();
            for (int j = 0; j < n; j++) {
                compat[i][j] = row.get(j).getAsBoolean();
            }
        }
        return new Tileset(blocks, compat);
    }

    private record Tileset(List<String> blocks, boolean[][] compat) {}

    private static StructuralZone findZone(BuildState state, String label) {
        if (label == null) return null;
        for (StructuralZone z : state.zoneSpecsView()) {
            if (label.equals(z.label())) return z;
        }
        return null;
    }

    private static MajorMass findMass(BuildState state, String label) {
        if (state.massPlan() == null || label == null) return null;
        for (MajorMass m : state.massPlan().masses()) {
            if (label.equals(m.label())) return m;
        }
        return null;
    }
}
