package com.rayyan.tesseract.toolbox;

import com.rayyan.tesseract.agent.BlockOp;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.1.3 — one test per toolbox function covering the happy path plus
 * at least one degenerate-input branch. Tests are intentionally
 * coordinate-precise so regressions are obvious in CI output.
 */
class ToolboxTest {

    // ------ Fills ----------------------------------------------------------

    @Test
    void box_fillsInclusiveRange() {
        Set<BlockOp> b = Toolbox.box(0, 0, 0, 2, 2, 2, "stone");
        assertEquals(27, b.size());
        assertTrue(containsPos(b, 0, 0, 0));
        assertTrue(containsPos(b, 2, 2, 2));
        for (BlockOp op : b) assertEquals("stone", op.block);
    }

    @Test
    void box_normalizesReversedCorners() {
        Set<BlockOp> a = Toolbox.box(0, 0, 0, 2, 2, 2, "stone");
        Set<BlockOp> b = Toolbox.box(2, 2, 2, 0, 0, 0, "stone");
        assertEquals(a.size(), b.size());
    }

    @Test
    void cylinder_isAroundCenter() {
        Set<BlockOp> c = Toolbox.cylinder(5.0, 5.0, 0, 4, 2.0, "brick");
        // Five layers, each disc is >=9 voxels for r=2
        assertTrue(c.size() >= 5 * 9);
        assertTrue(containsPos(c, 5, 0, 5));
        assertTrue(containsPos(c, 5, 4, 5));
        // Far outside radius
        assertFalse(containsPos(c, 10, 0, 5));
    }

    @Test
    void pyramid_stepsDownByOnePerLayer() {
        Set<BlockOp> p = Toolbox.pyramid(0, 0, 0, 4, 3, "sandstone");
        // layer radii 3,2,1,0 → 7² + 5² + 3² + 1² = 49+25+9+1 = 84
        assertEquals(84, p.size());
        assertTrue(containsPos(p, 0, 0, 0));
        assertTrue(containsPos(p, 3, 0, 0));
        assertTrue(containsPos(p, 0, 3, 0));
        assertFalse(containsPos(p, 3, 3, 0));
    }

    @Test
    void sphere_isSymmetricAroundCenter() {
        Set<BlockOp> s = Toolbox.sphere(0, 0, 0, 3.0, "stone");
        assertTrue(containsPos(s, 0, 0, 0));
        assertTrue(containsPos(s, 3, 0, 0));
        assertTrue(containsPos(s, 0, 3, 0));
        assertTrue(containsPos(s, 0, 0, 3));
        assertFalse(containsPos(s, 4, 4, 4));
    }

    // ------ Outlines -------------------------------------------------------

    @Test
    void walls_skipsInteriorAndTopBottom() {
        Set<BlockOp> w = Toolbox.walls(0, 0, 0, 4, 2, 4, "plank");
        // Perimeter 16 per Y, 3 Y layers = 48
        assertEquals(48, w.size());
        assertTrue(containsPos(w, 0, 1, 0));
        assertFalse(containsPos(w, 2, 1, 2));
    }

    @Test
    void frame_emitsTwelveEdges() {
        Set<BlockOp> f = Toolbox.frame(0, 0, 0, 4, 4, 4, "iron_block");
        // 12 edges × 5 voxels - 8 corner dupes handled by dedup.
        // Precise count: 12*5 - 8 corners counted 3 times each = 60 - 16 = 44
        assertEquals(44, f.size());
    }

    @Test
    void line_bresenham_producesContiguousPath() {
        Set<BlockOp> l = Toolbox.line(0, 0, 0, 5, 3, 2, "glass");
        // Dominant axis dx=5 so length = 6 voxels
        assertEquals(6, l.size());
        assertTrue(containsPos(l, 0, 0, 0));
        assertTrue(containsPos(l, 5, 3, 2));
    }

    // ------ Curves ---------------------------------------------------------

    @Test
    void arc_centersAroundCenter() {
        Set<BlockOp> a = Toolbox.arc(0, 0, 0, 4.0, 0, 180, 'Y', "quartz");
        assertFalse(a.isEmpty());
        // endpoints of a 0->180 arc in Y-plane: (4,0,0) and (-4,0,0)
        assertTrue(containsPos(a, 4, 0, 0));
        assertTrue(containsPos(a, -4, 0, 0));
    }

