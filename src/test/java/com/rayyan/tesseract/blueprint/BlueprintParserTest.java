package com.rayyan.tesseract.blueprint;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BlueprintParser}.
 *
 * Tests round-trip (parse → check fields) for both canonical examples
 * stored in src/test/resources/blueprints/.
 */
class BlueprintParserTest {

    // -------------------------------------------------------------------------
    // Canonical fixture tests
    // -------------------------------------------------------------------------

    @Test
    void parseCabin() throws Exception {
        Blueprint bp = parseResource("blueprints/cabin.json");

        assertEquals("cozy_oak_cabin", bp.name);
        assertEquals(12, bp.bounds.sizeX());
        assertEquals(12, bp.bounds.sizeY());
        assertEquals(10, bp.bounds.sizeZ());
        assertEquals(3, bp.primitives.size());

        Primitive foundation = bp.primitives.get(0);
        assertEquals("foundation", foundation.id);
        assertEquals("platform",   foundation.type);
        assertNull(foundation.on, "first primitive must have no 'on'");
        assertEquals("minecraft:stone_bricks", foundation.requireString("material"));
        assertEquals("minecraft:cobblestone",  foundation.getString("edge_material", null));

        int[] origin = foundation.requireIntArray3("origin");
        assertArrayEquals(new int[]{0, 0, 0}, origin);
        int[] size = foundation.requireIntArray3("size");
        assertArrayEquals(new int[]{12, 1, 10}, size);

        Primitive walls = bp.primitives.get(1);
        assertEquals("walls",       walls.id);
        assertEquals("walls",       walls.type);
        assertEquals("foundation",  walls.on);
        assertEquals(6, walls.requireInt("height"));
        assertEquals(3, walls.getOpenings().size());

        Primitive roof = bp.primitives.get(2);
        assertEquals("roof",         roof.id);
        assertEquals("gable_roof",   roof.type);
        assertEquals("walls",        roof.on);
        assertEquals("z",            roof.requireString("ridge_axis"));
        assertEquals(1,              roof.requireInt("overhang"));
        assertEquals("minecraft:oak_stairs", roof.requireString("stairs_material"));
    }

    @Test
    void parseWatchtower() throws Exception {
        Blueprint bp = parseResource("blueprints/watchtower.json");

        assertEquals("stone_watchtower", bp.name);
        assertEquals(8,  bp.bounds.sizeX());
        assertEquals(16, bp.bounds.sizeY());
        assertEquals(8,  bp.bounds.sizeZ());
        assertEquals(7, bp.primitives.size());

        // Foundation
        Primitive foundation = bp.primitives.get(0);
        assertEquals("foundation", foundation.id);
        assertNull(foundation.on);

        // Walls
        Primitive walls = bp.primitives.get(1);
        assertEquals("walls",      walls.id);
        assertEquals("foundation", walls.on);
        assertEquals(12, walls.requireInt("height"));
        assertEquals(2, walls.getOpenings().size());

        // Four corner columns
        for (int i = 2; i <= 5; i++) {
            Primitive col = bp.primitives.get(i);
            assertEquals("column", col.type);
            assertNull(col.on); // columns have explicit origins, no parent ref
            assertEquals(12, col.requireInt("height"));
        }

        // Parapet (flat_roof with battlements)
        Primitive parapet = bp.primitives.get(6);
        assertEquals("parapet",    parapet.id);
        assertEquals("flat_roof",  parapet.type);
        assertEquals("walls",      parapet.on);
        assertTrue(parapet.getBoolean("battlements", false));
        assertEquals("minecraft:stone_brick_wall", parapet.getString("battlement_material", null));
    }

    // -------------------------------------------------------------------------
    // Validation tests
    // -------------------------------------------------------------------------

    @Test
    void rejectsMissingBounds() {
        String json = """
                {"name":"test","primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    @Test
    void rejectsDuplicateIds() {
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"},
                  {"id":"base","type":"platform","origin":[0,1,0],"size":[4,1,4],"material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    @Test
    void rejectsMissingOnReference() {
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"nonexistent","height":4,"material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    @Test
    void rejectsOnBeforeDeclaration() {
        // 'walls' references 'roof' which comes AFTER it — illegal
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"roof","height":4,"material":"minecraft:stone"},
                  {"id":"roof","type":"flat_roof","on":"walls","material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    @Test
    void rejectsFirstPrimitiveWithOn() {
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"walls","type":"walls","on":"nonexistent","height":4,"material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    @Test
    void skipsUnknownPrimitiveTypeSilently() throws Exception {
        // Unknown type should be skipped (non-strict), not throw
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"},
                  {"id":"futuristic","type":"future_type_v99","material":"minecraft:stone"}
                ]}
                """;
        Blueprint bp = BlueprintParser.parse(json);
        assertEquals(1, bp.primitives.size(), "unknown type should be silently skipped");
    }

    @Test
    void strictModeRejectsUnknownType() {
        String json = """
                {"name":"test","bounds":{"sizeX":8,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"},
                  {"id":"futuristic","type":"future_type_v99","material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parseStrict(json));
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() throws Exception {
        String json = "```json\n" + """
                {"name":"fenced","bounds":{"sizeX":4,"sizeY":4,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"}
                ]}
                """ + "```";
        Blueprint bp = BlueprintParser.parse(json);
        assertEquals("fenced", bp.name);
    }

    @Test
    void rejectsBoundsExceedingMax() {
        String json = """
                {"name":"huge","bounds":{"sizeX":256,"sizeY":8,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],"material":"minecraft:stone"}
                ]}
                """;
        assertThrows(BlueprintParseException.class, () -> BlueprintParser.parse(json));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static Blueprint parseResource(String resourcePath) throws Exception {
        try (InputStream is = BlueprintParserTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            assertNotNull(is, "test resource not found: " + resourcePath);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return BlueprintParser.parseStrict(json);
        }
    }
}
