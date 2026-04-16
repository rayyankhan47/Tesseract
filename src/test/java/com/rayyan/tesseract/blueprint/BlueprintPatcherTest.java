package com.rayyan.tesseract.blueprint;

import com.google.gson.JsonPrimitive;
import com.rayyan.tesseract.agent.Critique;
import com.rayyan.tesseract.agent.Patch;
import com.rayyan.tesseract.agent.VisualCriticAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlueprintPatcher} and {@link VisualCriticAgent#parse}.
 */
class BlueprintPatcherTest {

    // =========================================================================
    // Modify
    // =========================================================================

    @Test
    void modifySimpleField() throws Exception {
        Blueprint bp = cabin();
        Patch p = modify("walls", "height", new JsonPrimitive(8));
        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));

        // Height of walls should now be 8
        Primitive walls = primitive(patched, "walls");
        assertNotNull(walls);
        assertEquals(8, walls.getInt("height", -1));
    }

    @Test
    void modifyOpeningUOffset() throws Exception {
        Blueprint bp = cabin();
        Patch p = modify("walls", "openings[0].u_offset", new JsonPrimitive(2));
        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));

        Primitive walls = primitive(patched, "walls");
        assertNotNull(walls);
        var openings = walls.getOpenings();
        assertFalse(openings.isEmpty());
        assertEquals(2, openings.get(0).uOffset());
    }

    @Test
    void modifyUnknownIdReturnsOriginal() throws Exception {
        Blueprint bp = cabin();
        Patch p = modify("nonexistent", "height", new JsonPrimitive(10));
        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));
        // Should return original blueprint unchanged
        assertSame(bp, patched);
    }

    @Test
    void modifyRidgeAxis() throws Exception {
        Blueprint bp = cabin();
        Patch p = modify("roof", "ridge_axis", new JsonPrimitive("x"));
        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));
        Primitive roof = primitive(patched, "roof");
        assertNotNull(roof);
        assertEquals("x", roof.getString("ridge_axis", "z"));
    }

    // =========================================================================
    // Add
    // =========================================================================

    @Test
    void addNewPrimitive() throws Exception {
        Blueprint bp = cabin();
        int originalCount = bp.primitives.size();

        Patch p = new Patch();
        p.op = "add";
        com.google.gson.JsonObject primJson = new com.google.gson.JsonObject();
        primJson.addProperty("id", "new_col");
        primJson.addProperty("type", "column");
        primJson.addProperty("height", 4);
        primJson.addProperty("material", "minecraft:stone");
        com.google.gson.JsonArray origin = new com.google.gson.JsonArray();
        origin.add(2); origin.add(1); origin.add(2);
        primJson.add("origin", origin);
        p.primitive = primJson;

        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));
        assertEquals(originalCount + 1, patched.primitives.size());
        assertNotNull(primitive(patched, "new_col"));
    }

    // =========================================================================
    // Remove
    // =========================================================================

    @Test
    void removePrimitive() throws Exception {
        Blueprint bp = cabin();
        int originalCount = bp.primitives.size();

        Patch p = new Patch();
        p.op = "remove";
        p.id = "roof";

        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));
        assertEquals(originalCount - 1, patched.primitives.size());
        assertNull(primitive(patched, "roof"));
    }

    // =========================================================================
    // Replace
    // =========================================================================

    @Test
    void replacePrimitive() throws Exception {
        Blueprint bp = cabin();

        Patch p = new Patch();
        p.op = "replace";
        p.id = "roof";
        com.google.gson.JsonObject replacement = new com.google.gson.JsonObject();
        replacement.addProperty("id", "roof");
        replacement.addProperty("type", "flat_roof");
        replacement.addProperty("on", "walls");
        replacement.addProperty("material", "minecraft:stone_bricks");
        p.primitive = replacement;

        Blueprint patched = BlueprintPatcher.apply(bp, List.of(p));
        Primitive roof = primitive(patched, "roof");
        assertNotNull(roof);
        assertEquals("flat_roof", roof.type);
        assertEquals("minecraft:stone_bricks", roof.getString("material", null));
    }

    // =========================================================================
    // Invalid patch — returns original
    // =========================================================================

    @Test
    void invalidPatchedBlueprintReturnsOriginal() throws Exception {
        Blueprint bp = cabin();
        // Setting height to a non-integer string causes compiler RuntimeException → returns original
        Patch p = modify("walls", "height", new JsonPrimitive("not_a_number"));
        Blueprint result = BlueprintPatcher.apply(bp, List.of(p));
        assertSame(bp, result, "Should return original when patched blueprint fails to compile");
    }

    @Test
    void emptyPatchListReturnsOriginal() throws Exception {
        Blueprint bp = cabin();
        assertSame(bp, BlueprintPatcher.apply(bp, List.of()));
    }

    // =========================================================================
    // BlueprintPatcher.applyDotPath unit tests
    // =========================================================================

    @Test
    void dotPathSimpleField() {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        BlueprintPatcher.applyDotPath(obj, "height", new JsonPrimitive(7));
        assertEquals(7, obj.get("height").getAsInt());
    }

    @Test
    void dotPathArrayIndex() {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        com.google.gson.JsonObject elem = new com.google.gson.JsonObject();
        elem.addProperty("u_offset", 1);
        arr.add(elem);
        obj.add("openings", arr);

        BlueprintPatcher.applyDotPath(obj, "openings[0].u_offset", new JsonPrimitive(5));
        assertEquals(5, obj.getAsJsonArray("openings").get(0).getAsJsonObject()
                .get("u_offset").getAsInt());
    }

    // =========================================================================
    // VisualCriticAgent.parse
    // =========================================================================

    @Test
    void parseSatisfiedResponse() throws Exception {
        Blueprint bp = cabin();
        String json = """
                {"satisfied":true,"issues":[],"patch":[]}
                """;
        Critique c = VisualCriticAgent.parse(json, bp);
        assertTrue(c.satisfied());
        assertTrue(c.issues().isEmpty());
        assertTrue(c.patch().isEmpty());
    }

    @Test
    void parseDissatisfiedResponse() throws Exception {
        Blueprint bp = cabin();
        String json = """
                {
                  "satisfied": false,
                  "issues": ["Roof is too shallow"],
                  "patch": [
                    {"op":"modify","id":"roof","field":"overhang","value":2}
                  ]
                }
                """;
        Critique c = VisualCriticAgent.parse(json, bp);
        assertFalse(c.satisfied());
        assertEquals(1, c.issues().size());
        assertEquals("Roof is too shallow", c.issues().get(0));
        assertEquals(1, c.patch().size());
        assertEquals("modify", c.patch().get(0).op);
        assertEquals("roof", c.patch().get(0).id);
        assertEquals("overhang", c.patch().get(0).field);
        assertEquals(2, c.patch().get(0).value.getAsInt());
    }

    @Test
    void parseMalformedJsonReturnsSatisfied() throws Exception {
        Blueprint bp = cabin();
        Critique c = VisualCriticAgent.parse("not json at all {{", bp);
        assertTrue(c.satisfied());
    }

    @Test
    void parseDropsUnknownIdPatch() throws Exception {
        Blueprint bp = cabin();
        String json = """
                {
                  "satisfied": false,
                  "issues": ["Something is wrong"],
                  "patch": [
                    {"op":"modify","id":"nonexistent_prim","field":"height","value":10}
                  ]
                }
                """;
        Critique c = VisualCriticAgent.parse(json, bp);
        // The bad patch entry should be dropped
        assertTrue(c.patch().isEmpty());
    }

    @Test
    void parseStripsMarkdownFences() throws Exception {
        Blueprint bp = cabin();
        String json = """
                ```json
                {"satisfied":true,"issues":[],"patch":[]}
                ```
                """;
        Critique c = VisualCriticAgent.parse(json, bp);
        assertTrue(c.satisfied());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Blueprint cabin() throws Exception {
        try (var is = BlueprintPatcherTest.class.getClassLoader()
                .getResourceAsStream("blueprints/cabin.json")) {
            assertNotNull(is);
            String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return BlueprintParser.parseStrict(json);
        }
    }

    private static Primitive primitive(Blueprint bp, String id) {
        return bp.primitives.stream()
                .filter(p -> p.id.equals(id))
                .findFirst().orElse(null);
    }

    private static Patch modify(String id, String field, com.google.gson.JsonElement value) {
        Patch p = new Patch();
        p.op    = "modify";
        p.id    = id;
        p.field = field;
        p.value = value;
        return p;
    }
}
