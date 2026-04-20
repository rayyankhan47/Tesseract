package com.rayyan.tesseract.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.ConceptPromptTemplate;
import com.rayyan.tesseract.api.ConceptPromptTemplate.Variation;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.ImagenClient;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.render.IsoRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Phase 0 — Concept Synthesis.
 *
 * <p>Produces four concept images from Imagen (one per stylistic variation),
 * then uses Gemini 2.5 Pro multimodal to pick the index that most faithfully
 * matches the user's prompt. All four images plus the selected index are
 * written to {@link BuildState#referenceImages} and {@link BuildState#selectedConceptIndex}
 * for downstream agents to consume.
 *
 * <p>If any individual Imagen call fails, it is recorded as a null slot and the
 * agent proceeds with the surviving images — as long as at least one came back,
 * the agent does not fail the build. If the selector call fails or refuses, the
 * fallback is to return index 0 (the minimalist variation) per 1.2.2.
 */
public final class ConceptAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.concept");

    /** Aspect ratio for concept images — fixed to 1:1 for architectural consistency. */
    private static final String CONCEPT_ASPECT = "1:1";

    /** Stricter reminder used by the §3.2.3 escalation chain if selector returns garbage. */
    private static final String SELECTOR_STRICT_REMINDER =
            "Return ONLY {\"index\":N} with N as an integer. "
          + "No prose, no markdown, no keys other than \"index\".";

    private ConceptAgent() {}

    /**
     * Runs the full Phase 0 pipeline. Both callbacks run on whichever thread
     * completes the CompletableFuture — caller is responsible for trampolining
     * back to the server thread (see {@link Orchestrator#onServerThread}).
     *
     * @param onSuccess called after referenceImages + selectedConceptIndex are populated
     * @param onError   called on fatal failure (e.g. all four Imagen calls failed)
     */
    public static void run(BuildState state,
                           ImagenClient imagen,
                           GeminiClient gemini,
                           Runnable onSuccess,
                           Consumer<String> onError) {
        String prompt = state.originalPrompt == null ? "" : state.originalPrompt.strip();
        if (prompt.isEmpty()) {
            onError.accept("ConceptAgent: empty prompt");
            return;
        }

        List<String> prompts = ConceptPromptTemplate.expand(prompt);
        String negative = ConceptPromptTemplate.negativePrompt();
        Variation[] variations = Variation.values();

        // 1.2.1 — four parallel Imagen calls.
        List<CompletableFuture<byte[]>> futures = new ArrayList<>(prompts.size());
        for (String p : prompts) {
            futures.add(imagen.generate(p, negative, CONCEPT_ASPECT)
                .exceptionally(err -> {
                    LOGGER.warn("ConceptAgent: Imagen call failed — {}", err.getMessage());
                    return null;
                }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .whenComplete((ignored, genErr) -> {
                List<ReferenceImage> images = new ArrayList<>(prompts.size());
                for (int i = 0; i < futures.size(); i++) {
                    byte[] bytes = futures.get(i).getNow(null);
                    if (bytes == null || bytes.length == 0) continue;
                    images.add(new ReferenceImage(
                            bytes,
                            "image/png",
                            prompts.get(i),
                            "imagen-3.0-generate-002",
                            variations[i].label()));
                }

                if (images.isEmpty()) {
                    onError.accept("ConceptAgent: all Imagen calls failed");
                    return;
                }
                LOGGER.info("ConceptAgent: {} / {} concept images generated", images.size(), prompts.size());

                // 1.2.2 — Gemini 2.5 Pro auto-select. Only run if we have at least 2 images.
                if (images.size() < 2) {
                    commit(state, images, 0);
                    onSuccess.run();
                    return;
                }

                selectBest(gemini, state, prompt, images)
                    .whenComplete((selectedIndex, selErr) -> {
                        int index = (selErr != null || selectedIndex == null) ? 0 : clampIndex(selectedIndex, images.size());
                        if (selErr != null) {
                            LOGGER.warn("ConceptAgent: selector failed ({}) — falling back to index 0", selErr.getMessage());
                        }
                        commit(state, images, index);
                        onSuccess.run();
                    });
            });
    }

    /** 1.2.2 — ask Gemini 2.5 Pro which of N concepts most faithfully matches the prompt. */
    private static CompletableFuture<Integer> selectBest(GeminiClient gemini,
                                                          BuildState state,
                                                          String userPrompt,
                                                          List<ReferenceImage> images) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are shown ").append(images.size())
          .append(" candidate reference images for an architectural build prompt. ")
          .append("Select the ONE image that most faithfully matches the user's intent. ")
          .append("Return ONLY a strict JSON object of the form {\"index\": N}, ")
          .append("where N is an integer in [0, ").append(images.size() - 1)
          .append("]. No prose, no markdown fence.\n\n");
        sb.append("User prompt: \"").append(userPrompt).append("\"\n\n");
        sb.append("Images are provided in the following order:\n");
        for (int i = 0; i < images.size(); i++) {
            sb.append("  [").append(i).append("] variation=")
              .append(images.get(i).variation() == null ? "unknown" : images.get(i).variation())
              .append("\n");
        }

        List<ImagePart> parts = new ArrayList<>(images.size());
        for (ReferenceImage img : images) {
            parts.add(new ImagePart(img.bytes(), img.mimeType()));
        }

        return gemini.call(
                TaskKind.CONCEPT_SELECT,
                "You are an architectural art director. Be decisive. Return only JSON.",
                sb.toString(),
                parts,
                ConceptAgent::isValidSelectorResponse,
                SELECTOR_STRICT_REMINDER,
                state.costTracker)
            .thenApply(ConceptAgent::parseIndex);
    }

    /** §3.2.3 validator: response must be parseable as {index: int}. */
    private static boolean isValidSelectorResponse(String raw) {
        if (raw == null) return false;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) trimmed = trimmed.substring(firstNl + 1);
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd >= 0) trimmed = trimmed.substring(0, fenceEnd);
            trimmed = trimmed.strip();
        }
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            return obj.has("index");
        } catch (Exception e) {
            return false;
        }
    }

    private static int parseIndex(String raw) {
        if (raw == null) return 0;
        String trimmed = raw.strip();
        // Strip common markdown fences if present
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) trimmed = trimmed.substring(firstNl + 1);
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd >= 0) trimmed = trimmed.substring(0, fenceEnd);
            trimmed = trimmed.strip();
        }
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (obj.has("index")) return obj.get("index").getAsInt();
        } catch (Exception e) {
            // fall through to heuristic
        }
        // Last-ditch: first digit in the response.
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') return c - '0';
        }
        return 0;
    }

    private static int clampIndex(int idx, int size) {
        if (idx < 0) return 0;
        if (idx >= size) return size - 1;
        return idx;
    }

    private static void commit(BuildState state, List<ReferenceImage> images, int selectedIndex) {
        state.referenceImages.clear();
        state.referenceImages.addAll(images);
        state.selectedConceptIndex = selectedIndex;
        LOGGER.info("ConceptAgent: selected index {} ({})",
                selectedIndex,
                images.get(selectedIndex).variation());
        writeDebugDump(state, images, selectedIndex);
    }

    /**
     * 1.3.3 — when {@code tesseract.debug.renders} is on, dump the four concepts
     * (plus a {@code selected.png} copy) to {@code run/tesseract_debug/concepts/<buildId>/}.
     */
    private static void writeDebugDump(BuildState state, List<ReferenceImage> images, int selectedIndex) {
        if (!IsoRenderer.isDebugEnabled()) return;
        try {
            String safeId = state.playerId == null ? "unknown" :
                    state.playerId.toString().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            Path dir = IsoRenderer.debugDir().resolve("concepts").resolve(safeId);
            Files.createDirectories(dir);
            for (int i = 0; i < images.size(); i++) {
                ReferenceImage img = images.get(i);
                String name = "concept_" + i + "_"
                        + (img.variation() == null ? "unknown" : img.variation().replace(' ', '_'))
                        + ".png";
                Files.write(dir.resolve(name), img.bytes());
            }
            Files.write(dir.resolve("selected.png"), images.get(selectedIndex).bytes());
            Files.writeString(dir.resolve("selected.txt"),
                    "index=" + selectedIndex + "\n"
                  + "variation=" + images.get(selectedIndex).variation() + "\n"
                  + "prompt=" + images.get(selectedIndex).prompt() + "\n");
        } catch (Exception e) {
            LOGGER.warn("ConceptAgent: debug dump failed: {}", e.getMessage());
        }
    }
}
