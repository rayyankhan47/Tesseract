package com.rayyan.tesseract.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal client for the Imagen predict REST API (Gemini-keyed).
 *
 * <p>Single-key (reuses {@code GEMINI_API_KEY} from the environment / {@code .env}).
 * POSTs to {@code models/imagen-3.0-generate-002:predict} and returns the decoded
 * PNG bytes of the first prediction.
 *
 * <p>Results are cached on disk under {@code run/tesseract_cache/concepts/}
 * keyed by SHA-256 of the prompt + aspect ratio + negative prompt tuple.
 * Cache hits skip the network round-trip entirely.
 */
public final class ImagenClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.imagen");
    private static final String BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:predict";
    private static final String DEFAULT_MODEL = "imagen-3.0-generate-002";

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Per-call retry policy for 5xx responses. */
    private static final int MAX_RETRIES = 2;
    private static final long BASE_RETRY_MS = 1_000L;
    private static final long JITTER_MS = 600L;
    private static final Random RANDOM = new Random();

    private static final Path CACHE_DIR = Paths.get("run", "tesseract_cache", "concepts");

    private final String apiKey;
    private final String modelId;

    public ImagenClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ImagenClient(String apiKey, String modelId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set.");
        }
        this.apiKey = apiKey;
        this.modelId = modelId;
    }

    /**
     * Reads {@code GEMINI_API_KEY} (Imagen shares the Gemini key). Falls back to
     * {@code .env} in the project root. Reuses the exact same loader path as
     * {@link GeminiClient#fromEnv()}.
     */
    public static ImagenClient fromEnv() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            key = readDotEnv("GEMINI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY is not set. Add it to a .env file in the project root:\n" +
                "  GEMINI_API_KEY=your_key_here");
        }
        return new ImagenClient(key);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a single concept image. Returns the decoded PNG bytes.
     *
     * @param prompt         positive concept prompt
     * @param negativePrompt what to avoid (cartoons, text overlays, signs …); may be null/empty
     * @param aspectRatio    one of Imagen's supported ratios ({@code "1:1"}, {@code "16:9"}, {@code "4:3"}, {@code "3:4"}, {@code "9:16"})
     */
    public CompletableFuture<byte[]> generate(String prompt, String negativePrompt, String aspectRatio) {
        String cacheKey = cacheKey(prompt, negativePrompt, aspectRatio);
        byte[] cached = loadFromCache(cacheKey);
        if (cached != null) {
            LOGGER.info("Imagen cache hit for key {}… ({} bytes)", cacheKey.substring(0, 8), cached.length);
            return CompletableFuture.completedFuture(cached);
        }

        JsonObject body = buildBody(prompt, negativePrompt, aspectRatio);
        return sendWithRetry(body, 0)
            .thenApply(png -> {
                writeToCache(cacheKey, png);
                return png;
            });
    }

    /** Convenience: 1:1 aspect ratio, no negative prompt. */
    public CompletableFuture<byte[]> generate(String prompt) {
        return generate(prompt, null, "1:1");
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<byte[]> sendWithRetry(JsonObject body, int attempt) {
        String url = String.format(BASE_URL, modelId) + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                int status = response.statusCode();

                if (status >= 500 && attempt < MAX_RETRIES) {
                    long delayMs = (BASE_RETRY_MS << attempt)
                            + (long) (RANDOM.nextDouble() * JITTER_MS);
                    LOGGER.warn("Imagen {} (attempt {}/{}); retrying in {}ms.",
                            status, attempt + 1, MAX_RETRIES + 1, delayMs);
                    return CompletableFuture
                            .runAsync(() -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                            .thenCompose(v -> sendWithRetry(body, attempt + 1));
                }

                if (status < 200 || status >= 300) {
                    CompletableFuture<byte[]> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException(
                            "Imagen API error " + status + ": " + preview(response.body())));
                    return failed;
                }

                try {
                    return CompletableFuture.completedFuture(extractPng(response.body()));
                } catch (Exception e) {
                    CompletableFuture<byte[]> failed = new CompletableFuture<>();
                    failed.completeExceptionally(e);
                    return failed;
                }
            });
    }

    private static JsonObject buildBody(String prompt, String negativePrompt, String aspectRatio) {
        JsonObject body = new JsonObject();

        JsonArray instances = new JsonArray();
        JsonObject instance = new JsonObject();
        instance.addProperty("prompt", prompt);
        instances.add(instance);
        body.add("instances", instances);

        JsonObject params = new JsonObject();
        params.addProperty("sampleCount", 1);
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            params.addProperty("aspectRatio", aspectRatio);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            params.addProperty("negativePrompt", negativePrompt);
        }
        body.add("parameters", params);

        return body;
    }

    private static byte[] extractPng(String responseBody) {
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            JsonObject obj = root.getAsJsonObject();
            JsonArray predictions = obj.getAsJsonArray("predictions");
            if (predictions == null || predictions.size() == 0) {
                throw new RuntimeException("Imagen returned no predictions. Body: " + preview(responseBody));
            }
            JsonObject first = predictions.get(0).getAsJsonObject();
            JsonElement b64El = first.get("bytesBase64Encoded");
            if (b64El == null) {
                // Some Imagen responses nest bytes under "image" — handle both shapes.
                JsonElement imgEl = first.get("image");
                if (imgEl != null && imgEl.isJsonObject()) {
                    b64El = imgEl.getAsJsonObject().get("bytesBase64Encoded");
                }
            }
            if (b64El == null) {
                throw new RuntimeException("Imagen prediction missing bytesBase64Encoded: "
                        + preview(responseBody));
            }
            return Base64.getDecoder().decode(b64El.getAsString());
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("Failed to parse Imagen response: " + preview(responseBody), e);
        }
    }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    private static String cacheKey(String prompt, String negativePrompt, String aspectRatio) {
        String blob = (prompt == null ? "" : prompt) + "|"
                + (negativePrompt == null ? "" : negativePrompt) + "|"
                + (aspectRatio == null ? "" : aspectRatio);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(blob.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(blob.hashCode());
        }
    }

    private static byte[] loadFromCache(String key) {
        try {
            Path file = CACHE_DIR.resolve(key + ".png");
            if (Files.exists(file)) {
                return Files.readAllBytes(file);
            }
        } catch (Exception e) {
            LOGGER.debug("Imagen cache read failed for {}: {}", key, e.getMessage());
        }
        return null;
    }

    private static void writeToCache(String key, byte[] png) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path file = CACHE_DIR.resolve(key + ".png");
            Files.write(file, png);
        } catch (Exception e) {
            LOGGER.warn("Imagen cache write failed for {}: {}", key, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // .env loader (copy of GeminiClient's path — kept internal to avoid
    // cross-client coupling on a private static method)
    // -------------------------------------------------------------------------

    private static String readDotEnv(String varName) {
        Path dotEnv = null;
        Path candidate = Path.of(".env").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            if (Files.exists(candidate)) { dotEnv = candidate; break; }
            Path parent = candidate.getParent() == null ? null : candidate.getParent().getParent();
            if (parent == null) break;
            candidate = parent.resolve(".env");
        }
        if (dotEnv == null) return null;
        try {
            for (String line : Files.readAllLines(dotEnv)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                if (!line.substring(0, eq).strip().equals(varName)) continue;
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                         || (value.startsWith("'")  && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isBlank() ? null : value;
            }
        } catch (Exception e) {
            // .env unreadable — fall through to the missing-key error
        }
        return null;
    }

    private static String preview(String text) {
        if (text == null) return "<null>";
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }
}