    // ------ Composition ----------------------------------------------------

    @Test
    void repeat_tilesAlongDelta() {
        Set<BlockOp> base = Toolbox.box(0, 0, 0, 0, 0, 0, "stone");
        Set<BlockOp> tiled = Toolbox.repeat(base, 2, 0, 0, 3);
        assertEquals(4, tiled.size());
        assertTrue(containsPos(tiled, 0, 0, 0));
        assertTrue(containsPos(tiled, 2, 0, 0));
        assertTrue(containsPos(tiled, 4, 0, 0));
        assertTrue(containsPos(tiled, 6, 0, 0));
    }

    @Test
    void mirror_reflectsAcrossPivot() {
        Set<BlockOp> base = Toolbox.box(1, 0, 0, 1, 0, 0, "stone");
        Set<BlockOp> mirrored = Toolbox.mirror(base, 'X', 5);
        // Original at x=1 plus mirror at x=9
        assertEquals(2, mirrored.size());
        assertTrue(containsPos(mirrored, 1, 0, 0));
        assertTrue(containsPos(mirrored, 9, 0, 0));
    }

    @Test
    void subtract_removesOverlappingPositions() {
        Set<BlockOp> a = Toolbox.box(0, 0, 0, 2, 0, 0, "stone");
        Set<BlockOp> b = Toolbox.box(1, 0, 0, 1, 0, 0, "stone");
        Set<BlockOp> r = Toolbox.subtract(a, b);
        assertEquals(2, r.size());
        assertTrue(containsPos(r, 0, 0, 0));
        assertFalse(containsPos(r, 1, 0, 0));
        assertTrue(containsPos(r, 2, 0, 0));
    }

    @Test
    void intersect_keepsSharedPositionsFromA() {
        Set<BlockOp> a = Toolbox.box(0, 0, 0, 3, 0, 0, "stone");
        Set<BlockOp> b = Toolbox.box(2, 0, 0, 5, 0, 0, "brick");
        Set<BlockOp> r = Toolbox.intersect(a, b);
        assertEquals(2, r.size());
        for (BlockOp op : r) assertEquals("stone", op.block);
    }

    // ------ Decoration -----------------------------------------------------

    @Test
    void crenellate_alternatesMerlonsAndCrenels() {
        Set<BlockOp> top = Toolbox.box(0, 4, 0, 7, 4, 0, "stone");
        Set<BlockOp> out = Toolbox.crenellate(top, 1, 0, "stone");
        // Every other voxel keeps + raises; the rest are dropped
        // We expect 4 kept + 4 raised = 8 total
        assertEquals(8, out.size());
        assertTrue(containsPos(out, 0, 4, 0));
        assertFalse(containsPos(out, 1, 4, 0));
        assertTrue(containsPos(out, 0, 5, 0));
    }

    @Test
    void scatter_isDeterministic() {
        Set<BlockOp> a = Toolbox.scatter(0, 0, 0, 5, 5, 5, 0.3, 1234L, "moss");
        Set<BlockOp> b = Toolbox.scatter(0, 0, 0, 5, 5, 5, 0.3, 1234L, "moss");
        assertEquals(a.size(), b.size());
        assertEquals(positions(a), positions(b));
    }

    @Test
    void scatter_rejectsNegativeDensity() {
        Set<BlockOp> s = Toolbox.scatter(0, 0, 0, 5, 5, 5, -0.5, 1L, "stone");
        assertTrue(s.isEmpty());
    }

    // ------ Helpers --------------------------------------------------------

    private static boolean containsPos(Set<BlockOp> s, int x, int y, int z) {
        for (BlockOp op : s) {
            if (op.x == x && op.y == y && op.z == z) return true;
        }
        return false;
    }

    private static Set<String> positions(Set<BlockOp> s) {
        Set<String> out = new HashSet<>();
        for (BlockOp op : s) out.add(op.x + "," + op.y + "," + op.z);
        return out;
    }
}
