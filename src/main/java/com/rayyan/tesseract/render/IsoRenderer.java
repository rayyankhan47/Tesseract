package com.rayyan.tesseract.render;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.blueprint.Blueprint;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * Pure Java 2D isometric renderer for compiled Blueprint block ops.
 *
 * <p>Produces a composite PNG with two views (front and back) side-by-side,
 * suitable for multimodal Gemini Vision critique calls.
 *
 * <p>Projection convention (dimetric isometric looking from north-east):
 * <pre>
 *   screenX = (blockX - blockZ) * ppb
 *   screenY = (blockX + blockZ) * ppb / 2 - blockY * ppb
 * </pre>
 * Three faces per block are painted back-to-front: top (brightness 1.0),
 * left/south (0.82), right/east (0.66).
 */
public final class IsoRenderer {

    // Per-face brightness multipliers
    private static final float TOP_BRIGHT   = 1.00f;
    private static final float LEFT_BRIGHT  = 0.82f;
    private static final float RIGHT_BRIGHT = 0.66f;

    // Stitching gap between the two views
    private static final int STITCH_GAP = 16;
    // Background colour
    private static final Color BG_COLOR = new Color(220, 224, 228);
    // Outline darkening delta
    private static final int OUTLINE_DELTA = 20;

    private IsoRenderer() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Renders a two-view composite (front + back) PNG from the given ops.
     *
     * @param ops    compiled block ops in blueprint-local coordinates
     * @param bounds blueprint bounds (used for image sizing)
     * @param ppb    pixels per block (8–24 is a good range; 12 is the default for vision critique)
     * @return PNG byte array, ready to pass to {@code GeminiClient.complete(..., imageBytes)}
     */
    public static byte[] renderPng(List<BlockOp> ops, Blueprint.Bounds bounds, int ppb) {
        BufferedImage front = renderView(ops, bounds, ppb, false);
        BufferedImage back  = renderView(ops, bounds, ppb, true);
        BufferedImage composite = stitch(front, back, ppb);
        return toPng(composite);
    }

