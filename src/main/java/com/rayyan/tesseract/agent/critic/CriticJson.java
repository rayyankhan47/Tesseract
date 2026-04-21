package com.rayyan.tesseract.agent.critic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Shared JSON fence strip + opinion parsing for Flash critics. */
final class CriticJson {

    private CriticJson() {}

    static String stripFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    static CriticOpinion parseOpinion(CriticKind kind, String raw) {
        try {
            JsonObject o = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
            double score = o.has("score") && o.get("score").isJsonPrimitive()
                    ? o.get("score").getAsDouble() : 0.0;
            String summary = o.has("summary") && o.get("summary").isJsonPrimitive()
                    ? o.get("summary").getAsString() : "";
            List<String> patches = new ArrayList<>();
            if (o.has("suggested_patches") && o.get("suggested_patches").isJsonArray()) {
                JsonArray arr = o.getAsJsonArray("suggested_patches");
                for (var el : arr) {
                    if (el != null && el.isJsonPrimitive()) patches.add(el.getAsString());
                }
            }
            return new CriticOpinion(kind, clamp01(score), summary, patches, false, "", null);
        } catch (Exception e) {
            return CriticOpinion.skipped(kind, "parse: " + e.getMessage());
        }
    }

    static boolean validOpinionJson(String raw) {
        try {
            JsonObject o = JsonParser.parseString(stripFence(raw)).getAsJsonObject();
            return o.has("score") && o.get("score").isJsonPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
