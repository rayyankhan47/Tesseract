package com.rayyan.tesseract.blueprint;

import com.rayyan.tesseract.agent.BlockOp;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BlueprintCompiler}.
 *
 * Tests cover individual primitives (bounds, block count, material assignment)
 * and full blueprints (canonical cabin, watchtower).
 */
class BlueprintCompilerTest {

    // =========================================================================
    // platform
    // =========================================================================

    @Test
    void platform_basicFill() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":4,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],
                   "material":"minecraft:stone"}
                ]}
                """);
        assertEquals(16, cb.ops().size(), "4×1×4 platform should be 16 blocks");
        cb.ops().forEach(op -> {
            assertEquals(0, op.y);
            assertEquals("minecraft:stone", op.block);
        });
    }

    @Test
    void platform_edgeMaterial() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":4,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],
                   "material":"minecraft:stone","edge_material":"minecraft:cobblestone"}
                ]}
                """);
        assertEquals(16, cb.ops().size());
        // Interior: only (1,0,1), (1,0,2), (2,0,1), (2,0,2) are non-edge for a 4×4
        Set<String> interior = cb.ops().stream()
                .filter(op -> op.x > 0 && op.x < 3 && op.z > 0 && op.z < 3)
                .map(op -> op.block)
                .collect(Collectors.toSet());
        assertEquals(Set.of("minecraft:stone"), interior);

        Set<String> edge = cb.ops().stream()
                .filter(op -> op.x == 0 || op.x == 3 || op.z == 0 || op.z == 3)
                .map(op -> op.block)
                .collect(Collectors.toSet());
        assertEquals(Set.of("minecraft:cobblestone"), edge);
    }

    @Test
    void platform_primitiveBoundsStored() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":8,"sizeY":4,"sizeZ":6},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[8,1,6],
                   "material":"minecraft:stone"}
                ]}
                """);
        PrimitiveBounds pb = cb.primitiveBounds().get("base");
        assertNotNull(pb);
        assertEquals(0, pb.originX()); assertEquals(0, pb.originY()); assertEquals(0, pb.originZ());
        assertEquals(8, pb.sizeX());   assertEquals(1, pb.sizeY());   assertEquals(6, pb.sizeZ());
    }

    // =========================================================================
    // walls
    // =========================================================================

    @Test
    void walls_hollowPerimeter() throws Exception {
        // 4×4 footprint, 3 tall → perimeter = 12 positions, 3 rows = 36 blocks
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":3,
                   "material":"minecraft:oak_planks"}
                ]}
                """);
        // 16 platform + 36 wall blocks
        // Perimeter of 4×4 = 4*4 - 2*2*2 = 12 per row → 3 rows = 36
        int wallBlocks = (int) cb.ops().stream()
                .filter(op -> op.y >= 1 && op.y <= 3).count();
        assertEquals(36, wallBlocks, "4×4 hollow walls 3 high = 12 perimeter × 3 rows");
    }

    @Test
    void walls_noInteriorBlocks() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":6,"sizeY":8,"sizeZ":6},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[6,1,6],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":4,
                   "material":"minecraft:stone_bricks"}
                ]}
                """);
        // No wall block should be at interior x,z positions (1<=x<=4, 1<=z<=4 at y >= 1)
        long interior = cb.ops().stream()
                .filter(op -> op.y >= 1
                        && op.x > 0 && op.x < 5
                        && op.z > 0 && op.z < 5)
                .count();
        assertEquals(0, interior, "No interior wall blocks expected");
    }

    @Test
    void walls_openingCutsDoor() throws Exception {
        // South wall door: 2 wide, 3 tall at u=1
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":6,"sizeY":8,"sizeZ":6},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[6,1,6],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":4,
                   "material":"minecraft:stone_bricks",
                   "openings":[
                     {"face":"south","u_offset":1,"v_offset":0,"width":2,"height":3,"type":"door"}
                   ]}
                ]}
                """);
        // The three positions (x=1,y=1,z=5),(x=2,y=1,z=5),(x=1,y=2,z=5),(x=2,y=2,z=5),(x=1,y=3,z=5),(x=2,y=3,z=5)
        // should NOT have wall blocks (note: wall starts at y=1 since base has height 1)
        Set<String> positions = cb.ops().stream()
                .filter(op -> op.z == 5 && op.y >= 1 && op.y <= 3 && op.x >= 1 && op.x <= 2)
                .map(op -> op.x + "," + op.y + "," + op.z)
                .collect(Collectors.toSet());
        assertEquals(0, positions.size(), "Door opening should create 6 absent blocks on south wall");
    }

    @Test
    void walls_cornerMaterial() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":2,
                   "material":"minecraft:oak_planks","corner_material":"minecraft:oak_log"}
                ]}
                """);
        // Corners at (0,y,0),(3,y,0),(0,y,3),(3,y,3) for y=1,2 should be oak_log
        List<BlockOp> corners = cb.ops().stream()
                .filter(op -> op.y >= 1
                        && (op.x == 0 || op.x == 3)
                        && (op.z == 0 || op.z == 3))
                .toList();
        assertEquals(8, corners.size()); // 4 corners × 2 y levels
        corners.forEach(op -> assertEquals("minecraft:oak_log", op.block));
    }

    // =========================================================================
    // column
    // =========================================================================

    @Test
    void column_verticalLine() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"col","type":"column","origin":[1,0,1],"height":5,
                   "material":"minecraft:stone_brick_wall"}
                ]}
                """);
        assertEquals(5, cb.ops().size());
        for (int dy = 0; dy < 5; dy++) {
            final int y = dy;
            assertTrue(cb.ops().stream().anyMatch(op -> op.x == 1 && op.y == y && op.z == 1));
        }
    }

    @Test
    void column_capAndBaseMaterial() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"col","type":"column","origin":[0,0,0],"height":3,
                   "material":"minecraft:stone","cap_material":"minecraft:chiseled_stone_bricks",
                   "base_material":"minecraft:mossy_cobblestone"}
                ]}
                """);
        assertEquals(3, cb.ops().size());
        Map<Integer, String> byY = cb.ops().stream()
                .collect(Collectors.toMap(op -> op.y, op -> op.block));
        assertEquals("minecraft:mossy_cobblestone",      byY.get(0));
        assertEquals("minecraft:stone",                  byY.get(1));
        assertEquals("minecraft:chiseled_stone_bricks",  byY.get(2));
    }

    // =========================================================================
    // flat_roof
    // =========================================================================

    @Test
    void flatRoof_fills() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[4,1,4],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":3,
                   "material":"minecraft:stone_bricks"},
                  {"id":"roof","type":"flat_roof","on":"walls",
                   "material":"minecraft:stone_bricks"}
                ]}
                """);
        // roof layer at y=4 (base y1 + walls height 3 = 4)
        long roofBlocks = cb.ops().stream().filter(op -> op.y == 4).count();
        assertEquals(16, roofBlocks, "4×4 flat roof = 16 blocks");
    }

    @Test
    void flatRoof_battlementsRaisePerimeter() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":6,"sizeY":10,"sizeZ":6},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[6,1,6],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":4,
                   "material":"minecraft:stone_bricks"},
                  {"id":"roof","type":"flat_roof","on":"walls",
                   "material":"minecraft:stone_bricks","battlements":true,
                   "battlement_material":"minecraft:stone_brick_wall"}
                ]}
                """);
        // Roof at y=5; battlement blocks at y=6 on perimeter
        long battlementBlocks = cb.ops().stream()
                .filter(op -> op.y == 6 && op.block.equals("minecraft:stone_brick_wall"))
                .count();
        assertTrue(battlementBlocks > 0, "Battlement blocks should be present at y=6");
    }

    // =========================================================================
    // gable_roof
    // =========================================================================

    @Test
    void gableRoof_hasStairsOnSlopes() throws Exception {
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":8,"sizeY":12,"sizeZ":6},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[8,1,6],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":4,
                   "material":"minecraft:oak_planks"},
                  {"id":"roof","type":"gable_roof","on":"walls",
                   "ridge_axis":"z","overhang":1,
                   "stairs_material":"minecraft:oak_stairs",
                   "slab_material":"minecraft:oak_slab",
                   "ridge_material":"minecraft:oak_log"}
                ]}
                """);
        long stairBlocks = cb.ops().stream()
                .filter(op -> op.block.startsWith("minecraft:oak_stairs["))
                .count();
        assertTrue(stairBlocks > 0, "Gable roof should contain stair blocks");
    }

    // =========================================================================
    // Reference resolution
    // =========================================================================

    @Test
    void walls_inheritPlatformFootprint() throws Exception {
        // Walls placed on a 6×1×8 platform should inherit 6×8 footprint
        CompiledBlueprint cb = compileJson("""
                {"name":"t","bounds":{"sizeX":6,"sizeY":12,"sizeZ":8},"primitives":[
                  {"id":"base","type":"platform","origin":[0,0,0],"size":[6,1,8],
                   "material":"minecraft:stone"},
                  {"id":"walls","type":"walls","on":"base","height":5,
                   "material":"minecraft:stone_bricks"}
                ]}
                """);
        PrimitiveBounds wb = cb.primitiveBounds().get("walls");
        assertNotNull(wb);
        assertEquals(6, wb.sizeX());
        assertEquals(8, wb.sizeZ());
        assertEquals(5, wb.sizeY());
        assertEquals(1, wb.originY()); // starts at top face of platform (y=1)
    }

    @Test
    void missingParentThrows() {
        // 'walls' references 'nonexistent' — should throw at compile time
        // (parser validates on-refs, but let's also test the compiler is robust)
        assertThrows(Exception.class, () -> compileJson("""
                {"name":"t","bounds":{"sizeX":4,"sizeY":8,"sizeZ":4},"primitives":[
                  {"id":"walls","type":"walls","on":"nonexistent","height":3,
                   "material":"minecraft:stone"}
                ]}
                """));
    }

    // =========================================================================
    // Determinism
    // =========================================================================

    @Test
    void compileIsDeterministic() throws Exception {
        String json = loadResource("blueprints/cabin.json");
        Blueprint bp = BlueprintParser.parse(json);
        CompiledBlueprint a = BlueprintCompiler.compile(bp);
        CompiledBlueprint b = BlueprintCompiler.compile(bp);

        assertEquals(a.ops().size(), b.ops().size());
        for (int i = 0; i < a.ops().size(); i++) {
            BlockOp oa = a.ops().get(i), ob = b.ops().get(i);
            assertEquals(oa.x, ob.x);
            assertEquals(oa.y, ob.y);
            assertEquals(oa.z, ob.z);
            assertEquals(oa.block, ob.block);
        }
    }

    // =========================================================================
    // Canonical blueprints
    // =========================================================================

    @Test
    void compileCabin() throws Exception {
        Blueprint bp = BlueprintParser.parseStrict(loadResource("blueprints/cabin.json"));
        CompiledBlueprint cb = BlueprintCompiler.compile(bp);

        assertFalse(cb.ops().isEmpty(), "Cabin should produce block ops");
        assertEquals(3, cb.primitiveBounds().size());
        // All ops within bounds
        cb.ops().forEach(op -> {
            assertTrue(op.x >= 0 && op.x < 12, "x in [0,12)");
            assertTrue(op.y >= 0 && op.y < 12, "y in [0,12)");
            assertTrue(op.z >= 0 && op.z < 10, "z in [0,10)");
        });
    }

    @Test
    void compileWatchtower() throws Exception {
        Blueprint bp = BlueprintParser.parseStrict(loadResource("blueprints/watchtower.json"));
        CompiledBlueprint cb = BlueprintCompiler.compile(bp);

        assertFalse(cb.ops().isEmpty(), "Watchtower should produce block ops");
        assertEquals(7, cb.primitiveBounds().size());
        cb.ops().forEach(op -> {
            assertTrue(op.x >= 0 && op.x < 8, "x in [0,8)");
            assertTrue(op.y >= 0 && op.y < 16, "y in [0,16)");
            assertTrue(op.z >= 0 && op.z < 8, "z in [0,8)");
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static CompiledBlueprint compileJson(String json) throws Exception {
        Blueprint bp = BlueprintParser.parse(json);
        return BlueprintCompiler.compile(bp);
    }

    private static String loadResource(String path) throws Exception {
        try (InputStream is = BlueprintCompilerTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(is, "test resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
