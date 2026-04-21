package com.rayyan.tesseract.toolbox;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL-backed registry of promoted toolbox extensions (§6.3.2 / §6.3.3).
 *
 * <p>File path: {@code run/tesseract_cache/toolbox_extensions.jsonl}.
 * The plan names {@code resources/} as the destination, but that dir
 * ships inside the mod jar and isn't writable at runtime; the
 * convention used by every other runtime cache in this codebase
 * (Imagen concepts, RAG embeddings, debug renders) is the
 * {@code run/tesseract_cache/} directory next to the server's data.
 *
 * <p>One line per extension. Re-promoting an existing name <em>replaces</em>
 * the previous entry rather than appending, so the store stays flat and
 * deterministic. Atomic writes (write-then-rename) guarantee partial
 * updates don't corrupt the file.
 */
public final class ToolboxExtensionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.toolbox");
    private static final Gson GSON = new Gson();
    private static final String CACHE_FILENAME = "toolbox_extensions.jsonl";

    private final Path file;
    private final List<ToolboxExtension> extensions = new ArrayList<>();

    public ToolboxExtensionStore(Path file) {
        this.file = file;
    }

    public static ToolboxExtensionStore atDefaultPath() {
        return new ToolboxExtensionStore(defaultPath());
    }

    /**
     * Re-reads the on-disk file. Missing file → empty list (fresh install).
     * Malformed lines are logged and skipped so a single bad entry doesn't
     * take out the whole library.
     */
    public synchronized ToolboxExtensionStore load() {
        extensions.clear();
        if (!Files.exists(file)) return this;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    extensions.add(deserialise(JsonParser.parseString(line).getAsJsonObject()));
                } catch (Exception e) {
                    LOGGER.warn("ToolboxExtensionStore: skipping malformed line {} ({})", i + 1, e.getMessage());
                }
            }
            LOGGER.info("ToolboxExtensionStore: loaded {} extension(s) from {}", extensions.size(), file);
        } catch (IOException e) {
            LOGGER.warn("ToolboxExtensionStore: failed to read {} ({})", file, e.getMessage());
        }
        return this;
    }

    public synchronized List<ToolboxExtension> all() {
        return List.copyOf(extensions);
    }

    /**
     * Inserts or replaces an extension by {@link ToolboxExtension#name()}.
     * Returns {@code true} if the caller overwrote an existing entry.
     */
    public synchronized boolean upsert(ToolboxExtension ext) {
        boolean existed = false;
        for (int i = 0; i < extensions.size(); i++) {
            if (extensions.get(i).name().equals(ext.name())) {
                extensions.set(i, ext);
                existed = true;
                break;
            }
        }
        if (!existed) extensions.add(ext);
        try {
            persist();
        } catch (IOException e) {
            LOGGER.warn("ToolboxExtensionStore: failed to persist to {} ({})", file, e.getMessage());
        }
        return existed;
    }

    /**
     * Writes the in-memory list to disk atomically (tmp-file + move).
     * Package-private so tests can poke it directly.
     */
    synchronized void persist() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (ToolboxExtension ext : extensions) {
                writer.write(GSON.toJson(serialise(ext)));
                writer.write("\n");
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Builds a prompt snippet listing every promoted function with its
     * signature, description, and examples — consumed verbatim by L4
     * as "community-contributed toolbox".
     */
    public synchronized String renderForPrompt() {
        if (extensions.isEmpty()) return "(no community-contributed tools yet)";
        StringBuilder sb = new StringBuilder();
        for (ToolboxExtension ext : extensions) {
            sb.append("### ").append(ext.signature()).append("\n");
            if (ext.description() != null && !ext.description().isBlank()) {
                sb.append(ext.description()).append("\n");
            }
            for (String ex : ext.usageExamples()) {
                sb.append("    ").append(ex).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Serialisation — tiny hand-written bridge so we don't depend on Gson's
    // record support (flaky across gson versions).
    // -------------------------------------------------------------------------

    private static Map<String, Object> serialise(ToolboxExtension ext) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", ext.name());
        m.put("signature", ext.signature());
        m.put("source", ext.source());
        m.put("description", ext.description());
        m.put("usage_examples", ext.usageExamples());
        m.put("build_id", ext.buildId());
        m.put("original_prompt", ext.originalPrompt());
        m.put("score", ext.score());
        m.put("promoted_at_ms", ext.promotedAtMs());
        return m;
    }

    private static ToolboxExtension deserialise(JsonObject obj) {
        List<String> usage = new ArrayList<>();
        JsonElement usageEl = obj.get("usage_examples");
        if (usageEl != null && usageEl.isJsonArray()) {
            for (JsonElement e : usageEl.getAsJsonArray()) {
                if (e != null && e.isJsonPrimitive()) usage.add(e.getAsString());
            }
        }
        return new ToolboxExtension(
                stringOr(obj, "name", ""),
                stringOr(obj, "signature", ""),
                stringOr(obj, "source", ""),
                stringOr(obj, "description", ""),
                usage,
                stringOr(obj, "build_id", ""),
                stringOr(obj, "original_prompt", ""),
                obj.has("score") ? obj.get("score").getAsDouble() : 0.0,
                obj.has("promoted_at_ms") ? obj.get("promoted_at_ms").getAsLong() : 0L);
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    static Path defaultPath() {
        Path cwd = Path.of("").toAbsolutePath();
        Path base = cwd.getFileName() != null && cwd.getFileName().toString().equals("run")
                ? cwd
                : cwd.resolve("run");
        return base.resolve("tesseract_cache").resolve(CACHE_FILENAME);
    }

    // Kept for test convenience; the live pipeline goes through load/upsert.
    void setExtensionsForTesting(List<ToolboxExtension> list) {
        extensions.clear();
        extensions.addAll(list);
    }
}
