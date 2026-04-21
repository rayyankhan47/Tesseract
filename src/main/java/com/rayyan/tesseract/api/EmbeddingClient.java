package com.rayyan.tesseract.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Google Generative Language embeddings client — {@code text-embedding-004}.
 *
 * <p>Returns a float[768] embedding per input. Supports single and batch
 * ({@code batchEmbedContents}) requests; the latter is used for the one-shot
 * corpus bootstrap so we don't burn 150+ individual HTTPS calls on startup.
 *
 * <p>Per REFACTOR_3 §4.2.1 — the resulting vectors are cached to disk keyed by
 * model + corpus version so the next JVM start can skip the API round-trip.
 */
public final class EmbeddingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.embed");

    public static final String DEFAULT_MODEL = "text-embedding-004";
    private static final int EMBEDDING_DIM = 768;

    private static final String SINGLE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent?key=%s";
    private static final String BATCH_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents?key=%s";

    private static final int BATCH_SIZE = 100;        // Gemini limit for batchEmbedContents
    private static final long TIMEOUT_MS = 60_000L;

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String apiKey;
    private final String modelId;

    public EmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public EmbeddingClient(String apiKey, String modelId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY required for EmbeddingClient");
        }
        this.apiKey = apiKey;
        this.modelId = modelId == null ? DEFAULT_MODEL : modelId;
    }

    public static EmbeddingClient fromEnv() {
        return new EmbeddingClient(GeminiClient.resolveApiKey());
    }

    public String modelId() { return modelId; }

    /** Embeds a single text chunk. */
    public CompletableFuture<float[]> embed(String text) {
        JsonObject body = buildSingleBody(text);
        String url = String.format(SINGLE_URL, modelId, apiKey);
        return postJson(url, body)
                .thenApply(EmbeddingClient::parseSingle);
    }

    /**
     * Embeds many texts in batches of up to 100 per request. The output list
     * preserves input order. Whole-batch failures surface as an exception; the
     * caller should retry or fall back (e.g. cosine search skipped).
     */
    public CompletableFuture<List<float[]>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            chunks.add(texts.subList(i, Math.min(i + BATCH_SIZE, texts.size())));
        }
        CompletableFuture<List<float[]>> acc = CompletableFuture.completedFuture(new ArrayList<>());
        for (List<String> chunk : chunks) {
            acc = acc.thenCompose(collected ->
                    embedOneBatch(chunk).thenApply(batchResult -> {
                        collected.addAll(batchResult);
                        return collected;
                    }));
        }
        return acc;
    }

    private CompletableFuture<List<float[]>> embedOneBatch(List<String> texts) {
        JsonObject body = buildBatchBody(texts);
        String url = String.format(BATCH_URL, modelId, apiKey);
        return postJson(url, body)
                .thenApply(json -> parseBatch(json, texts.size()));
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private CompletableFuture<String> postJson(String url, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(resp -> {
                    int status = resp.statusCode();
                    if (status == 429 || status >= 500) {
                        LOGGER.warn("TRANSIENT_RETRY embed status={} body={}",
                                status, previewBody(resp.body()));
                        return CompletableFuture
                                .runAsync(() -> {}, CompletableFuture.delayedExecutor(2_000, TimeUnit.MILLISECONDS))
                                .thenCompose(v -> HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                        .thenApply(r -> {
                                            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                                                throw new RuntimeException(
                                                        "Embedding API " + r.statusCode() + ": "
                                                                + previewBody(r.body()));
                                            }
                                            return r.body();
                                        }));
                    }
                    if (status < 200 || status >= 300) {
                        CompletableFuture<String> failed = new CompletableFuture<>();
                        failed.completeExceptionally(new RuntimeException(
                                "Embedding API " + status + ": " + previewBody(resp.body())));
                        return failed;
                    }
                    return CompletableFuture.completedFuture(resp.body());
                });
    }

    // -------------------------------------------------------------------------
    // Request bodies
    // -------------------------------------------------------------------------

    private JsonObject buildSingleBody(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", "models/" + modelId);
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text == null ? "" : text);
        parts.add(part);
        content.add("parts", parts);
        body.add("content", content);
        return body;
    }

    private JsonObject buildBatchBody(List<String> texts) {
        JsonObject body = new JsonObject();
        JsonArray requests = new JsonArray();
        for (String text : texts) {
            JsonObject req = new JsonObject();
            req.addProperty("model", "models/" + modelId);
            JsonObject content = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", text == null ? "" : text);
            parts.add(part);
            content.add("parts", parts);
            req.add("content", content);
            requests.add(req);
        }
        body.add("requests", requests);
        return body;
    }

    // -------------------------------------------------------------------------
    // Response parsers
    // -------------------------------------------------------------------------

    private static float[] parseSingle(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject embedding = root.getAsJsonObject("embedding");
        if (embedding == null) throw new RuntimeException("Missing 'embedding' in response");
        JsonArray values = embedding.getAsJsonArray("values");
        if (values == null) throw new RuntimeException("Missing 'embedding.values'");
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i).getAsFloat();
        return out;
    }

    private static List<float[]> parseBatch(String json, int expected) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray embeddings = root.getAsJsonArray("embeddings");
        if (embeddings == null) throw new RuntimeException("Missing 'embeddings' in batch response");
        List<float[]> out = new ArrayList<>(embeddings.size());
        for (JsonElement el : embeddings) {
            JsonArray values = el.getAsJsonObject().getAsJsonArray("values");
            float[] vec = new float[values.size()];
            for (int i = 0; i < values.size(); i++) vec[i] = values.get(i).getAsFloat();
            out.add(vec);
        }
        if (out.size() != expected) {
            LOGGER.warn("Batch embedding returned {} vectors, expected {}", out.size(), expected);
        }
        return out;
    }

    private static String previewBody(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** Static helper so callers can confirm the expected embedding dimension. */
    public static int expectedDimension() { return EMBEDDING_DIM; }
}
