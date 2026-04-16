package com.rayyan.tesseract.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.blueprint.Primitive;
import com.rayyan.tesseract.blueprint.PrimitiveBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Stage DETAILING of the Refactor-2 pipeline.
 *
 * <p>Once the structural iteration loop converges, DetailAgent layers
 * small, high-impact decorations on top: torches near doors, flower
 * pots in windows, fences along ledges, signs above entrances.
 *
 * <p>On success: appends accepted detail ops to {@code state.completedOps}
 * (structural ops first) and calls {@code onComplete}.
 * On failure: logs a warning, calls {@code onComplete} with zero added
 * details — the build still proceeds; decorations are never load-bearing.
 */
public final class DetailAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.detail_agent");
    private static final Gson GSON = new Gson();

    private DetailAgent() {}

    // -------------------------------------------------------------------------
    // Prompts
    // -------------------------------------------------------------------------

    static final String SYSTEM_PROMPT =
        "You are the decoration pass for a Minecraft builder. " +
        "The structural build is complete. " +
        "Your job: add small, high-impact decorations — torches, trim, signs, " +
        "flower pots, fences — that make the build feel crafted, not generated.\n\n" +

        "=== RULES ===\n" +
        "1. Place lights (torches, lanterns) near entrances and along walls at y intervals of 4.\n" +
        "2. Add flower pots or panes below window openings when space allows.\n" +
        "3. Use fill_line for fence rails along raised platforms or ledges.\n" +
        "4. Keep detail count between 5 and 30 items — quality over quantity.\n" +
        "5. Never place details outside the given bounds.\n" +
        "6. Block ids must be fully-qualified (minecraft:torch, not just torch).\n\n" +

        "=== RESPONSE FORMAT ===\n" +
        "Respond ONLY with a JSON array of detail objects. No markdown, no prose.\n" +
        "Each object must have a 'type' field. Supported types:\n\n" +
        "  Torch or lantern:\n" +
        "    { \"type\": \"torch\", \"pos\": [x,y,z], \"face\": \"south\", " +
        "\"block\": \"minecraft:torch\" }\n" +
        "    face: north | south | east | west | floor\n\n" +
        "  Single-block decoration (flower_pot, lantern, chest, crafting_table, etc.):\n" +
        "    { \"type\": \"decoration\", \"pos\": [x,y,z], \"block\": \"minecraft:flower_pot\" }\n\n" +
        "  Run of identical blocks (fences, banners):\n" +
        "    { \"type\": \"fill_line\", \"pos\": [x0,y0,z0], \"to\": [x1,y1,z1], " +
        "\"block\": \"minecraft:oak_fence\" }\n\n" +
        "  Sign:\n" +
        "    { \"type\": \"sign\", \"pos\": [x,y,z], " +
        "\"block\": \"minecraft:oak_sign\", \"text\": \"optional\" }";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the detail pass asynchronously.
     *
     * <p>Requires {@code state.compiledBlueprint} and {@code state.blueprint} to be set.
     * Uses the last rendered PNG (if available) as a visual reference for placement.
     */
    public static void run(BuildState state,
                           GeminiClient gemini,
                           Runnable onComplete,
                           Consumer<String> onError) {
        if (state.compiledBlueprint == null) {
            LOGGER.warn("DetailAgent: no compiled blueprint — skipping decoration");
            finalizeAndComplete(state, List.of(), onComplete);
            return;
        }

        String userPrompt = buildUserPrompt(state);
        LOGGER.info("DetailAgent: requesting details for '{}' ({} structural ops)",
                state.blueprint != null ? state.blueprint.name : "?",
                state.compiledBlueprint.ops().size());

        // Use multimodal call when we have a render PNG; fall back to text-only
        if (state.lastRenderPng != null && state.lastRenderPng.length > 0) {
            gemini.complete(SYSTEM_PROMPT, userPrompt, state.lastRenderPng, "image/png")
                  .whenComplete((raw, ex) -> handleResponse(raw, ex, state, onComplete));
        } else {
            gemini.complete(SYSTEM_PROMPT, userPrompt)
                  .whenComplete((raw, ex) -> handleResponse(raw, ex, state, onComplete));
        }
    }

    // -------------------------------------------------------------------------
    // Response handling
    // -------------------------------------------------------------------------

    private static void handleResponse(String raw, Throwable ex,
                                        BuildState state, Runnable onComplete) {
        if (ex != null) {
            LOGGER.warn("DetailAgent: Gemini call failed: {} — proceeding with no details",
                    ex.getMessage());
            finalizeAndComplete(state, List.of(), onComplete);
            return;
        }
        List<Detail> details = parseDetails(raw, state);
        List<BlockOp> filtered = filterAndCompile(details, state);
        finalizeAndComplete(state, filtered, onComplete);
    }

    // -------------------------------------------------------------------------
    // Prompt
    // -------------------------------------------------------------------------

    static String buildUserPrompt(BuildState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build request: \"").append(state.originalPrompt).append("\"\n\n");

        if (state.blueprint != null) {
            Blueprint bp = state.blueprint;
            sb.append("Blueprint bounds: ")
              .append(bp.bounds.sizeX()).append("W × ")
              .append(bp.bounds.sizeY()).append("H × ")
              .append(bp.bounds.sizeZ()).append("D\n\n");

            sb.append("Structural primitives (").append(bp.primitives.size())
              .append("):\n");
            for (Primitive p : bp.primitives) {
                sb.append("  ").append(p).append("\n");
            }
            sb.append("\n");

            if (state.compiledBlueprint != null && !state.compiledBlueprint.primitiveBounds().isEmpty()) {
                sb.append("Primitive bounds (id → originX,originY,originZ – maxX,maxY,maxZ):\n");
                for (var entry : state.compiledBlueprint.primitiveBounds().entrySet()) {
                    PrimitiveBounds pb = entry.getValue();
                    sb.append("  ").append(entry.getKey()).append(": ")
                      .append(pb.originX()).append(",")
                      .append(pb.originY()).append(",")
                      .append(pb.originZ())
                      .append(" – ")
                      .append(pb.maxX()).append(",")
                      .append(pb.maxY()).append(",")
                      .append(pb.maxZ()).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("Return a JSON array of detail objects as specified.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parse
    // -------------------------------------------------------------------------

    static List<Detail> parseDetails(String raw, BuildState state) {
        if (raw == null || raw.isBlank()) return List.of();

        // Strip markdown fences
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("```[a-zA-Z]*\\n?", "").replaceAll("```$", "").strip();
        }

        try {
            Detail[] arr = GSON.fromJson(json, Detail[].class);
            if (arr == null) return List.of();
            return List.of(arr);
        } catch (JsonSyntaxException e) {
            LOGGER.warn("DetailAgent: failed to parse detail JSON — proceeding with no details: {}",
                    e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Filter / compile
    // -------------------------------------------------------------------------

    static List<BlockOp> filterAndCompile(List<Detail> details, BuildState state) {
        if (details.isEmpty()) return List.of();

        Blueprint.Bounds bounds = state.blueprint != null ? state.blueprint.bounds : null;

        // Build a set of occupied positions from structural ops for collision detection
        Set<Long> occupied = new HashSet<>();
        for (BlockOp op : state.compiledBlueprint.ops()) {
            occupied.add(key(op.x, op.y, op.z));
        }

        List<BlockOp> accepted = new ArrayList<>();
        int droppedOob  = 0;
        int droppedColl = 0;

        for (Detail d : details) {
            List<BlockOp> compiled = DetailCompiler.compile(d);
            for (BlockOp op : compiled) {
                if (bounds != null && isOutOfBounds(op, bounds)) {
                    droppedOob++;
                    continue;
                }
                if (occupied.contains(key(op.x, op.y, op.z)) && !d.isOverlapAllowed()) {
                    droppedColl++;
                    continue;
                }
                accepted.add(op);
            }
        }

        if (droppedOob > 0 || droppedColl > 0) {
            LOGGER.info("DetailAgent: dropped {} out-of-bounds, {} collision detail ops",
                    droppedOob, droppedColl);
        }
        LOGGER.info("DetailAgent: {} detail ops accepted from {} detail items",
                accepted.size(), details.size());
        return accepted;
    }

    // -------------------------------------------------------------------------
    // Finalization
    // -------------------------------------------------------------------------

    private static void finalizeAndComplete(BuildState state, List<BlockOp> detailOps,
                                             Runnable onComplete) {
        List<BlockOp> structural = state.compiledBlueprint != null
                ? state.compiledBlueprint.ops()
                : List.of();

        List<BlockOp> merged = new ArrayList<>(structural.size() + detailOps.size());
        merged.addAll(structural);
        merged.addAll(detailOps);
        state.completedOps = merged;

        onComplete.run();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isOutOfBounds(BlockOp op, Blueprint.Bounds b) {
        return op.x < 0 || op.x >= b.sizeX()
            || op.y < 0 || op.y >= b.sizeY()
            || op.z < 0 || op.z >= b.sizeZ();
    }

    /** Packs (x,y,z) ∈ [0,1023]³ into a single long key for fast lookup. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FF) << 20) | ((long) (y & 0x3FF) << 10) | (z & 0x3FF);
    }
}
