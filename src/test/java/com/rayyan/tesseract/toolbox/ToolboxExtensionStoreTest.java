package com.rayyan.tesseract.toolbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolboxExtensionStoreTest {

    @Test
    void roundTripsJsonl(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tools.jsonl");
        ToolboxExtensionStore store = new ToolboxExtensionStore(file).load();
        assertTrue(store.all().isEmpty());

        ToolboxExtension a = new ToolboxExtension(
                "arched_window", "arched_window(x, y, z, w, h)",
                "def arched_window(x, y, z, w, h):\n    return box(x, y, z, x+w, y+h, z, 'stone')",
                "A rectangular window with a pointed-arch top.",
                List.of("wall = arched_window(0, 0, 0, 4, 6)"),
                "build-001", "gothic cathedral", 0.92, 1_700_000_000_000L);
        store.upsert(a);

        ToolboxExtension b = new ToolboxExtension(
                "spiral_staircase", "spiral_staircase(cx, cz, y0, turns, radius)",
                "def spiral_staircase(cx, cz, y0, turns, radius):\n    pass",
                "Helical staircase around a cylindrical core.",
                List.of("stairs = spiral_staircase(8, 8, 0, 2, 4)"),
                "build-002", "ancient tower", 0.85, 1_700_000_001_000L);
        store.upsert(b);

        ToolboxExtensionStore reopened = new ToolboxExtensionStore(file).load();
        List<ToolboxExtension> loaded = reopened.all();
        assertEquals(2, loaded.size());
        assertEquals("arched_window", loaded.get(0).name());
        assertEquals("spiral_staircase", loaded.get(1).name());
        assertEquals(0.92, loaded.get(0).score());
        assertEquals(List.of("wall = arched_window(0, 0, 0, 4, 6)"), loaded.get(0).usageExamples());
    }

    @Test
    void upsertReplacesExisting(@TempDir Path dir) {
        ToolboxExtensionStore store = new ToolboxExtensionStore(dir.resolve("x.jsonl"));
        ToolboxExtension first = ext("foo", 0.81);
        ToolboxExtension second = ext("foo", 0.92);

        assertFalse(store.upsert(first));
        assertTrue(store.upsert(second));
        assertEquals(1, store.all().size());
        assertEquals(0.92, store.all().get(0).score());
    }

    @Test
    void renderForPromptIncludesEachFunction(@TempDir Path dir) {
        ToolboxExtensionStore store = new ToolboxExtensionStore(dir.resolve("p.jsonl"));
        store.upsert(ext("oriel_window", 0.9));
        String rendered = store.renderForPrompt();
        assertTrue(rendered.contains("oriel_window"));
    }

    @Test
    void malformedLinesAreSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.jsonl");
        java.nio.file.Files.writeString(file, "{not json}\n{\"name\":\"ok\",\"signature\":\"ok()\",\"source\":\"def ok():\\n  pass\",\"score\":0.9}\n");
        ToolboxExtensionStore store = new ToolboxExtensionStore(file).load();
        assertEquals(1, store.all().size());
        assertEquals("ok", store.all().get(0).name());
    }

    private static ToolboxExtension ext(String name, double score) {
        return new ToolboxExtension(
                name, name + "()", "def " + name + "():\n    pass",
                name + " helper", List.of(name + "()"),
                "bid", "prompt", score, 0L);
    }
}
