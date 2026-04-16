package com.rayyan.tesseract.blueprint;

/**
 * An opening (door, window, or gap) cut into a {@code walls} primitive.
 *
 * Coordinates are wall-relative:
 *   uOffset = blocks from the left edge of the named face (0-based)
 *   vOffset = blocks from the bottom of the wall (0 = ground level of the wall)
 */
public record Opening(
        String face,    // "north" | "south" | "east" | "west"
        int    uOffset,
        int    vOffset,
        int    width,
        int    height,
        String type     // "door" | "window" | "gap"
) {
    /** Faces the compiler understands. */
    public static final java.util.Set<String> VALID_FACES =
            java.util.Set.of("north", "south", "east", "west");

    /** Opening types the compiler understands. */
    public static final java.util.Set<String> VALID_TYPES =
            java.util.Set.of("door", "window", "gap");
}
