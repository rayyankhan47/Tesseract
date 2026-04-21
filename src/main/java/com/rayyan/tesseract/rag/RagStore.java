package com.rayyan.tesseract.rag;

import com.rayyan.tesseract.api.EmbeddingClient;
import com.rayyan.tesseract.render.IsoRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Process-wide RAG singleton. Bootstraps on mod init (async), survives for
 * the server lifetime. Agents call {@link #topK} from the {@code RAG_PLAN}
 * phase without worrying about embedding or caching.
 *
 * <p>Bootstrap sequence (§4.2.1):
 * <ol>
 *   <li>Load corpus from {@code architecture_corpus.jsonl}.</li>
 *   <li>Read cached vectors from {@code run/tesseract_cache/embeddings.bin}.</li>
 *   <li>For any current entries missing / stale in the cache, batch-embed
 *       them via {@code text-embedding-004}.</li>
 *   <li>Merge cache + fresh embeddings, write them back to disk.</li>
 * </ol>
 *
 * <p>If bootstrap fails (no API key, network outage), the store lands in a
 * {@linkplain #isReady() not-ready} state and downstream agents must fall
 * through to the text-only path.
 */
public final class RagStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.rag");

    public static final int DEFAULT_TOP_K = 6;
    private static final String CACHE_FILENAME = "embeddings.bin";

    private static volatile RagStore INSTANCE;

    private final List<CorpusEntry> corpus;
    private volatile VectorStore store;
    private volatile boolean bootstrapped;
    private volatile String bootstrapError;

    private RagStore(List<CorpusEntry> corpus) {
        this.corpus = corpus;
        this.store = null;
        this.bootstrapped = false;
    }

    // -------------------------------------------------------------------------
    // Singleton lifecycle
    // -------------------------------------------------------------------------

    /** Kicks off an async bootstrap. Safe to call once on mod init. */
    public static synchronized CompletableFuture<RagStore> initAsync() {
        if (INSTANCE != null && INSTANCE.isReady()) {
            return CompletableFuture.completedFuture(INSTANCE);
        }
        List<CorpusEntry> entries = CorpusLoader.loadDefault();
        RagStore store = new RagStore(entries);
        INSTANCE = store;

        if (entries.isEmpty()) {
            store.bootstrapError = "corpus empty";
            store.bootstrapped = true;
            return CompletableFuture.completedFuture(store);
        }

        return CompletableFuture.supplyAsync(store::bootstrapSync)
                .exceptionally(err -> {
                    LOGGER.warn("RagStore: bootstrap failed: {}", err.getMessage());
                    store.bootstrapError = err.getMessage();
                    store.bootstrapped = true;
                    return store;
                });
    }

    /** @return the initialized singleton or null if {@link #initAsync} hasn't run. */
    public static RagStore get() { return INSTANCE; }

    public boolean isReady() { return bootstrapped && store != null && store.size() > 0; }
    public String bootstrapError() { return bootstrapError; }
    public int size() { return store == null ? 0 : store.size(); }

    // -------------------------------------------------------------------------
    // Retrieval
    // -------------------------------------------------------------------------

    /** Top-k neighbors for the given query embedding. Empty if not ready. */
    public List<VectorStore.Hit> topK(float[] queryEmbedding, int k) {
        if (!isReady()) return List.of();
        return store.topK(queryEmbedding, k);
    }

    /**
     * Convenience: embeds the query string then returns top-k neighbors.
     * Primarily a test-path hook — production callers should batch their
     * embedding calls via the {@link EmbeddingClient} they already hold.
     */
    public CompletableFuture<List<VectorStore.Hit>> topK(EmbeddingClient embed,
                                                         String query, int k) {
        if (!isReady()) return CompletableFuture.completedFuture(List.of());
        return embed.embed(query).thenApply(vec -> store.topK(vec, k));
    }

    // -------------------------------------------------------------------------
    // Bootstrap
    // -------------------------------------------------------------------------

    private RagStore bootstrapSync() {
        Path cachePath = resolveCachePath();
        VectorStore.CacheReadResult cacheResult;
        try {
            cacheResult = VectorStore.readCache(cachePath, corpus);
        } catch (IOException e) {
            LOGGER.warn("RagStore: cache read failed: {} — rebuilding all", e.getMessage());
            cacheResult = new VectorStore.CacheReadResult(List.of(), List.of(), corpus);
        }

        List<CorpusEntry> allEntries = new ArrayList<>(corpus.size());
        List<float[]> allVectors = new ArrayList<>(corpus.size());
        allEntries.addAll(cacheResult.cachedEntries());
        allVectors.addAll(cacheResult.cachedVectors());

        List<CorpusEntry> missing = cacheResult.missing();
        if (!missing.isEmpty()) {
            LOGGER.info("RagStore: embedding {} new/changed entries via {}",
                    missing.size(), EmbeddingClient.DEFAULT_MODEL);
            EmbeddingClient embed = EmbeddingClient.fromEnv();
            List<String> texts = missing.stream().map(CorpusEntry::embeddingText).toList();
            List<float[]> fresh = embed.embedBatch(texts).join();
            if (fresh.size() != missing.size()) {
                throw new RuntimeException("Expected " + missing.size()
                        + " embeddings, got " + fresh.size());
            }
            allEntries.addAll(missing);
            allVectors.addAll(fresh);
        }

        this.store = VectorStore.of(allEntries, allVectors);
        try {
            VectorStore.writeCache(cachePath, allEntries, allVectors);
        } catch (IOException e) {
            LOGGER.warn("RagStore: failed to persist cache to {}: {}", cachePath, e.getMessage());
        }
        this.bootstrapped = true;
        LOGGER.info("RagStore: ready — {} entries (cache_hits={}, fresh={})",
                allEntries.size(), cacheResult.cachedEntries().size(), missing.size());
        return this;
    }

    /**
     * Resolves the embedding cache path. Mirrors {@link IsoRenderer#debugDir()}'s
     * working-directory logic so the cache lands next to other Tesseract caches
     * whether the server runs from the repo root or from {@code run/}.
     */
    private static Path resolveCachePath() {
        Path cwd = Path.of("").toAbsolutePath();
        Path base = cwd.getFileName() != null && cwd.getFileName().toString().equals("run")
                ? cwd
                : cwd.resolve("run");
        return base.resolve("tesseract_cache").resolve(CACHE_FILENAME);
    }
}
