package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.api.EmbeddingClient;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.rag.CorpusEntry;
import com.rayyan.tesseract.rag.RagStore;
import com.rayyan.tesseract.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * §4.3 — retrieves architectural knowledge and injects it into the build.
 *
 * <ol>
 *   <li>Asks Gemini Flash for a one-sentence caption of the selected concept
 *       image (§4.2.3). If the image is missing or captioning fails, we fall
 *       back to the raw prompt — RAG should degrade, not error.</li>
 *   <li>Embeds {@code prompt + caption} via {@code text-embedding-004}.</li>
 *   <li>Hits {@link RagStore#topK} for the top-6 nearest entries.</li>
 *   <li>Writes the retrieved rows to {@link BuildState#ragContext} and stores
 *       the caption in {@link BuildState#conceptCaption}.</li>
 * </ol>
 *
 * <p>Downstream L1/L2/L3 agents read {@link BuildState#ragContext} directly
 * and call {@link #formatContextBlock(List, int)} to inject the context into
 * their prompts uniformly.
 */
public final class RagAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.rag_agent");

    public static final int TOP_K = RagStore.DEFAULT_TOP_K;

    private static final String CAPTION_SYSTEM_PROMPT = """
            You are a concise architectural observer. Given a reference image,
            return exactly one sentence (max 25 words) describing its built form:
            style cues, silhouette shape, notable features. No preamble, no
            punctuation beyond the single sentence. Output prose only.
            """;

    private RagAgent() {}

    /**
     * Runs the full caption → embed → retrieve pipeline, populating the
     * given {@link BuildState} in place. The returned future completes with
     * {@code true} if retrieval produced at least one hit; {@code false}
     * means we degraded to no-context (text-only planning) but the build
     * continues.
     */
    public static CompletableFuture<Boolean> run(BuildState state,
                                                 GeminiClient gemini,
                                                 EmbeddingClient embed) {
        RagStore store = RagStore.get();
        if (store == null || !store.isReady()) {
            LOGGER.warn("RAG_SKIPPED reason=store_not_ready error={}",
                    store == null ? "uninitialized" : store.bootstrapError());
            return CompletableFuture.completedFuture(false);
        }
        if (state.originalPrompt == null || state.originalPrompt.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<String> captionFuture = captionConcept(state, gemini);

        return captionFuture.thenCompose(caption -> {
            state.conceptCaption = caption;
            String query = buildQuery(state.originalPrompt, caption);
            LOGGER.info("RAG_QUERY prompt_len={} caption_len={}",
                    state.originalPrompt.length(),
                    caption == null ? 0 : caption.length());

            return embed.embed(query)
                    .thenApply(vec -> store.topK(vec, TOP_K))
                    .thenApply(hits -> {
                        if (hits.isEmpty()) {
                            LOGGER.warn("RAG_NO_HITS — corpus did not match query");
                            return false;
                        }
                        List<CorpusEntry> entries = new ArrayList<>(hits.size());
                        StringBuilder trace = new StringBuilder();
                        for (int i = 0; i < hits.size(); i++) {
                            VectorStore.Hit hit = hits.get(i);
                            entries.add(hit.entry());
                            if (i > 0) trace.append(", ");
                            trace.append(hit.entry().id())
                                    .append('=')
                                    .append(String.format("%.3f", hit.similarity()));
                        }
                        state.ragContext.clear();
                        state.ragContext.addAll(entries);
                        LOGGER.info("RAG_HITS count={} top=[{}]", entries.size(), trace);
                        return true;
                    });
        }).exceptionally(err -> {
            LOGGER.warn("RAG_SKIPPED reason=pipeline_error error={}", err.getMessage());
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    private static CompletableFuture<String> captionConcept(BuildState state, GeminiClient gemini) {
        ReferenceImage concept = selectedConcept(state);
        if (concept == null) {
            return CompletableFuture.completedFuture("");
        }
        List<ImagePart> parts = List.of(new ImagePart(concept.bytes(), concept.mimeType()));
        String userPrompt =
                "Caption this concept image in one sentence (max 25 words). "
              + "Intended build: \"" + trunc(state.originalPrompt, 200) + "\"";

        return gemini.call(TaskKind.IMAGE_CAPTION,
                        CAPTION_SYSTEM_PROMPT, userPrompt, parts, state.costTracker)
                .thenApply(raw -> {
                    if (raw == null) return "";
                    String cleaned = raw.strip();
                    if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
                        cleaned = cleaned.substring(1, cleaned.length() - 1);
                    }
                    return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
                })
                .exceptionally(err -> {
                    LOGGER.warn("IMAGE_CAPTION failed, falling back to prompt-only query: {}",
                            err.getMessage());
                    return "";
                });
    }

    private static ReferenceImage selectedConcept(BuildState state) {
        if (state.referenceImages.isEmpty()) return null;
        int idx = state.selectedConceptIndex;
        if (idx < 0 || idx >= state.referenceImages.size()) idx = 0;
        return state.referenceImages.get(idx);
    }

    private static String buildQuery(String prompt, String caption) {
        StringBuilder sb = new StringBuilder(prompt.length() + (caption == null ? 0 : caption.length()) + 32);
        sb.append("Architectural build: ").append(prompt);
        if (caption != null && !caption.isBlank()) {
            sb.append(" | Concept: ").append(caption);
        }
        return sb.toString();
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // -------------------------------------------------------------------------
    // Prompt injection helpers (§4.3.1)
    // -------------------------------------------------------------------------

    /**
     * Formats the top-k retrieved entries as a single markdown block ready
     * to drop into an L1/L2/L3 system prompt.
     *
     * <p>The first entry is rendered in full (full description + features +
     * dimensions); the remaining entries are compacted so the whole block
     * stays under a few hundred tokens per build.
     *
     * @param maxShort cap on short-format rows after the leading detailed row
     */
    public static String formatContextBlock(List<CorpusEntry> entries, int maxShort) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Reference knowledge (retrieved from architectural corpus):\n\n");
        sb.append(entries.get(0).toPromptBlock()).append('\n');
        int shorts = Math.min(maxShort, entries.size() - 1);
        if (shorts > 0) {
            sb.append("Supporting entries:\n");
            for (int i = 1; i <= shorts; i++) {
                sb.append(entries.get(i).toShortPrompt()).append('\n');
            }
        }
        sb.append("\nGround proportions and features in these. When a choice is ")
          .append("influenced by a specific entry, cite it by id in your output's ")
          .append("\"citing\" field (e.g. \"citing\": [\"ex_notre_dame\"]).");
        return sb.toString();
    }

    /**
     * Records a citation in the build's timeline. Agents call this from
     * their response-parse stage when they emit {@code "citing": [...]}
     * in a structured output (§4.3.3).
     */
    public static void recordCitations(BuildState state, List<String> citedIds) {
        if (citedIds == null || citedIds.isEmpty()) return;
        state.ragCitations.addAll(citedIds);
    }
}
