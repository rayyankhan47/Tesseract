package com.rayyan.tesseract.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

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
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String apiKey;

    public GeminiClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set.");
        }
        this.apiKey = apiKey;
    }

    /** Reads GEMINI_API_KEY from the environment. Throws if missing. */
    public static GeminiClient fromEnv() {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY environment variable is not set. " +
                "Export it before starting the server: export GEMINI_API_KEY=your_key_here");
        }
        return new GeminiClient(key);
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
        String url = API_URL + "?key=" + apiKey;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException(
                        "Gemini API error " + response.statusCode() + ": " + preview(response.body()));
                }
                return extractText(response.body());
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
