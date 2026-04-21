package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.BoundingBox;
import com.rayyan.tesseract.plan.MajorMass;
import com.rayyan.tesseract.plan.MassPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * §5.1 — L1 Architect. Decomposes the voxel mass into a small set of
 * labelled {@link MajorMass} volumes using Gemini 2.5 Pro.
 *
 * <p>Inputs (§5.1.1):
 * <ul>
 *   <li>user prompt</li>
 *   <li>selected concept image ({@link BuildState#referenceImages})</li>
 *   <li>16³ voxel mass ASCII diagram ({@link BuildState#massSketch})</li>
 *   <li>top-k RAG entries ({@link BuildState#ragContext})</li>
 * </ul>
 *
 * <p>Output schema:
 * <pre>{@code
 * {
 *   "overallStyle": "gothic",
 *   "narrative": "Central tower flanked by two lower wings...",
 *   "masses": [
 *     {"label":"central_tower","role":"primary_vertical",
 *      "bounds":{"minX":6,"minY":0,"minZ":6,"maxX":10,"maxY":15,"maxZ":10},
 *      "citing":["style_gothic"]}
 *   ],
 *   "citing": ["style_gothic", "ex_chartres"]
 * }
 * }</pre>
 *
 * <p>Silhouette critic (§5.1.3): once the plan parses, we compute its
 * coverage against {@link BuildState#massSketch}. If coverage falls below
 * {@link #MIN_COVERAGE} or overreach exceeds {@link #MAX_OVERREACH}, we
 * retry once with a critique appended to the prompt. Only one retry — the
 * idea is to correct a clearly-wrong plan, not to drive Pro through a full
 * inner loop.
 */
public final class L1ArchitectAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l1");

    /** Coverage threshold below which we invoke the silhouette retry. */
    public static final double MIN_COVERAGE = 0.75;
    /** Overreach threshold above which we invoke the silhouette retry. */
    public static final double MAX_OVERREACH = 0.45;

    private static final int MAX_MASSES = 8;

    private static final String SYSTEM_PROMPT = """
            You are the L1 Architect in a multi-agent Minecraft-build pipeline.
            You receive a user prompt, a concept image, a 16×16×16 voxel mass
            silhouette, and retrieved architectural knowledge.

            Your job: propose a small number (1-6) of labelled major masses
            whose union matches the voxel silhouette. Think like a CAD
            massing study — you are naming the *volumes*, not the details.

            Requirements:
              - Use 16×16×16 mass-local coordinates, 0 ≤ x,y,z ≤ 15, inclusive.
              - Every mass needs a short snake_case label (central_tower,
                east_wing, portico, crown_drum, etc.) and one of these roles:
                primary_vertical, secondary_vertical, primary_horizontal,
                secondary_horizontal, connector, plinth, crown, appendage.
              - Bounding boxes must tile the silhouette reasonably; aim for
                >=80% coverage of the filled voxels; avoid large bounding
                boxes that enclose empty space.
              - Draw vocabulary (style words, features, proportions) from the
                retrieved corpus entries. Cite corpus ids you used.

            Optional key "age" (0.0–1.0): building weathering / ruin intensity for
            the non-LLM texture pass after geometry; omit to default 0.5.

            Return a single JSON object matching this schema — no prose, no
            markdown fences, no code blocks:
            {
              "overallStyle": "<style-word>",
              "narrative": "<one paragraph rationale>",
              "age": 0.0,
              "masses": [
                {
                  "label": "<snake_case>",
                  "role":  "<role vocabulary>",
                  "bounds": {"minX":0,"minY":0,"minZ":0,"maxX":0,"maxY":0,"maxZ":0},
                  "citing": ["<corpus_id>", ...]
                }
              ],
              "citing": ["<corpus_id>", ...]
            }
            """;

    private static final String STRICT_REMINDER = """
            Return ONLY the JSON object matching the schema in the system prompt.
            No prose before or after. No markdown code fences. Every mass must
            have all four bounds keys with integer values in [0, 15], minX<=maxX,
            minY<=maxY, minZ<=maxZ.
            """;

    private L1ArchitectAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Produces a {@link MassPlan} for the given {@link BuildState}. On success
     * the plan is stored in {@link BuildState#massPlan} and citations are
     * recorded. The returned future never completes normally with {@code null}.
     */
    public static CompletableFuture<MassPlan> run(BuildState state, GeminiClient gemini) {
        if (state.massSketch == null) {
            CompletableFuture<MassPlan> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "L1ArchitectAgent requires BuildState.massSketch"));
            return failed;
        }
        return runAttempt(state, gemini, null, 0);
    }

    // -------------------------------------------------------------------------
    // Implementation
    // -------------------------------------------------------------------------

    private static CompletableFuture<MassPlan> runAttempt(BuildState state,
                                                          GeminiClient gemini,
                                                          String critiqueFromLastAttempt,
                                                          int attempt) {
        String userPrompt = buildUserPrompt(state, critiqueFromLastAttempt);
        List<ImagePart> images = selectedImageParts(state);

        return gemini.call(TaskKind.L1_ARCHITECT,
                        SYSTEM_PROMPT, userPrompt, images,
                        L1ArchitectAgent::isValidMassPlanResponse,
                        STRICT_REMINDER, state.costTracker)
                .thenCompose(raw -> {
                    MassPlan plan;
                    try {
                        plan = parse(raw);
                    } catch (RuntimeException parseError) {
                        LOGGER.warn("L1_PARSE_FAIL attempt={} error={}", attempt, parseError.getMessage());
                        CompletableFuture<MassPlan> failed = new CompletableFuture<>();
                        failed.completeExceptionally(parseError);
                        return failed;
                    }

                    double coverage = plan.coverageAgainst(state.massSketch);
                    double overreach = plan.overreachAgainst(state.massSketch);
                    LOGGER.info("L1 attempt={} masses={} coverage={} overreach={} style={}",
                            attempt, plan.masses().size(),
                            String.format("%.3f", coverage),
                            String.format("%.3f", overreach),
                            plan.overallStyle());

                    boolean needsRetry = (coverage < MIN_COVERAGE) || (overreach > MAX_OVERREACH);
                    if (needsRetry && attempt == 0) {
                        String critique = buildCritique(plan, coverage, overreach);
                        LOGGER.info("L1_SILHOUETTE_RETRY coverage={} overreach={} — retrying once",
                                String.format("%.3f", coverage),
                                String.format("%.3f", overreach));
                        return runAttempt(state, gemini, critique, attempt + 1);
                    }

                    if (needsRetry) {
                        LOGGER.warn("L1_SILHOUETTE_ACCEPTED_DEGRADED coverage={} overreach={} — keeping plan",
                                String.format("%.3f", coverage),
                                String.format("%.3f", overreach));
                    }

                    state.massPlan = plan;
                    RagAgent.recordCitations(state, plan.citing());
                    for (MajorMass m : plan.masses()) {
                        RagAgent.recordCitations(state, m.citing());
                    }
                    return CompletableFuture.completedFuture(plan);
                });
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    private static String buildUserPrompt(BuildState state, String critique) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("User prompt: ").append(state.originalPrompt).append("\n\n");

        if (state.conceptCaption != null && !state.conceptCaption.isBlank()) {
            sb.append("Concept caption: ").append(state.conceptCaption).append("\n\n");
        }

        if (!state.ragContext.isEmpty()) {
            sb.append(RagAgent.formatContextBlock(state.ragContext, 5));
            sb.append("\n\n");
        }

        sb.append("Voxel silhouette (16³, filled voxels = #):\n");
        sb.append(state.massSketch.toAsciiLayersCompact()).append('\n');

        int[] tight = state.massSketch.tightBounds();
        if (tight != null) {
            sb.append("Silhouette tight bounds: [")
              .append(tight[0]).append(',').append(tight[1]).append(',').append(tight[2])
              .append("]..[")
              .append(tight[3]).append(',').append(tight[4]).append(',').append(tight[5])
              .append("]\n");
        }

        sb.append("\nPropose a MassPlan with at most ").append(MAX_MASSES).append(" masses. ")
          .append("Aim for coverage >= ")
          .append(String.format("%.2f", MIN_COVERAGE))
          .append(" and overreach <= ")
          .append(String.format("%.2f", MAX_OVERREACH))
          .append(". Respond with only the JSON object.\n");

        if (critique != null && !critique.isBlank()) {
            sb.append("\n").append(critique);
        }
        return sb.toString();
    }

    private static String buildCritique(MassPlan previousPlan, double coverage, double overreach) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Silhouette critic feedback on previous attempt:\n");
        sb.append("- Coverage was ").append(String.format("%.2f", coverage))
          .append(" (target >= ").append(String.format("%.2f", MIN_COVERAGE)).append(").\n");
        sb.append("- Overreach was ").append(String.format("%.2f", overreach))
          .append(" (target <= ").append(String.format("%.2f", MAX_OVERREACH)).append(").\n");
        sb.append("- Your previous masses were: ");
        for (int i = 0; i < previousPlan.masses().size(); i++) {
            if (i > 0) sb.append(", ");
            MajorMass m = previousPlan.masses().get(i);
            sb.append(m.label()).append(m.bounds());
        }
        sb.append(".\n");
        if (coverage < MIN_COVERAGE) {
            sb.append("Enlarge or add masses so the union covers more filled voxels.\n");
        }
        if (overreach > MAX_OVERREACH) {
            sb.append("Tighten bounding boxes so they hug the filled silhouette and avoid empty space.\n");
        }
        sb.append("Produce a corrected JSON plan.");
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
    // Parsing / validation
    // -------------------------------------------------------------------------

    static boolean isValidMassPlanResponse(String raw) {
        if (raw == null) return false;
        String trimmed = raw.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false;
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!obj.has("masses") || !obj.get("masses").isJsonArray()) return false;
            JsonArray arr = obj.getAsJsonArray("masses");
            if (arr.size() == 0 || arr.size() > MAX_MASSES) return false;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) return false;
                JsonObject m = e.getAsJsonObject();
                if (!m.has("bounds") || !m.get("bounds").isJsonObject()) return false;
                JsonObject b = m.getAsJsonObject("bounds");
                for (String k : new String[] {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"}) {
                    if (!b.has(k) || !b.get(k).isJsonPrimitive()) return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MassPlan parse(String raw) {
        String trimmed = stripJsonFence(raw);
        JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
        String style = stringOr(obj, "overallStyle", "unspecified");
        String narrative = stringOr(obj, "narrative", "");
        List<String> planCiting = stringArray(obj, "citing");
        double age = 0.5;
        if (obj.has("age") && obj.get("age").isJsonPrimitive()) {
            age = obj.get("age").getAsDouble();
            if (Double.isNaN(age)) age = 0.5;
            if (age < 0.0) age = 0.0;
            if (age > 1.0) age = 1.0;
        }

        JsonArray massesArr = obj.getAsJsonArray("masses");
        if (massesArr == null || massesArr.size() == 0) {
            throw new IllegalArgumentException("masses array empty");
        }
        List<MajorMass> masses = new ArrayList<>(massesArr.size());
        for (JsonElement el : massesArr) {
            JsonObject m = el.getAsJsonObject();
            String label = stringOr(m, "label", "mass_" + masses.size());
            String role = stringOr(m, "role", "unspecified");
            JsonObject b = m.getAsJsonObject("bounds");
            BoundingBox bounds = new BoundingBox(
                    b.get("minX").getAsInt(), b.get("minY").getAsInt(), b.get("minZ").getAsInt(),
                    b.get("maxX").getAsInt(), b.get("maxY").getAsInt(), b.get("maxZ").getAsInt())
                    .clampTo(state().resolution());
            List<String> citing = stringArray(m, "citing");
            masses.add(new MajorMass(label, role, bounds, citing));
        }
        return new MassPlan(style, narrative, masses, planCiting, age);
    }

    /**
     * Returns a dummy {@link VoxelMass} just to expose {@code resolution()}
     * for the clamp step. This decouples the parser from the caller's
     * actual mass (we don't carry it through parsing for purity).
     */
    private static VoxelMass state() { return CLAMP_MASS; }
    private static final VoxelMass CLAMP_MASS = new VoxelMass();

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
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return fallback;
        return el.getAsString();
    }

    private static List<String> stringArray(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }
}
