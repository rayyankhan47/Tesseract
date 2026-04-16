package com.rayyan.tesseract.render;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.blueprint.BlueprintCompiler;
import com.rayyan.tesseract.blueprint.BlueprintParser;
import com.rayyan.tesseract.blueprint.CompiledBlueprint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IsoRendererTest {

    // =========================================================================
    // BlockColorPalette
    // =========================================================================

    @Test
    void paletteReturnsColorForKnownBlock() {
        Color c = BlockColorPalette.lookup("minecraft:stone");
        assertNotEquals(BlockColorPalette.UNKNOWN_COLOR, c);
    }

    @Test
    void paletteStripsNamespace() {
        Color withNs    = BlockColorPalette.lookup("minecraft:oak_planks");
        Color withoutNs = BlockColorPalette.lookup("oak_planks");
        assertEquals(withNs, withoutNs);
    }

    @Test
    void paletteStripsStateProperties() {
        Color plain  = BlockColorPalette.lookup("minecraft:oak_stairs");
        Color stated = BlockColorPalette.lookup("minecraft:oak_stairs[facing=east,half=bottom]");
        assertEquals(plain, stated);
    }

    @Test
    void paletteStripsStairsSuffix() {
        Color planks = BlockColorPalette.lookup("minecraft:oak_planks");
        Color stairs = BlockColorPalette.lookup("minecraft:oak_stairs");
        // Both should map to the same oak colour
        assertEquals(planks, stairs);
    }

    @Test
    void paletteReturnsMagentaForUnknown() {
        Color c = BlockColorPalette.lookup("minecraft:definitely_not_a_real_block_xyz");
        assertEquals(BlockColorPalette.UNKNOWN_COLOR, c);
    }

    // =========================================================================
    // IsoRenderer — basic output properties
    // =========================================================================

    @Test
    void renderProducesNonEmptyPng() throws Exception {
        List<BlockOp> ops = singleBlock(0, 0, 0, "minecraft:stone");
        byte[] png = IsoRenderer.renderPng(ops, bounds(4, 4, 4), 8);
        assertTrue(png.length > 0, "PNG must not be empty");
        // Verify it is valid PNG
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "Must decode as a valid image");
    }

    @Test
    void renderImageIsWiderThanTall() throws Exception {
        // Two side-by-side views → wider than tall for a typical structure
        List<BlockOp> ops = singleBlock(0, 0, 0, "minecraft:stone");
        byte[] png = IsoRenderer.renderPng(ops, bounds(8, 4, 6), 8);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertTrue(img.getWidth() > img.getHeight(),
                "Composite (two views) should be wider than tall: "
                + img.getWidth() + " × " + img.getHeight());
    }

    @Test
    void renderHasNonBackgroundPixels() throws Exception {
        List<BlockOp> ops = singleBlock(2, 0, 2, "minecraft:stone_bricks");
        byte[] png = IsoRenderer.renderPng(ops, bounds(8, 4, 8), 12);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);

        Color bg = new Color(220, 224, 228); // BG_COLOR
        int bgRgb = bg.getRGB();
        boolean hasNonBg = false;
        outer:
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                if (img.getRGB(x, y) != bgRgb) { hasNonBg = true; break outer; }
            }
        }
        assertTrue(hasNonBg, "Rendered image should contain non-background pixels");
    }

    @Test
    void renderIsDeterministic() throws Exception {
        List<BlockOp> ops = multiBlock();
        Blueprint.Bounds b = bounds(8, 6, 8);
        byte[] a = IsoRenderer.renderPng(ops, b, 10);
        byte[] c = IsoRenderer.renderPng(ops, b, 10);
        assertArrayEquals(a, c, "Rendering must be deterministic");
    }

    @Test
    void renderCabinBlueprint() throws Exception {
        Blueprint bp = BlueprintParser.parseStrict(loadResource("blueprints/cabin.json"));
        CompiledBlueprint cb = BlueprintCompiler.compile(bp);
        byte[] png = IsoRenderer.renderPng(cb.ops(), bp.bounds, 10);
        assertTrue(png.length > 1024, "Cabin render should produce a substantial PNG");
    }

    @Test
    void renderWatchtowerBlueprint() throws Exception {
        Blueprint bp = BlueprintParser.parseStrict(loadResource("blueprints/watchtower.json"));
        CompiledBlueprint cb = BlueprintCompiler.compile(bp);
        byte[] png = IsoRenderer.renderPng(cb.ops(), bp.bounds, 10);
        assertTrue(png.length > 1024, "Watchtower render should produce a substantial PNG");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<BlockOp> singleBlock(int x, int y, int z, String block) {
        BlockOp op = new BlockOp();
        op.x = x; op.y = y; op.z = z; op.block = block;
        return List.of(op);
    }

    private static List<BlockOp> multiBlock() {
        List<BlockOp> ops = new ArrayList<>();
        String[] blocks = {"minecraft:stone","minecraft:oak_planks","minecraft:cobblestone"};
        int i = 0;
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                BlockOp op = new BlockOp();
                op.x = x; op.y = 0; op.z = z; op.block = blocks[i++ % blocks.length];
                ops.add(op);
            }
        }
        return ops;
    }

    private static Blueprint.Bounds bounds(int sx, int sy, int sz) {
        return new Blueprint.Bounds(sx, sy, sz);
    }

    private static String loadResource(String path) throws Exception {
        try (InputStream is = IsoRendererTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "test resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
