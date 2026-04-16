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
import java.util.concurrent.CompletableFuture;

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
        "gemini-2.0-flash-lite-preview",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    );
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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<String> send(JsonObject body) {
        return sendWithFallback(body, 0);
    }

    private CompletableFuture<String> sendWithFallback(JsonObject body, int modelIndex) {
        String model = MODELS.get(modelIndex);
        String url = String.format(BASE_URL, model) + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                int status = response.statusCode();
                if (status >= 500 && modelIndex + 1 < MODELS.size()) {
                    LOGGER.warn("Gemini model '{}' returned {}; falling back to '{}'.",
                            model, status, MODELS.get(modelIndex + 1));
                    return sendWithFallback(body, modelIndex + 1);
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
