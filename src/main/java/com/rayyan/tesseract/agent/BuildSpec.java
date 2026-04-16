package com.rayyan.tesseract.agent;

import java.util.List;

/**
 * Output model for InterpretationAgent.
 *
 * Populated by parsing the raw JSON text returned by the first LLM call.
 * All fields are set once and treated as read-only by subsequent agents.
 *
 * Mirrors the InterpretationAgent output schema:
 * {
 *   "style": "gothic",
 *   "type": "gate",
 *   "width": 8,
 *   "height": 14,
 *   "depth": 4,
 *   "materials": ["stone_bricks", "cobblestone", "oak_fence"],
 *   "features": ["twin_towers", "central_arch", "crenellations", "torches"]
 * }
 */
public final class BuildSpec {
    String style;
    String type;
    int width;
    int height;
    int depth;
    List<String> materials;
    List<String> features;
    /** The raw JSON string returned by the LLM — kept for logging and debugging. */
    String rawJson;

    // Public accessors for cross-package access (e.g. PaletteUtils in blueprint package)
    public List<String> getMaterials() { return materials; }
    public List<String> getFeatures()  { return features; }
    public String getStyle()           { return style; }
    public String getType()            { return type; }
    public int getWidth()              { return width; }
    public int getHeight()             { return height; }
    public int getDepth()              { return depth; }
}
