package com.rayyan.tesseract.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code /weathering_palette.json} — base block → ordered weathering
 * chain (§9.1.1).
 */
public final class WeatheringPalette {

    public static final String RESOURCE = "/weathering_palette.json";

    private final double baseThreshold;
    private final List<Entry> entries;

    public record Entry(List<String> match, List<String> variants, double thresholdSpread) {}

    public WeatheringPalette(double baseThreshold, List<Entry> entries) {
        this.baseThreshold = baseThreshold;
        this.entries = entries;
    }

    public static WeatheringPalette loadDefault() {
        try (InputStream in = WeatheringPalette.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return fallback();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (Exception e) {
            return fallback();
        }
    }

    static WeatheringPalette parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        double base = root.has("baseThreshold") ? root.get("baseThreshold").getAsDouble() : 0.55;
        List<Entry> list = new ArrayList<>();
        if (root.has("entries") && root.get("entries").isJsonArray()) {
            for (var el : root.getAsJsonArray("entries")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                List<String> match = new ArrayList<>();
                if (o.has("match") && o.get("match").isJsonArray()) {
                    for (var m : o.getAsJsonArray("match")) {
                        if (m.isJsonPrimitive()) match.add(norm(m.getAsString()));
                    }
                }
                List<String> variants = new ArrayList<>();
                if (o.has("variants") && o.get("variants").isJsonArray()) {
                    for (var v : o.getAsJsonArray("variants")) {
                        if (v.isJsonPrimitive()) variants.add(v.getAsString());
                    }
                }
                double spread = o.has("thresholdSpread") ? o.get("thresholdSpread").getAsDouble() : 0.12;
                if (!match.isEmpty() && !variants.isEmpty()) {
                    list.add(new Entry(match, variants, spread));
                }
            }
        }
        return new WeatheringPalette(base, list);
    }

    private static WeatheringPalette fallback() {
        List<Entry> e = List.of(
                new Entry(List.of("stone_bricks", "minecraft:stone_bricks"),
                        List.of("stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks"), 0.12));
        return new WeatheringPalette(0.55, e);
    }

    /**
     * @param mossBias   0–1, higher at low altitude (§9.1.3)
     * @param altitude01 0 = bottom of build, 1 = top — high weathers less
     */
    public String substitute(String blockId, double noise01, double age,
                             double mossBias, boolean interior, double altitude01) {
        String idKey = norm(blockId);
        for (Entry e : entries) {
            if (!e.match.contains(idKey)) continue;
            if (interior && age < 0.99) {
                return blockId;
            }
            double spread = e.thresholdSpread * (0.5 + age);
            double thr = baseThreshold - age * 0.18 + mossBias * 0.12 - altitude01 * 0.08;
            int steps = e.variants.size();
            int best = 0;
            for (int s = 1; s < steps; s++) {
                if (noise01 > thr + spread * (s - 1)) {
                    best = s;
                }
            }
            return e.variants.get(best);
        }
        return blockId;
    }

    static String norm(String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase();
        int b = s.indexOf('[');
        if (b > 0) s = s.substring(0, b);
        if (s.startsWith("minecraft:")) s = s.substring(10);
        return s;
    }
}
