package com.rayyan.tesseract.rag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads {@link CorpusEntry} rows from the packaged {@code architecture_corpus.jsonl}.
 * Called once on mod init; the result is shared with the embedding bootstrap.
 */
public final class CorpusLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.rag");
    private static final String CORPUS_RESOURCE = "/architecture_corpus.jsonl";

    private CorpusLoader() {}

    /**
     * Reads the corpus from the mod's classpath resources and returns an
     * immutable list of entries. Malformed lines are skipped with a warning.
     */
    public static List<CorpusEntry> loadDefault() {
        try (InputStream in = CorpusLoader.class.getResourceAsStream(CORPUS_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("CorpusLoader: {} not found on classpath — RAG disabled", CORPUS_RESOURCE);
                return Collections.emptyList();
            }
            return loadFrom(in);
        } catch (IOException e) {
            LOGGER.warn("CorpusLoader: I/O error reading {}: {}", CORPUS_RESOURCE, e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<CorpusEntry> loadFrom(InputStream in) throws IOException {
        List<CorpusEntry> entries = new ArrayList<>(200);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                try {
                    JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
                    CorpusEntry entry = CorpusEntry.fromJson(obj);
                    if (entry != null) entries.add(entry);
                } catch (JsonSyntaxException | IllegalStateException e) {
                    LOGGER.warn("CorpusLoader: skipping malformed line {} ({}): {}",
                            lineNo, e.getMessage(), trimmed.length() > 120
                                    ? trimmed.substring(0, 120) + "…" : trimmed);
                    skipped++;
                }
            }
            LOGGER.info("CorpusLoader: loaded {} entries ({} skipped)", entries.size(), skipped);
        }
        return List.copyOf(entries);
    }
}
