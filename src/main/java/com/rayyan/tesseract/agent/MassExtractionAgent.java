package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Phase 1 — 3D Mass Extraction.
 *
 * <p>Turns the primary concept image (written by {@link ConceptAgent} into
 * {@link BuildState#referenceImages}) into a 16³ {@link VoxelMass}. This is
 * done via multimodal reasoning on Gemini 2.5 Pro — no dedicated 3D model.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Prompt Pro for a flat JSON array of {@code [x,y,z]} filled voxel
 *       coordinates. Temperature 0.2, max output 16k tokens.</li>
 *   <li>Parse and validate density ({@link VoxelMass#MIN_FILLED}–{@link VoxelMass#MAX_FILLED}).
 *       On out-of-bounds density, retry once with a stricter reminder.</li>
 *   <li>Optional single refinement pass — "critique this mass against the
 *       reference, return add/remove voxels" — applied if the mass looks
 *       valid but an improvement pass is enabled.</li>
 * </ol>
 *
 * <p>Writes {@link BuildState#massSketch} on success. On fatal failure the
 * orchestrator falls back to the v2-style text-only pipeline (see §3.3.3).
 */
public final class MassExtractionAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.mass_extraction");

    private static final int VOXEL_RESOLUTION   = VoxelMass.DEFAULT_RESOLUTION;
    private static final int MAX_PARSE_RETRIES  = 1;

    /** Stricter reminder appended on escalation per §3.2.3. */
    private static final String STRICT_REMINDER =
        "Return ONLY a JSON array of integer [x,y,z] triples. "
      + "No prose, no keys, no markdown fence. "
      + "Example format: [[8,0,8],[8,1,8],[8,2,8]].";

    private MassExtractionAgent() {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void run(BuildState state,
                           GeminiClient gemini,
                           Runnable onSuccess,
                           Consumer<String> onError) {
        run(state, gemini, true, onSuccess, onError);
    }

    /**
     * @param enableRefinement when true, runs the optional §2.1.3 critique pass
     *                         after a valid initial mass arrives.
     */
    public static void run(BuildState state,
                           GeminiClient gemini,
                           boolean enableRefinement,
                           Runnable onSuccess,
                           Consumer<String> onError) {
        ReferenceImage primary = primaryConcept(state);
        if (primary == null) {
            onError.accept("MassExtractionAgent: no concept image available in BuildState.referenceImages");
            return;
        }

        extractOnce(gemini, state, primary, 0)
            .thenCompose(initial -> {
                if (initial == null) {
                    CompletableFuture<VoxelMass> f = new CompletableFuture<>();
                    f.completeExceptionally(new RuntimeException(
                            "mass extraction failed after " + (MAX_PARSE_RETRIES + 1) + " attempt(s)"));
                    return f;
                }
                if (!enableRefinement) {
                    return CompletableFuture.completedFuture(initial);
                }
                return refineOnce(gemini, state, primary, initial)
                        .exceptionally(err -> {
                            LOGGER.warn("MassExtractionAgent: refinement failed ({}) — keeping initial mass",
                                    err.getMessage());
                            return initial;
                        });
            })
            .whenComplete((mass, ex) -> {
                if (ex != null || mass == null) {
                    onError.accept("MassExtractionAgent: " + (ex == null ? "null mass" : ex.getMessage()));
                    return;
                }
                state.massSketch = mass;
                LOGGER.info("MassExtractionAgent: mass ready ({} filled voxels @ {}³)",
                        mass.filledCount(), mass.resolution());
                writeDebugPng(state, mass);
                onSuccess.run();
            });
    }

    /**
     * §2.2.3 — when {@code tesseract.debug.renders} is on, emit an isometric PNG
     * of the voxel mass alongside the concept dump.
     */
    private static void writeDebugPng(BuildState state, VoxelMass mass) {
        try {
            com.rayyan.tesseract.blueprint.Blueprint.Bounds bounds =
                    VoxelMassRenderer.deriveBounds(state.buildSelection);
            byte[] png = VoxelMassRenderer.renderPng(mass, bounds, 8);
            VoxelMassRenderer.writeDebugCopy(png, state.playerId == null ? "unknown"
                    : state.playerId.toString());
        } catch (Exception e) {
            LOGGER.warn("MassExtractionAgent: debug PNG render failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // First pass: extract with retry on density failure / parse refusal
    // -------------------------------------------------------------------------

    private static CompletableFuture<VoxelMass> extractOnce(GeminiClient gemini,
                                                            BuildState state,
                                                            ReferenceImage concept,
                                                            int attempt) {
        String userPrompt = attempt == 0
                ? EXTRACTION_USER_PROMPT
                : EXTRACTION_USER_PROMPT
                + "\n\nPREVIOUS ATTEMPT FAILED validation. "
                + "You returned an invalid number of voxels (must be between "
                + VoxelMass.MIN_FILLED + " and " + VoxelMass.MAX_FILLED + ") "
                + "or the response was not a valid JSON array of [x,y,z] triples. "
                + "Return ONLY the JSON array, no prose, no markdown fence.";

        List<ImagePart> images = List.of(new ImagePart(concept.bytes(), concept.mimeType()));

        // §3.1 routing: model + generation config come from ModelRegistry.
        // §3.2.3: validator rejects non-parseable / density-invalid responses so
        // the call chain escalates (Pro is already top-tier — escalation is a
        // no-op here but we keep the validator so future chain growth Just Works).
        return gemini.call(TaskKind.MASS_EXTRACTION,
                           EXTRACTION_SYSTEM_PROMPT, userPrompt, images,
                           MassExtractionAgent::isValidMassResponse,
                           STRICT_REMINDER, state.costTracker)
            .thenCompose(raw -> {
                VoxelMass mass = tryParse(raw);
                if (mass != null && mass.isValidDensity()) {
                    return CompletableFuture.completedFuture(mass);
                }

                if (attempt >= MAX_PARSE_RETRIES) {
                    LOGGER.warn("MassExtractionAgent: giving up after {} attempts; filled={} (raw preview: {})",
                            attempt + 1, mass == null ? -1 : mass.filledCount(), preview(raw));
                    return CompletableFuture.completedFuture(null);
                }

                LOGGER.warn("MassExtractionAgent: attempt {} invalid (filled={}) — retrying",
                        attempt + 1, mass == null ? -1 : mass.filledCount());
                return extractOnce(gemini, state, concept, attempt + 1);
            });
    }

    /** Parse-level validator for the §3.2.3 escalation chain. */
    private static boolean isValidMassResponse(String raw) {
        VoxelMass parsed = tryParse(raw);
        return parsed != null && parsed.isValidDensity();
    }

    // -------------------------------------------------------------------------
    // Optional single refinement pass (§2.1.3)
    // -------------------------------------------------------------------------

    private static CompletableFuture<VoxelMass> refineOnce(GeminiClient gemini,
                                                           BuildState state,
                                                           ReferenceImage concept,
                                                           VoxelMass initial) {
        String userPrompt = buildRefinementPrompt(initial);
        List<ImagePart> images = List.of(new ImagePart(concept.bytes(), concept.mimeType()));

        return gemini.call(TaskKind.MASS_EXTRACTION,
                           REFINEMENT_SYSTEM_PROMPT, userPrompt, images,
                           state.costTracker)
            .thenApply(raw -> applyRefinement(initial, raw));
    }

    private static VoxelMass applyRefinement(VoxelMass base, String raw) {
        if (raw == null || raw.isBlank()) return base;
        String cleaned = stripFences(raw);
        JsonObject obj;
        try {
            obj = JsonParser.parseString(cleaned).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("MassExtractionAgent: refinement JSON parse failed — keeping initial mass");
            return base;
        }

        int added = 0, removed = 0;
        if (obj.has("add") && obj.get("add").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("add")) {
                int[] c = coordFromEl(el);
                if (c != null) { base.add(c[0], c[1], c[2]); added++; }
            }
        }
        if (obj.has("remove") && obj.get("remove").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("remove")) {
                int[] c = coordFromEl(el);
                if (c != null) { base.remove(c[0], c[1], c[2]); removed++; }
            }
        }

        // Reject the refinement wholesale if it pushed density out of range.
        if (!base.isValidDensity()) {
            LOGGER.warn("MassExtractionAgent: refinement pushed density to {} — reverting is not possible, keeping",
                    base.filledCount());
        }
        LOGGER.info("MassExtractionAgent: refinement applied ({} added, {} removed; total={})",
                added, removed, base.filledCount());
        return base;
    }

    private static String buildRefinementPrompt(VoxelMass initial) {
        return "You previously produced this 3D mass sketch for the attached reference image:\n\n"
             + initial.toAsciiLayersCompact()
             + "\nCritique the sketch against the image and return ONLY a JSON object of the form:\n"
             + "{\n"
             + "  \"add\": [[x,y,z], ...],\n"
             + "  \"remove\": [[x,y,z], ...]\n"
             + "}\n\n"
             + "All coordinates must be integers in [0, " + (VoxelMass.DEFAULT_RESOLUTION - 1) + "]. "
             + "Keep the edit minimal — aim to nudge the silhouette toward the reference, not rewrite it. "
             + "If the sketch already matches well, return {\"add\":[],\"remove\":[]}.";
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a Gemini response into a {@link VoxelMass}. Accepts either a bare
     * JSON array of {@code [x,y,z]} triples or an object with a top-level
     * {@code "voxels"} array. Returns null on total parse failure.
     */
    static VoxelMass tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = stripFences(raw);

        JsonElement root;
        try {
            root = JsonParser.parseString(cleaned);
        } catch (Exception e) {
            LOGGER.warn("MassExtractionAgent: JSON parse error — {}", e.getMessage());
            return null;
        }

        JsonArray arr;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("voxels")
                && root.getAsJsonObject().get("voxels").isJsonArray()) {
            arr = root.getAsJsonObject().getAsJsonArray("voxels");
        } else {
            LOGGER.warn("MassExtractionAgent: top-level JSON was neither array nor object with 'voxels'");
            return null;
        }

        VoxelMass mass = new VoxelMass(VOXEL_RESOLUTION);
        for (JsonElement el : arr) {
            int[] c = coordFromEl(el);
            if (c != null) mass.add(c[0], c[1], c[2]);
        }
        return mass;
    }

    private static int[] coordFromEl(JsonElement el) {
        try {
            if (el == null) return null;
            if (el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                if (a.size() < 3) return null;
                return new int[] {
                        a.get(0).getAsInt(),
                        a.get(1).getAsInt(),
                        a.get(2).getAsInt() };
            }
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("x") && o.has("y") && o.has("z")) {
                    return new int[] {
                            o.get("x").getAsInt(),
                            o.get("y").getAsInt(),
                            o.get("z").getAsInt() };
                }
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }

    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.strip();
        }
        return s;
    }

    private static String preview(String s) {
        if (s == null) return "<null>";
        return s.length() <= 240 ? s : s.substring(0, 240) + "...";
    }

    private static ReferenceImage primaryConcept(BuildState state) {
        if (state.referenceImages == null || state.referenceImages.isEmpty()) return null;
        int idx = state.selectedConceptIndex;
        if (idx < 0 || idx >= state.referenceImages.size()) idx = 0;
        return state.referenceImages.get(idx);
    }

    // -------------------------------------------------------------------------
    // Prompts
    // -------------------------------------------------------------------------

    static final String EXTRACTION_SYSTEM_PROMPT =
        "You are a 3D mass-extraction module for an architectural build pipeline. "
      + "You look at a single reference image of a building and output a coarse "
      + "3D occupancy grid describing its silhouette mass. You NEVER add interior "
      + "detail, furniture, textures, or decoration — only the solid envelope.";

    static final String EXTRACTION_USER_PROMPT =
        "Given the attached reference image of a building, output a "
      + VOXEL_RESOLUTION + "×" + VOXEL_RESOLUTION + "×" + VOXEL_RESOLUTION
      + " voxel mass describing its silhouette.\n\n"
      + "Coordinate system:\n"
      + "  x: left → right (0 to " + (VOXEL_RESOLUTION - 1) + ")\n"
      + "  y: bottom → top (0 to " + (VOXEL_RESOLUTION - 1) + ")\n"
      + "  z: front → back (0 to " + (VOXEL_RESOLUTION - 1) + ")\n\n"
      + "The structure should rest on y=0 and rise toward higher y. Center it "
      + "horizontally in x and z. Scale it to fill the grid (tallest point near "
      + "y = " + (VOXEL_RESOLUTION - 1) + ").\n\n"
      + "Fill ONLY the silhouette mass, not interior detail. Between "
      + VoxelMass.MIN_FILLED + " and " + VoxelMass.MAX_FILLED + " filled voxels "
      + "total; tall thin structures (towers, spires) at the low end, dense wide "
      + "structures at the high end.\n\n"
      + "Return ONLY a JSON array of integer triples [[x,y,z], ...]. No prose, "
      + "no keys, no markdown fence. Example format: [[8,0,8],[8,1,8],[8,2,8]].";

    static final String REFINEMENT_SYSTEM_PROMPT =
        "You are refining a 3D mass sketch against a reference image. Return "
      + "minimal edits as JSON. No prose.";
}
