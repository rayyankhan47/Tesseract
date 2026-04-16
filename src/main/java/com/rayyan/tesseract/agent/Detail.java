package com.rayyan.tesseract.agent;

/**
 * A single decoration item emitted by {@link DetailAgent}.
 *
 * <p>Supported types:
 * <ul>
 *   <li>{@code torch}      — wall-mounted or standing light; {@code block} defaults to
 *                            {@code minecraft:torch}, {@code face} may be
 *                            {@code north/south/east/west/floor}</li>
 *   <li>{@code decoration} — single-block placement (flower_pot, lantern, chest, etc.)</li>
 *   <li>{@code fill_line}  — run of identical blocks from {@code from} to {@code to}
 *                            (axis-aligned); expands into one BlockOp per block</li>
 *   <li>{@code sign}       — sign block; {@code text} is optional display text</li>
 * </ul>
 *
 * <p>This class is designed for Gson deserialisation from the DetailAgent JSON response.
 * Fields are intentionally package-private to keep the surface area small; callers use
 * {@link DetailAgent} which is in the same package.
 */
public final class Detail {

    /** One of: {@code torch}, {@code decoration}, {@code fill_line}, {@code sign}. */
    String type;

    /** Blueprint-local {@code [x, y, z]} for single-block details; start point for fill_line. */
    int[] pos;

    /** Blueprint-local {@code [x, y, z]} end point for {@code fill_line}. */
    int[] to;

    /** Fully-qualified block id, e.g. {@code minecraft:torch}. */
    String block;

    /**
     * Wall face for torch/sign placement: {@code north}, {@code south}, {@code east},
     * {@code west}, or {@code floor} (for standing). Nullable; defaults to {@code floor}.
     */
    String face;

    /** Optional display text for {@code sign} type. */
    String text;

    /** Returns {@code true} if this detail type is allowed to overlap structural ops. */
    boolean isOverlapAllowed() {
        return "torch".equals(type) || "lantern".equals(type) || "sign".equals(type);
    }
}
