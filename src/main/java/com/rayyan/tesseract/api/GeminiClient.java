package com.rayyan.tesseract.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal client for the Gemini generateContent REST API.
 *
 * Text-only:       gemini.complete(systemPrompt, userPrompt)
 * With image ref:  gemini.complete(systemPrompt, userPrompt, imageBytes, "image/png")
 *
 * Both overloads return CompletableFuture<String> containing the raw text of
 * the first candidate, so callers stay non-blocking on the server thread.
 */
public final class GeminiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.gemini");
    private static final String BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    /**
     * Models tried in order. On a 5xx the client falls through to the next entry;
     * non-5xx errors (4xx, parse failures) fail immediately without fallback.
     */
    private static final List<String> MODELS = List.of(
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    );
    /**
     * Per-model retry policy for 5xx responses.
     * Each model is tried up to (MAX_RETRIES_PER_MODEL + 1) times before falling back
     * to the next model in the list.
     * Delay formula: BASE_RETRY_MS * 2^attempt + random jitter in [0, JITTER_MS).
     */
    private static final int MAX_RETRIES_PER_MODEL = 2;
    private static final long BASE_RETRY_MS = 1_000L;
    private static final long JITTER_MS = 600L;
    private static final Random RANDOM = new Random();

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String apiKey;

    public GeminiClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set.");
        }
        this.apiKey = apiKey;
    }

    /**
     * Reads {@code GEMINI_API_KEY} from the environment, falling back to a
     * {@code .env} file in the current working directory (project root when
     * running via {@code ./gradlew runServer}).
     *
     * .env format — one {@code KEY=value} pair per line, {@code #} comments ignored,
     * optional surrounding quotes stripped:
     * <pre>
     *   GEMINI_API_KEY=AIza...
     * </pre>
     */
    public static GeminiClient fromEnv() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            key = readDotEnv("GEMINI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY is not set. Add it to a .env file in the project root:\n" +
                "  GEMINI_API_KEY=your_key_here");
        }
        return new GeminiClient(key);
    }

    /**
     * Looks for {@code varName=value} in {@code .env} (CWD).
     * Returns null if the file doesn't exist or the key isn't present.
     */
    private static String readDotEnv(String varName) {
        // Minecraft's CWD is run/ — walk up to find .env in the project root too.
        Path dotEnv = null;
        Path candidate = Path.of(".env").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            if (Files.exists(candidate)) { dotEnv = candidate; break; }
            Path parent = candidate.getParent().getParent();
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
                // Strip optional surrounding single or double quotes
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                         || (value.startsWith("'")  && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isBlank() ? null : value;
            }
        } catch (IOException e) {
            // .env unreadable — fall through to the missing-key error
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Text-only completion. */
    public CompletableFuture<String> complete(String systemPrompt, String userPrompt) {
        return send(buildBody(systemPrompt, userPrompt, null, null));
    }

    /** Multimodal completion — attaches imageBytes as an inlineData part. */
    public CompletableFuture<String> complete(String systemPrompt, String userPrompt,
                                               byte[] imageBytes, String mimeType) {
        return send(buildBody(systemPrompt, userPrompt, imageBytes, mimeType));
    }

    /**
     * Multi-image variant that still uses the {@link #MODELS} fallback chain.
     *
     * <p>The first image in {@code images} is treated as the primary focus; any
     * others should be referenced in the {@code userPrompt} by index
     * ("image [0] is the current render, image [1] is the concept reference").
     */
    public CompletableFuture<String> complete(String systemPrompt, String userPrompt,
                                               List<ImagePart> images) {
        return send(buildMultiImageBody(systemPrompt, userPrompt, images));
    }

    /**
     * Multi-image multimodal call pinned to a specific model (no fallback chain).
     *
     * <p>Used by agents that need a specific model tier (e.g. Gemini 2.5 Pro for
     * the concept auto-selector). Retries the same model on 5xx; bubbles up the
     * error on all other non-2xx.
     *
     * <p>Step 3 will replace this with a unified {@code call(TaskKind, …)} path;
     * until then this is the escape hatch.
     *
     * @param modelId       fully-qualified model id (e.g. {@code gemini-2.5-pro})
     * @param systemPrompt  system instruction, may be null
     * @param userPrompt    text part; always prepended to the parts array
     * @param images        ordered list of image parts; may be empty or null
     */
    public CompletableFuture<String> completeWithModel(String modelId,
                                                       String systemPrompt,
                                                       String userPrompt,
                                                       List<ImagePart> images) {
        return completeWithModel(modelId, systemPrompt, userPrompt, images, null);
    }

    /**
     * Pinned-model variant that also sets a {@link GenerationConfig}
     * (temperature, maxOutputTokens). Used by agents that need fine control
     * (e.g. {@code MassExtractionAgent} pins temperature 0.2, maxOutputTokens 16k).
     */
    public CompletableFuture<String> completeWithModel(String modelId,
                                                       String systemPrompt,
                                                       String userPrompt,
                                                       List<ImagePart> images,
                                                       GenerationConfig config) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        JsonObject body = buildMultiImageBody(systemPrompt, userPrompt, images);
        if (config != null) {
            body.add("generationConfig", config.toJson());
        }
        return sendPinned(body, modelId, 0);
    }

    /**
     * Subset of the Gemini {@code generationConfig} knobs we actually use.
     * Fields are nullable — unset values are omitted from the request.
     */
    public record GenerationConfig(Double temperature,
                                   Integer maxOutputTokens,
                                   Double topP,
                                   Integer topK) {
        public static GenerationConfig of(double temperature, int maxOutputTokens) {
            return new GenerationConfig(temperature, maxOutputTokens, null, null);
        }

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            if (temperature != null)      obj.addProperty("temperature", temperature);
            if (maxOutputTokens != null)  obj.addProperty("maxOutputTokens", maxOutputTokens);
            if (topP != null)             obj.addProperty("topP", topP);
            if (topK != null)             obj.addProperty("topK", topK);
            return obj;
        }
    }

    /**
     * Inline image payload for multimodal calls. Field order matches the
     * Gemini REST schema: mime type, then base64 bytes.
     */
    public record ImagePart(byte[] bytes, String mimeType) {
        public ImagePart {
            if (bytes == null) throw new IllegalArgumentException("bytes is required");
            if (mimeType == null || mimeType.isBlank()) mimeType = "image/png";
        }
    }

    /**
     * Accepts a raw response string; returns {@code true} if the response is
     * valid/usable, {@code false} if the call should escalate.
     * Used by {@link #call(TaskKind, String, String, List, Validator, String, CostTracker)}
     * to drive the §3.2.3 parse-failure / refusal / empty escalation chain.
     */
    @FunctionalInterface
    public interface Validator { boolean isValid(String raw); }

    // =========================================================================
    // Unified TaskKind-routed call (§3.1 / §3.2)
    // =========================================================================

    /**
     * The v3 canonical entry point: resolves the model via {@link ModelRegistry},
     * runs the failure-class retry chains (transient, rate-limit, parse-refusal)
     * per REFACTOR_3 §3.2, and — when a {@link CostTracker} is supplied — records
     * per-call token usage attributed to whichever model actually served the
     * request.
     */
    public CompletableFuture<String> call(TaskKind kind,
                                          String systemPrompt,
                                          String userPrompt,
                                          List<ImagePart> images) {
        return call(kind, systemPrompt, userPrompt, images, null, null, null);
    }

    public CompletableFuture<String> call(TaskKind kind,
                                          String systemPrompt,
                                          String userPrompt,
                                          List<ImagePart> images,
                                          CostTracker cost) {
        return call(kind, systemPrompt, userPrompt, images, null, null, cost);
    }

    /**
     * @param validator         nullable — if present and returns false, the
     *                          call escalates up the chain with a reminder.
     * @param stricterReminder  text appended to {@code systemPrompt} on each
     *                          escalation; ignored when {@code validator} is null.
     */
    public CompletableFuture<String> call(TaskKind kind,
                                          String systemPrompt,
                                          String userPrompt,
                                          List<ImagePart> images,
                                          Validator validator,
                                          String stricterReminder,
                                          CostTracker cost) {
        ModelSpec spec = ModelRegistry.get(kind);
        return runEscalation(kind, spec, systemPrompt, userPrompt, images,
                             validator, stricterReminder, cost, 0);
    }

    private CompletableFuture<String> runEscalation(TaskKind kind,
                                                    ModelSpec spec,
                                                    String systemPrompt,
                                                    String userPrompt,
                                                    List<ImagePart> images,
                                                    Validator validator,
                                                    String stricterReminder,
                                                    CostTracker cost,
                                                    int escalationIdx) {
        List<String> chain = spec.escalationChain();
        int clampedIdx = Math.min(escalationIdx, chain.size() - 1);
        String modelId = chain.get(clampedIdx);

        // Append stricter reminder on escalation, never on first attempt.
        String sys = systemPrompt;
        if (escalationIdx > 0 && stricterReminder != null && !stricterReminder.isBlank()) {
            sys = (systemPrompt == null ? "" : systemPrompt + "\n\n") + stricterReminder;
        }

        JsonObject body = buildMultiImageBody(sys, userPrompt, images);
        GenerationConfig genCfg = spec.toGenerationConfig();
        if (genCfg != null) body.add("generationConfig", genCfg.toJson());

        return sendOnceWithRetries(modelId, spec.timeoutMs(), body, 0)
            // §3.2.2 — one-shot downshift on rate-limit / quota exhaustion.
            .thenCompose(result -> {
                if (result.isRateLimited() && spec.downshiftTarget() != null
                        && !spec.downshiftTarget().equals(modelId)) {
                    LOGGER.warn("QUOTA_DOWNSHIFT kind={} from={} to={}",
                            kind, modelId, spec.downshiftTarget());
                    return sendOnceWithRetries(spec.downshiftTarget(), spec.timeoutMs(), body, 0);
                }
                if (result.error != null) {
                    CompletableFuture<CallResult> failed = new CompletableFuture<>();
                    failed.completeExceptionally(result.error);
                    return failed;
                }
                return CompletableFuture.completedFuture(result);
            })
            // §3.2.3 — parse/refusal/empty escalation (validator-gated).
            .thenCompose(result -> {
                String text = result.text;
                boolean accepted = validator == null || validator.isValid(text);
                if (!accepted) {
                    int maxEscalations = Math.min(RetryPolicy.MAX_ESCALATIONS, chain.size() - 1);
                    int nextIdx = escalationIdx + 1;
                    if (escalationIdx < maxEscalations) {
                        LOGGER.warn("PARSE_ESCALATION kind={} from={} to={} reminder={}",
                                kind, modelId,
                                chain.get(Math.min(nextIdx, chain.size() - 1)),
                                stricterReminder != null);
                        return runEscalation(kind, spec, systemPrompt, userPrompt, images,
                                             validator, stricterReminder, cost, nextIdx);
                    }
                    LOGGER.warn("PARSE_GIVEUP kind={} model={} after={} escalations",
                            kind, modelId, maxEscalations);
                }

                if (cost != null) {
                    cost.record(kind, result.modelId,
                            result.inputTokens, result.outputTokens,
                            spec.inputPricePerMTok(), spec.outputPricePerMTok());
                }
                return CompletableFuture.completedFuture(text);
            });
    }

    // -------------------------------------------------------------------------
    // Internal: one HTTP call with failure-class routing (§3.2)
    // -------------------------------------------------------------------------

    /**
     * Performs a single logical Gemini call: one HTTP request retried up to
     * {@link RetryPolicy#TRANSIENT_BACKOFF_MS}{@code .length} times on any
     * {@link RetryPolicy.FailureClass#TRANSIENT} response (5xx / 408 / 503 / I/O)
     * with exponential backoff (0.5s → 2s → 8s).
     *
     * <p>Return value semantics:
     * <ul>
     *   <li>OK (2xx) → {@link CallResult} with text + token counts.</li>
     *   <li>RATE_LIMIT (429 or 403+quota body) → {@link CallResult#isRateLimited()}
     *       is true; {@link #call} decides whether to downshift.</li>
     *   <li>FATAL or exhausted transient → {@code CallResult.error} set;
     *       {@link #call} propagates.</li>
     * </ul>
     */
    private CompletableFuture<CallResult> sendOnceWithRetries(String modelId,
                                                              long timeoutMs,
                                                              JsonObject body,
                                                              int attempt) {
        String url = String.format(BASE_URL, modelId) + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle((response, ex) -> {
                // Transient I/O failure — classified as TRANSIENT.
                if (ex != null) {
                    if (attempt < RetryPolicy.TRANSIENT_BACKOFF_MS.length) {
                        LOGGER.warn("TRANSIENT_RETRY model={} cause=io msg={} attempt={}/{}",
                                modelId, ex.getMessage(), attempt + 1,
                                RetryPolicy.TRANSIENT_BACKOFF_MS.length);
                        return null;  // signal retry
                    }
                    return CallResult.failure(modelId,
                            new RuntimeException("Gemini I/O failure after "
                                    + RetryPolicy.TRANSIENT_BACKOFF_MS.length
                                    + " retries: " + ex.getMessage(), ex));
                }

                int status = response.statusCode();
                String bodyPreview = preview(response.body());
                RetryPolicy.FailureClass cls = RetryPolicy.classify(status, response.body());

                switch (cls) {
                    case OK:
                        return parseCallResult(modelId, response.body());

                    case TRANSIENT:
                        if (attempt < RetryPolicy.TRANSIENT_BACKOFF_MS.length) {
                            LOGGER.warn("TRANSIENT_RETRY model={} status={} body={} attempt={}/{}",
                                    modelId, status, bodyPreview,
                                    attempt + 1, RetryPolicy.TRANSIENT_BACKOFF_MS.length);
                            return null;
                        }
                        return CallResult.failure(modelId, new RuntimeException(
                                "Gemini transient " + status + " on " + modelId + " after "
                                + RetryPolicy.TRANSIENT_BACKOFF_MS.length + " retries: "
                                + bodyPreview));

                    case RATE_LIMIT:
                        LOGGER.warn("RATE_LIMIT model={} status={} body={}",
                                modelId, status, bodyPreview);
                        return CallResult.rateLimited(modelId, bodyPreview);

                    case FATAL:
                    default:
                        return CallResult.failure(modelId, new RuntimeException(
                                "Gemini " + status + " (model=" + modelId + "): "
                                + bodyPreview));
                }
            })
            .thenCompose(result -> {
                if (result != null) return CompletableFuture.completedFuture(result);
                long delay = RetryPolicy.TRANSIENT_BACKOFF_MS[attempt]
                        + (long) (RANDOM.nextDouble() * RetryPolicy.JITTER_MS);
                return CompletableFuture
                        .runAsync(() -> {},
                                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                        .thenCompose(v -> sendOnceWithRetries(modelId, timeoutMs, body, attempt + 1));
            });
    }

    private static CallResult parseCallResult(String modelId, String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                // Refusal / safety filter — no candidates returned. Give caller
                // an empty response so the validator chain can decide what next.
                return CallResult.success(modelId, "", extractTokens(root, "promptTokenCount"),
                        extractTokens(root, "candidatesTokenCount"));
            }
            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject content = candidate.getAsJsonObject("content");
            if (content == null) {
                return CallResult.success(modelId, "",
                        extractTokens(root, "promptTokenCount"),
                        extractTokens(root, "candidatesTokenCount"));
            }
            JsonArray parts = content.getAsJsonArray("parts");
            String text = "";
            if (parts != null && parts.size() > 0) {
                JsonElement first = parts.get(0).getAsJsonObject().get("text");
                if (first != null && !first.isJsonNull()) text = first.getAsString();
            }
            long inTok  = extractTokens(root, "promptTokenCount");
            long outTok = extractTokens(root, "candidatesTokenCount");
            return CallResult.success(modelId, text, inTok, outTok);
        } catch (JsonSyntaxException e) {
            return CallResult.failure(modelId,
                    new RuntimeException("Failed to parse Gemini response: " + preview(body), e));
        } catch (Exception e) {
            return CallResult.failure(modelId,
                    new RuntimeException("Unexpected error parsing Gemini response: " + e.getMessage(), e));
        }
    }

    private static long extractTokens(JsonObject root, String field) {
        try {
            JsonObject usage = root.getAsJsonObject("usageMetadata");
            if (usage == null) return 0L;
            JsonElement el = usage.get(field);
            return el == null || el.isJsonNull() ? 0L : el.getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Internal carrier for one HTTP-level Gemini call result. */
    private static final class CallResult {
        final String modelId;
        final String text;
        final long inputTokens;
        final long outputTokens;
        final boolean rateLimited;
        final Throwable error;

        private CallResult(String modelId, String text, long inTok, long outTok,
                           boolean rateLimited, Throwable error) {
            this.modelId = modelId;
            this.text = text;
            this.inputTokens = inTok;
            this.outputTokens = outTok;
            this.rateLimited = rateLimited;
            this.error = error;
        }

        static CallResult success(String modelId, String text, long inTok, long outTok) {
            return new CallResult(modelId, text, inTok, outTok, false, null);
        }

        static CallResult failure(String modelId, Throwable err) {
            return new CallResult(modelId, null, 0L, 0L, false, err);
        }

        static CallResult rateLimited(String modelId, String bodyPreview) {
            return new CallResult(modelId, bodyPreview, 0L, 0L, true, null);
        }

        boolean isRateLimited() { return rateLimited; }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<String> send(JsonObject body) {
        return sendWithFallback(body, 0, 0);
    }

    /**
     * Sends the request to {@code MODELS[modelIndex]}, retrying on 5xx with
     * exponential backoff before falling through to the next model.
     *
     * @param modelIndex   index into {@link #MODELS}
     * @param retryAttempt 0-based retry count for the current model
     */
    private CompletableFuture<String> sendWithFallback(JsonObject body, int modelIndex, int retryAttempt) {
        String model = MODELS.get(modelIndex);
        String url = String.format(BASE_URL, model) + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                int status = response.statusCode();

                if (status >= 500) {
                    // Retry the same model with exponential backoff + jitter before falling back.
                    if (retryAttempt < MAX_RETRIES_PER_MODEL) {
                        long delayMs = (BASE_RETRY_MS << retryAttempt)
                                + (long) (RANDOM.nextDouble() * JITTER_MS);
                        LOGGER.warn("Gemini '{}' returned {} (attempt {}/{}); retrying in {}ms.",
                                model, status, retryAttempt + 1, MAX_RETRIES_PER_MODEL + 1, delayMs);
                        return CompletableFuture
                                .runAsync(() -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                                .thenCompose(v -> sendWithFallback(body, modelIndex, retryAttempt + 1));
                    }
                    // Same model exhausted — try the next one (also from attempt 0).
                    if (modelIndex + 1 < MODELS.size()) {
                        LOGGER.warn("Gemini '{}' failed after {} attempts; falling back to '{}'.",
                                model, MAX_RETRIES_PER_MODEL + 1, MODELS.get(modelIndex + 1));
                        return sendWithFallback(body, modelIndex + 1, 0);
                    }
                    // All models exhausted.
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException(
                            "Gemini API error " + status + " on all models: " + preview(response.body())));
                    return failed;
                }

                if (status < 200 || status >= 300) {
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException(
                            "Gemini API error " + status + " (model=" + model + "): "
                            + preview(response.body())));
                    return failed;
                }

                return CompletableFuture.completedFuture(extractText(response.body()));
            });
    }

    /**
     * Pinned variant of {@link #sendWithFallback}: uses a single explicit model
     * and never falls through to any other entry. Retries on 5xx with the same
     * exponential-backoff schedule.
     */
    private CompletableFuture<String> sendPinned(JsonObject body, String modelId, int retryAttempt) {
        String url = String.format(BASE_URL, modelId) + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                int status = response.statusCode();

                if (status >= 500) {
                    if (retryAttempt < MAX_RETRIES_PER_MODEL) {
                        long delayMs = (BASE_RETRY_MS << retryAttempt)
                                + (long) (RANDOM.nextDouble() * JITTER_MS);
                        LOGGER.warn("Gemini pinned '{}' returned {} (attempt {}/{}); retrying in {}ms.",
                                modelId, status, retryAttempt + 1, MAX_RETRIES_PER_MODEL + 1, delayMs);
                        return CompletableFuture
                                .runAsync(() -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                                .thenCompose(v -> sendPinned(body, modelId, retryAttempt + 1));
                    }
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException(
                            "Gemini pinned API error " + status + " (model=" + modelId + "): "
                            + preview(response.body())));
                    return failed;
                }

                if (status < 200 || status >= 300) {
                    CompletableFuture<String> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new RuntimeException(
                            "Gemini pinned API error " + status + " (model=" + modelId + "): "
                            + preview(response.body())));
                    return failed;
                }

                return CompletableFuture.completedFuture(extractText(response.body()));
            });
    }

    private static JsonObject buildMultiImageBody(String systemPrompt, String userPrompt,
                                                  List<ImagePart> images) {
        JsonObject body = new JsonObject();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sysInstr = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysPart = new JsonObject();
            sysPart.addProperty("text", systemPrompt);
            sysParts.add(sysPart);
            sysInstr.add("parts", sysParts);
            body.add("systemInstruction", sysInstr);
        }

        JsonArray parts = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userPrompt == null ? "" : userPrompt);
        parts.add(textPart);

        if (images != null) {
            for (ImagePart img : images) {
                if (img == null || img.bytes() == null) continue;
                JsonObject inlineData = new JsonObject();
                inlineData.addProperty("mimeType", img.mimeType());
                inlineData.addProperty("data", Base64.getEncoder().encodeToString(img.bytes()));
                JsonObject imagePart = new JsonObject();
                imagePart.add("inlineData", inlineData);
                parts.add(imagePart);
            }
        }

        JsonObject userContent = new JsonObject();
        userContent.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(userContent);
        body.add("contents", contents);

        return body;
    }

    private static JsonObject buildBody(String systemPrompt, String userPrompt,
                                        byte[] imageBytes, String mimeType) {
        JsonObject body = new JsonObject();

        // systemInstruction
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sysInstr = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysPart = new JsonObject();
            sysPart.addProperty("text", systemPrompt);
            sysParts.add(sysPart);
            sysInstr.add("parts", sysParts);
            body.add("systemInstruction", sysInstr);
        }

        // contents[0].parts
        JsonArray parts = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userPrompt);
        parts.add(textPart);

        if (imageBytes != null && mimeType != null) {
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mimeType", mimeType);
            inlineData.addProperty("data", Base64.getEncoder().encodeToString(imageBytes));
            JsonObject imagePart = new JsonObject();
            imagePart.add("inlineData", inlineData);
            parts.add(imagePart);
        }

        JsonObject userContent = new JsonObject();
        userContent.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(userContent);
        body.add("contents", contents);

        return body;
    }

    private static String extractText(String responseBody) {
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            JsonObject obj = root.getAsJsonObject();
            JsonArray candidates = obj.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                throw new RuntimeException("Gemini returned no candidates. Body: " + preview(responseBody));
            }
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) {
                throw new RuntimeException("Gemini candidate has no parts.");
            }
            return parts.get(0).getAsJsonObject().get("text").getAsString();
        } catch (JsonSyntaxException e) {
            throw new RuntimeException("Failed to parse Gemini response: " + preview(responseBody), e);
        }
    }

    private static String preview(String text) {
        if (text == null) return "<null>";
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }
}