    /**
     * Writes a debug copy of the rendered PNG to {@code run/tesseract_debug/}.
     * Silent no-op if the system property {@code tesseract.debug.renders} is unset
     * or the directory does not exist.
     *
     * @param png     PNG bytes from {@link #renderPng}
     * @param buildId any string identifier (e.g. player UUID)
     * @param iter    iteration index
     */
    public static void writeDebugCopy(byte[] png, String buildId, int iter) {
        if (System.getProperty("tesseract.debug.renders") == null) return;
        try {
            File dir = new File("run/tesseract_debug");
            if (!dir.exists() && !dir.mkdirs()) return;
            String safe = buildId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            File out = new File(dir, safe + "_iter" + iter + ".png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(png);
            }
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /**
     * Renders a single isometric view.
     *
     * @param swapXZ if true, the X and Z axes are swapped (gives a 90° rotated view)
     */
    static BufferedImage renderView(List<BlockOp> ops, Blueprint.Bounds bounds,
                                    int ppb, boolean swapXZ) {
        // Sort back-to-front: ascending (x + z - y) so closer blocks paint over farther ones
        Comparator<BlockOp> depth = Comparator.comparingInt(op ->
                (swapXZ ? op.z + op.x : op.x + op.z) - op.y);
        List<BlockOp> sorted = ops.stream().sorted(depth).toList();

        int sizeX = swapXZ ? bounds.sizeZ() : bounds.sizeX();
        int sizeZ = swapXZ ? bounds.sizeX() : bounds.sizeZ();
        int sizeY = bounds.sizeY();

        // Image dimensions
        int padding = ppb * 2;
        int imgW = (sizeX + sizeZ) * ppb + padding * 2;
        int imgH = (sizeY + (sizeX + sizeZ + 1) / 2) * ppb + padding * 2;

        // Screen offset so that block(0,0,0)'s back corner is at (dX, dY)
        int dX = sizeZ * ppb + padding;
        int dY = sizeY * ppb + padding;

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, imgW, imgH);

        for (BlockOp op : sorted) {
            if ("minecraft:air".equals(op.block) || "air".equals(op.block)) continue;
            Color base = BlockColorPalette.lookup(op.block);
            if (base == BlockColorPalette.UNKNOWN_COLOR && (op.block == null || op.block.isBlank())) continue;

            int bx = swapXZ ? op.z : op.x;
            int bz = swapXZ ? op.x : op.z;
            int by = op.y;

            // Screen position of the block's back-bottom corner (bx, by, bz) projected
            int ox = (bx - bz) * ppb + dX;
            int oy = (bx + bz) * ppb / 2 - by * ppb + dY;

            drawBlock(g, ox, oy, ppb, base);
        }

        g.dispose();
        return img;
    }

    /**
     * Draws a single isometric block at screen-origin {@code (ox, oy)}.
     *
     * The 8 projected corners (relative to ox, oy) are:
     * <pre>
     *  C000 = (0,        0)        C100 = (+ppb,  +ppb/2)
     *  C001 = (-ppb,  +ppb/2)      C101 = (0,     +ppb)
     *  C010 = (0,        -ppb)     C110 = (+ppb,  -ppb/2)
     *  C011 = (-ppb,  -ppb/2)      C111 = (0,     0)   ← same as C000 (isometric collapse)
     * </pre>
     * Three visible faces:
     * <pre>
     *  Top:   C010, C110, C111(=C000), C011
     *  Right: C110, C111, C101, C100
     *  Left:  C011, C111, C101, C001
     * </pre>
     */
    private static void drawBlock(Graphics2D g, int ox, int oy, int ppb, Color base) {
        int h  = ppb;
        int w  = ppb;
        int hw = ppb / 2;

        // Pre-compute the 8 corners
        int[] cx = { ox,      ox+w,     ox-w,     ox,       ox,       ox+w,     ox-w,     ox      };
        int[] cy = { oy,      oy+hw,    oy+hw,    oy+h,     oy-h,     oy-hw,    oy-hw,    oy      };
        //          C000     C100      C001      C101      C010      C110      C011      C111(=C000)

        // Top face: C010(4), C110(5), C000/C111(7≡0), C011(6)
        fillFace(g, new int[]{cx[4],cx[5],cx[0],cx[6]}, new int[]{cy[4],cy[5],cy[0],cy[6]},
                base, TOP_BRIGHT);

        // Right face (east, x=+1): C110(5), C000(0/7), C101(3), C100(1)
        fillFace(g, new int[]{cx[5],cx[0],cx[3],cx[1]}, new int[]{cy[5],cy[0],cy[3],cy[1]},
                base, RIGHT_BRIGHT);

        // Left face (south, z=+1): C011(6), C000(0/7), C101(3), C001(2)
        fillFace(g, new int[]{cx[6],cx[0],cx[3],cx[2]}, new int[]{cy[6],cy[0],cy[3],cy[2]},
                base, LEFT_BRIGHT);
    }

    private static void fillFace(Graphics2D g, int[] xs, int[] ys, Color base, float bright) {
        Color fill = shade(base, bright);
        g.setColor(fill);
        g.fillPolygon(xs, ys, 4);
        // 1-pixel outline (slightly darker than fill)
        g.setColor(darken(fill, OUTLINE_DELTA));
        g.drawPolygon(xs, ys, 4);
    }

    // =========================================================================
    // Stitching
    // =========================================================================

    /** Composites two views side-by-side with a gap and "front" / "back" labels. */
    static BufferedImage stitch(BufferedImage front, BufferedImage back, int ppb) {
        int totalW = front.getWidth() + STITCH_GAP + back.getWidth();
        int totalH = Math.max(front.getHeight(), back.getHeight());

        BufferedImage out = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(180, 184, 188)); // slightly darker separator
        g.fillRect(0, 0, totalW, totalH);

        g.drawImage(front, 0, 0, null);
        g.drawImage(back,  front.getWidth() + STITCH_GAP, 0, null);

        // Labels
        int fontSize = Math.max(8, ppb);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        g.setColor(new Color(40, 40, 40));
        g.drawString("front", 4, fontSize + 2);
        g.drawString("back",  front.getWidth() + STITCH_GAP + 4, fontSize + 2);

        g.dispose();
        return out;
    }

    // =========================================================================
    // Color helpers
    // =========================================================================

    private static Color shade(Color c, float brightness) {
        return new Color(
                clamp(Math.round(c.getRed()   * brightness)),
                clamp(Math.round(c.getGreen() * brightness)),
                clamp(Math.round(c.getBlue()  * brightness)));
    }

    private static Color darken(Color c, int delta) {
        return new Color(
                clamp(c.getRed()   - delta),
                clamp(c.getGreen() - delta),
                clamp(c.getBlue()  - delta));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int clamp(long v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    // =========================================================================
    // PNG encoding
    // =========================================================================

    private static byte[] toPng(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("IsoRenderer: failed to encode PNG", e);
        }
    }
}
