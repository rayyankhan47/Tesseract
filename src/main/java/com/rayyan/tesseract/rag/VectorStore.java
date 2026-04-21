package com.rayyan.tesseract.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory cosine-similarity vector store for RAG (§4.2.2).
 *
 * <p>Backed by parallel arrays: {@code entries[i]} and {@code vectors[i]} are
 * paired. Vectors are L2-normalized on ingest so cosine similarity becomes a
 * dot product. Top-k retrieval is a full linear scan — fine for corpora in
 * the low-hundreds; if the corpus ever grows past ~10k entries this should be
 * swapped for HNSW or similar.
 *
 * <p>Binary cache format (§4.2.1):
 * <pre>
 *   int    MAGIC   = 0x54455343 ('TESC')
 *   int    version = 1
 *   int    dim
 *   int    count
 *   for each entry:
 *     utf    id
 *     utf    signature (name|period|features|dims) — cache-key sanity check
 *     float[dim] values
 * </pre>
 */
public final class VectorStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.vector_store");

    private static final int MAGIC = 0x54455343; // "TESC"
    private static final int VERSION = 1;

    private final List<CorpusEntry> entries;
    private final float[][] vectors;
    private final int dim;

    private VectorStore(List<CorpusEntry> entries, float[][] vectors, int dim) {
        if (entries.size() != vectors.length) {
            throw new IllegalArgumentException("entries/vectors size mismatch");
        }
        this.entries = List.copyOf(entries);
        this.vectors = vectors;
        this.dim = dim;
    }

    public int size() { return entries.size(); }
    public int dimension() { return dim; }
    public List<CorpusEntry> entries() { return entries; }

    /** Builds a store from entries + matched embeddings, L2-normalizing copies. */
    public static VectorStore of(List<CorpusEntry> entries, List<float[]> vectors) {
        if (entries.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "entries (" + entries.size() + ") != vectors (" + vectors.size() + ")");
        }
        if (entries.isEmpty()) {
            return new VectorStore(List.of(), new float[0][], 0);
        }
        int dim = vectors.get(0).length;
        float[][] normalized = new float[vectors.size()][];
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            if (v.length != dim) {
                throw new IllegalArgumentException(
                        "Embedding dim mismatch at " + entries.get(i).id() + ": " + v.length + " vs " + dim);
            }
            normalized[i] = normalize(v);
        }
        return new VectorStore(entries, normalized, dim);
    }

    // -------------------------------------------------------------------------
    // Retrieval
    // -------------------------------------------------------------------------

    /**
     * Top-k cosine nearest entries to {@code query}. Query is L2-normalized
     * on entry; callers can pass raw embedding vectors.
     */
    public List<Hit> topK(float[] query, int k) {
        if (entries.isEmpty() || k <= 0) return List.of();
        if (query.length != dim) {
            throw new IllegalArgumentException("query dim " + query.length + " != store dim " + dim);
        }
        float[] normQuery = normalize(query);

        // Single-pass partial sort — for k in the single digits and N in the
        // hundreds, a simple sorted ArrayList is easier to audit than a heap.
        List<Hit> allHits = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            float sim = dot(normQuery, vectors[i]);
            allHits.add(new Hit(entries.get(i), sim));
        }
        allHits.sort(Comparator.comparingDouble((Hit h) -> h.similarity()).reversed());
        return Collections.unmodifiableList(allHits.subList(0, Math.min(k, allHits.size())));
    }

    public record Hit(CorpusEntry entry, float similarity) {}

    // -------------------------------------------------------------------------
    // Math helpers
    // -------------------------------------------------------------------------

    private static float[] normalize(float[] v) {
        double norm2 = 0.0;
        for (float f : v) norm2 += (double) f * f;
        double norm = Math.sqrt(norm2);
        if (norm < 1e-12) return v.clone();
        float[] out = new float[v.length];
        float inv = (float) (1.0 / norm);
        for (int i = 0; i < v.length; i++) out[i] = v[i] * inv;
        return out;
    }

    private static float dot(float[] a, float[] b) {
        float sum = 0.0f;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    // -------------------------------------------------------------------------
    // On-disk cache (§4.2.1)
    // -------------------------------------------------------------------------

    /**
     * Writes {@code entries[i] → vectors[i]} to {@code cachePath}. Per-entry
     * signature binds the vector to the entry's current content, so any edit
     * to the corpus silently invalidates the row on next load.
     */
    public static void writeCache(Path cachePath,
                                  List<CorpusEntry> entries,
                                  List<float[]> vectors) throws IOException {
        if (entries.size() != vectors.size()) {
            throw new IllegalArgumentException("entries/vectors size mismatch");
        }
        Files.createDirectories(cachePath.getParent());
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(cachePath,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(dim);
            out.writeInt(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                CorpusEntry e = entries.get(i);
                float[] v = vectors.get(i);
                out.writeUTF(e.id());
                out.writeUTF(signature(e));
                for (float f : v) out.writeFloat(f);
            }
            LOGGER.info("VectorStore: wrote {} vectors (dim={}) to {}",
                    entries.size(), dim, cachePath);
        }
    }

    /**
     * Reads the cache and pairs each stored vector with its current entry. If
     * a row's signature drifted (corpus was edited), that row is dropped so
     * the bootstrap will re-embed just the dirty entries.
     *
     * @return a partial store plus the set of entry ids missing from the cache
     */
    public static CacheReadResult readCache(Path cachePath,
                                            List<CorpusEntry> currentEntries) throws IOException {
        if (!Files.exists(cachePath)) {
            return new CacheReadResult(List.of(), List.of(), currentEntries);
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(cachePath))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                LOGGER.warn("VectorStore: cache magic mismatch at {} — rebuilding", cachePath);
                return new CacheReadResult(List.of(), List.of(), currentEntries);
            }
            int version = in.readInt();
            if (version != VERSION) {
                LOGGER.warn("VectorStore: cache version {} != {} — rebuilding",
                        version, VERSION);
                return new CacheReadResult(List.of(), List.of(), currentEntries);
            }
            int dim = in.readInt();
            int count = in.readInt();

            java.util.Map<String, CorpusEntry> byId = new java.util.HashMap<>();
            for (CorpusEntry e : currentEntries) byId.put(e.id(), e);

            List<CorpusEntry> hitEntries = new ArrayList<>(count);
            List<float[]> hitVectors = new ArrayList<>(count);
            java.util.Set<String> hitIds = new java.util.HashSet<>();

            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                String sig = in.readUTF();
                float[] v = new float[dim];
                for (int j = 0; j < dim; j++) v[j] = in.readFloat();

                CorpusEntry current = byId.get(id);
                if (current == null) continue;                 // row removed from corpus
                if (!signature(current).equals(sig)) continue; // row edited — needs re-embed
                hitEntries.add(current);
                hitVectors.add(v);
                hitIds.add(id);
            }

            List<CorpusEntry> missing = new ArrayList<>();
            for (CorpusEntry e : currentEntries) {
                if (!hitIds.contains(e.id())) missing.add(e);
            }
            LOGGER.info("VectorStore: loaded {} cached vectors (dim={}), {} missing",
                    hitEntries.size(), dim, missing.size());
            return new CacheReadResult(hitEntries, hitVectors, missing);
        } catch (IOException e) {
            LOGGER.warn("VectorStore: failed to read {} ({}) — rebuilding",
                    cachePath, e.getMessage());
            return new CacheReadResult(List.of(), List.of(), currentEntries);
        }
    }

    /** Cache-signature for a row — any change to content triggers re-embed. */
    private static String signature(CorpusEntry e) {
        return Integer.toHexString(e.embeddingText().hashCode());
    }

    /**
     * Outcome of a cache read: entry/vector pairs that survived validation,
     * plus the list of current entries still needing embeddings.
     * Vectors are raw (pre-normalization) exactly as written to disk.
     */
    public record CacheReadResult(List<CorpusEntry> cachedEntries,
                                  List<float[]> cachedVectors,
                                  List<CorpusEntry> missing) {}
}
