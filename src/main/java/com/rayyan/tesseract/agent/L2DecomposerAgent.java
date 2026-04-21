package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.MajorMass;
import com.rayyan.tesseract.plan.MassPlan;
import com.rayyan.tesseract.plan.StructuralZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * §5.2 — L2 Decomposer. For each {@link MajorMass} in the L1 plan, produces
 * a list of {@link StructuralZone}s (foundation, body, crown, rhythm bands)
 * using Gemini 2.5 Flash.
 *
 * <p>Zones are returned per-mass as a single JSON call that decomposes all
 * masses together — this gives the LLM the context of the whole building
 * when zoning each mass, which matters for consistency (e.g. the crown of
 * the central tower should align with the crown of its flanking wings).
 *
 * <p>Structural critic (§5.2.3) is inner-loop and optional. It runs as a
 * second Flash call that grades the plan 0–1 against the stated style; if
 * the score drops below {@link #MIN_STRUCTURAL_SCORE} and critic returns a
 * patch, we accept the patched plan. Any critic failure is logged as
 * {@code CRITIC_SKIPPED} and the original zoning commits.
 */
public final class L2DecomposerAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l2");

    public static final double MIN_STRUCTURAL_SCORE = 0.6;

    private static final String SYSTEM_PROMPT = """
            You are the L2 Decomposer. For each major mass produced by the L1
            Architect, decompose it into a small set of vertical structural
            zones (3-6 per mass typical):
              - foundation / plinth (low base, typically 1-3 voxels tall)
              - body / shaft (the main vertical extent)
              - crown (roof, spire, cornice, dome cap)
              - rhythm bands (window courses, belt courses, cornices)

            Every zone lives within its parent mass's Y range. Y-ranges may
            touch but should not overlap excessively; adjacent zones share a
            voxel at most.

            Use feature_hints to suggest what L3 should put in this zone
            ("pointed arch windows in bays of 4", "buttressed corners",
            "crenellated parapet"). Use material_families to suggest broad
            categories ("cut_stone", "timber_trim", "dark_metal_accents");
            do not name specific Minecraft block ids yet — L3 handles that.

            Draw vocabulary from the provided architectural corpus entries.
            Cite the corpus ids you actually used.

            Return a single JSON object — no prose, no markdown fences:
            {
              "zones": [
                {
                  "label": "<snake_case>",
                  "role": "foundation | body | crown | rhythm | appendage",
                  "mass_label": "<matches an L1 mass label>",
                  "y_min": 0,
                  "y_max": 0,
                  "feature_hints": ["..."],
                  "material_families": ["..."],
                  "citing": ["<corpus_id>", ...]
                }
              ],
              "citing": ["<corpus_id>", ...]
            }
            """;

    private static final String STRICT_REMINDER = """
            Return ONLY the JSON object matching the schema. No prose, no
            markdown code fences. Each zone must have every key. y_min and
            y_max must be integers within the parent mass's Y range, and
            every mass_label must match one of the L1 mass labels exactly.
            """;

    private static final String CRITIC_SYSTEM = """
            You are the Structural Critic (L2 inner-loop, optional).
            Score a proposed structural zoning 0-1 on architectural coherence
            for the stated style, then either approve or suggest minimal
            patches. Keep the output terse. Return JSON:
            {
              "score": 0.0,
              "summary": "one sentence",
              "patches": [
                {"op": "replace", "zone": "<label>", "y_min": 0, "y_max": 0,
                 "feature_hints": [...], "material_families": [...]}
              ]
            }
            """;

    private L2DecomposerAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Decomposes every {@link MajorMass} in the state's {@link BuildState#massPlan}.
     * Populates {@link BuildState#zoneSpecs} on success.
     */
    public static CompletableFuture<List<StructuralZone>> run(BuildState state, GeminiClient gemini) {
        if (state.massPlan == null) {
            CompletableFuture<List<StructuralZone>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "L2DecomposerAgent requires BuildState.massPlan"));
            return failed;
        }

        String userPrompt = buildUserPrompt(state);
        List<ImagePart> images = selectedImageParts(state);

        return gemini.call(TaskKind.L2_DECOMPOSE,
                        SYSTEM_PROMPT, userPrompt, images,
                        L2DecomposerAgent::isValidZonesResponse,
                        STRICT_REMINDER, state.costTracker)
                .thenApply(raw -> parseZones(raw, state.massPlan, state))
                .thenCompose(zones -> runStructuralCritic(state, gemini, zones))
                .thenApply(zones -> {
                    state.zoneSpecs.clear();
                    state.zoneSpecs.addAll(zones);
                    for (StructuralZone z : zones) RagAgent.recordCitations(state, z.citing());
                    LOGGER.info("L2 produced {} zones across {} masses",
                            zones.size(), state.massPlan.masses().size());
                    return zones;
                });
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    private static String buildUserPrompt(BuildState state) {
        StringBuilder sb = new StringBuilder(4096);
        MassPlan plan = state.massPlan;

        sb.append("User prompt: ").append(state.originalPrompt).append('\n');
        sb.append("Overall style (from L1): ").append(plan.overallStyle()).append("\n\n");

        if (state.conceptCaption != null && !state.conceptCaption.isBlank()) {
            sb.append("Concept caption: ").append(state.conceptCaption).append("\n\n");
        }

        if (!state.ragContext.isEmpty()) {
            sb.append(RagAgent.formatContextBlock(state.ragContext, 4)).append("\n\n");
        }

        sb.append("L1 masses to zone:\n");
        for (MajorMass m : plan.masses()) {
            sb.append("  - ").append(m.label())
              .append(" (role=").append(m.role()).append(") ")
              .append(m.bounds())
              .append(" size=[")
              .append(m.bounds().sizeX()).append(',')
              .append(m.bounds().sizeY()).append(',')
              .append(m.bounds().sizeZ()).append("]")
              .append('\n');
        }

        sb.append("\nProduce zones covering every mass's Y range. Respond with only the JSON object.");
        return sb.toString();
    }

    private static List<ImagePart> selectedImageParts(BuildState state) {
        List<ImagePart> parts = new ArrayList<>();
        if (!state.referenceImages.isEmpty()) {
            int idx = state.selectedConceptIndex;
            if (idx < 0 || idx >= state.referenceImages.size()) idx = 0;
            ReferenceImage concept = state.referenceImages.get(idx);
            parts.add(new ImagePart(concept.bytes(), concept.mimeType()));
        }
        return parts;
    }

    // -------------------------------------------------------------------------
    // Structural critic (inner loop, optional)
    // -------------------------------------------------------------------------

    private static CompletableFuture<List<StructuralZone>> runStructuralCritic(
            BuildState state, GeminiClient gemini, List<StructuralZone> zones) {
        if (zones.isEmpty() || state.massPlan == null) {
            return CompletableFuture.completedFuture(zones);
        }
        String critiquePrompt = buildCriticPrompt(state, zones);
        return gemini.call(TaskKind.CRITIC_INNER,
                        CRITIC_SYSTEM, critiquePrompt, List.of(), state.costTracker)
                .thenApply(raw -> applyCriticPatches(zones, raw))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED task=l2_structural reason={}", err.getMessage());
                    return zones;
                });
    }

    private static String buildCriticPrompt(BuildState state, List<StructuralZone> zones) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Style: ").append(state.massPlan.overallStyle()).append('\n');
        sb.append("Prompt: ").append(state.originalPrompt).append("\n\n");
        sb.append("Proposed zones:\n");
        for (StructuralZone z : zones) {
            sb.append("  - ").append(z.massLabel()).append('/').append(z.label())
              .append(" [y=").append(z.yMin()).append("..").append(z.yMax()).append(']')
              .append(" role=").append(z.role())
              .append(" features=").append(z.featureHints())
              .append(" mats=").append(z.materialFamilies())
              .append('\n');
        }
        sb.append("\nReturn JSON: score, summary, optional patches.");
        return sb.toString();
    }

    private static List<StructuralZone> applyCriticPatches(List<StructuralZone> zones, String raw) {
        try {
            String trimmed = stripJsonFence(raw);
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            double score = obj.has("score") && obj.get("score").isJsonPrimitive()
                    ? obj.get("score").getAsDouble() : 1.0;
            String summary = obj.has("summary") && obj.get("summary").isJsonPrimitive()
                    ? obj.get("summary").getAsString() : "";
            LOGGER.info("L2_STRUCTURAL_CRITIC score={} summary={}",
                    String.format("%.2f", score), summary);

            if (score >= MIN_STRUCTURAL_SCORE || !obj.has("patches") || !obj.get("patches").isJsonArray()) {
                return zones;
            }
            JsonArray patches = obj.getAsJsonArray("patches");
            if (patches.size() == 0) return zones;

            List<StructuralZone> patched = new ArrayList<>(zones);
            for (JsonElement el : patches) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                String zoneLabel = stringOr(p, "zone", null);
                if (zoneLabel == null) continue;
                for (int i = 0; i < patched.size(); i++) {
                    StructuralZone z = patched.get(i);
                    if (!z.label().equals(zoneLabel)) continue;
                    int yMin = p.has("y_min") && p.get("y_min").isJsonPrimitive()
                            ? p.get("y_min").getAsInt() : z.yMin();
                    int yMax = p.has("y_max") && p.get("y_max").isJsonPrimitive()
                            ? p.get("y_max").getAsInt() : z.yMax();
                    List<String> hints = p.has("feature_hints") && p.get("feature_hints").isJsonArray()
                            ? stringArray(p.getAsJsonArray("feature_hints")) : z.featureHints();
                    List<String> mats = p.has("material_families") && p.get("material_families").isJsonArray()
                            ? stringArray(p.getAsJsonArray("material_families")) : z.materialFamilies();
                    patched.set(i, new StructuralZone(
                            z.label(), z.role(), z.massLabel(),
                            Math.min(yMin, yMax), Math.max(yMin, yMax),
                            hints, mats, z.citing()));
                    break;
                }
            }
            LOGGER.info("L2_STRUCTURAL_CRITIC applied {} patch(es)", patches.size());
            return patched;
        } catch (RuntimeException e) {
            LOGGER.warn("CRITIC_SKIPPED task=l2_structural reason=parse:{}", e.getMessage());
            return zones;
        }
    }

    // -------------------------------------------------------------------------
    // Parsing / validation
    // -------------------------------------------------------------------------

    static boolean isValidZonesResponse(String raw) {
        if (raw == null) return false;
        String trimmed = stripJsonFence(raw);
        if (!trimmed.startsWith("{")) return false;
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!obj.has("zones") || !obj.get("zones").isJsonArray()) return false;
            JsonArray arr = obj.getAsJsonArray("zones");
            if (arr.size() == 0) return false;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) return false;
                JsonObject z = e.getAsJsonObject();
                if (!z.has("mass_label") || !z.has("y_min") || !z.has("y_max") || !z.has("label")) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<StructuralZone> parseZones(String raw, MassPlan plan, BuildState state) {
        String trimmed = stripJsonFence(raw);
        JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
        JsonArray arr = obj.getAsJsonArray("zones");
        if (obj.has("citing") && obj.get("citing").isJsonArray()) {
            RagAgent.recordCitations(state, stringArray(obj.getAsJsonArray("citing")));
        }

        List<StructuralZone> zones = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject z = el.getAsJsonObject();
            String label = stringOr(z, "label", "zone_" + zones.size());
            String role = stringOr(z, "role", "unspecified");
            String massLabel = stringOr(z, "mass_label", null);
            if (massLabel == null) continue;

            MajorMass parent = findMass(plan, massLabel);
            if (parent == null) {
                LOGGER.warn("L2 dropping zone {} — unknown mass_label={}", label, massLabel);
                continue;
            }

            int yMin = z.has("y_min") && z.get("y_min").isJsonPrimitive()
                    ? z.get("y_min").getAsInt() : parent.bounds().minY();
            int yMax = z.has("y_max") && z.get("y_max").isJsonPrimitive()
                    ? z.get("y_max").getAsInt() : parent.bounds().maxY();

            yMin = Math.max(parent.bounds().minY(), Math.min(parent.bounds().maxY(), yMin));
            yMax = Math.max(parent.bounds().minY(), Math.min(parent.bounds().maxY(), yMax));
            if (yMin > yMax) { int tmp = yMin; yMin = yMax; yMax = tmp; }

            List<String> hints = z.has("feature_hints") && z.get("feature_hints").isJsonArray()
                    ? stringArray(z.getAsJsonArray("feature_hints")) : List.of();
            List<String> mats  = z.has("material_families") && z.get("material_families").isJsonArray()
                    ? stringArray(z.getAsJsonArray("material_families")) : List.of();
            List<String> citing = z.has("citing") && z.get("citing").isJsonArray()
                    ? stringArray(z.getAsJsonArray("citing")) : List.of();

            zones.add(new StructuralZone(label, role, massLabel, yMin, yMax, hints, mats, citing));
        }
        if (zones.isEmpty()) {
            throw new IllegalStateException("L2 produced no usable zones");
        }
        return zones;
    }

    private static MajorMass findMass(MassPlan plan, String label) {
        for (MajorMass m : plan.masses()) {
            if (label.equals(m.label())) return m;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String stripJsonFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull() || !el.isJsonPrimitive()) ? fallback : el.getAsString();
    }

    private static List<String> stringArray(JsonArray arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
