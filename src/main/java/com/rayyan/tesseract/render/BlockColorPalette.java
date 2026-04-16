package com.rayyan.tesseract.render;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps Minecraft block IDs to flat RGB colours for the isometric renderer.
 *
 * <p>Lookup strips any {@code [state=...]} suffix and any shape variant suffix
 * ({@code _stairs}, {@code _slab}, {@code _wall}, etc.) before falling through to
 * the canonical base-block colour. Unknown blocks return {@link #UNKNOWN_COLOR}
 * (magenta) and are logged once.
 */
public final class BlockColorPalette {

    public static final Color UNKNOWN_COLOR = new Color(255, 0, 255);

    // Already-logged unknowns — avoids log spam on large builds
    private static final Set<String> LOGGED_UNKNOWNS = ConcurrentHashMap.newKeySet();

    private static final Map<String, Color> PALETTE = new HashMap<>(256);

    static {
        // ---- Stone family ------------------------------------------------
        put("stone",                    128, 128, 128);
        put("cobblestone",              112, 112, 112);
        put("stone_bricks",             118, 118, 118);
        put("mossy_stone_bricks",       100, 118, 100);
        put("mossy_cobblestone",        100, 112, 100);
        put("cracked_stone_bricks",     110, 110, 110);
        put("chiseled_stone_bricks",    122, 122, 122);
        put("andesite",                 136, 136, 136);
        put("polished_andesite",        148, 148, 148);
        put("granite",                  155, 114,  96);
        put("polished_granite",         165, 124, 106);
        put("diorite",                  196, 196, 196);
        put("polished_diorite",         208, 208, 208);
        put("deepslate",                 80,  80,  88);
        put("cobbled_deepslate",         76,  76,  84);
        put("polished_deepslate",        88,  88,  96);
        put("deepslate_bricks",          84,  84,  92);
        put("deepslate_tiles",           80,  80,  88);
        put("chiseled_deepslate",        82,  82,  90);
        put("blackstone",                36,  32,  40);
        put("polished_blackstone",       48,  44,  52);
        put("polished_blackstone_bricks",44,  40,  48);
        put("chiseled_polished_blackstone", 50, 46, 54);
        put("sandstone",                220, 200, 156);
        put("smooth_sandstone",         222, 204, 162);
        put("chiseled_sandstone",       218, 198, 152);
        put("red_sandstone",            182, 100,  44);
        put("smooth_red_sandstone",     186, 106,  50);
        put("bricks",                   176,  96,  72);
        put("mud_bricks",               148, 116,  80);
        put("basalt",                    92,  88,  96);
        put("smooth_basalt",             94,  90,  98);
        put("calcite",                  220, 220, 216);
        put("tuff",                     110, 110, 106);
        put("prismarine",                80, 160, 148);
        put("prismarine_bricks",         88, 168, 156);
        put("dark_prismarine",           64, 108, 100);
        put("sea_lantern",              192, 224, 216);

        // ---- Oak wood family --------------------------------------------
        Color oak    = new Color(162, 130,  78);
        Color oakLog = new Color(108,  86,  48);
        putAll(oak,    "oak_planks","oak_stairs","oak_slab","oak_fence",
                       "oak_door","oak_trapdoor","oak_fence_gate");
        putAll(oakLog, "oak_log","oak_wood","stripped_oak_log","stripped_oak_wood");

        // ---- Spruce -----------------------------------------------------
        Color spruce    = new Color(114,  84,  48);
        Color spruceLog = new Color( 68,  54,  36);
        putAll(spruce,    "spruce_planks","spruce_stairs","spruce_slab","spruce_fence",
                          "spruce_door","spruce_trapdoor","spruce_fence_gate");
        putAll(spruceLog, "spruce_log","spruce_wood","stripped_spruce_log","stripped_spruce_wood");

        // ---- Birch ------------------------------------------------------
        Color birch    = new Color(192, 178, 118);
        Color birchLog = new Color(200, 196, 164);
        putAll(birch,    "birch_planks","birch_stairs","birch_slab","birch_fence",
                         "birch_door","birch_trapdoor","birch_fence_gate");
        putAll(birchLog, "birch_log","birch_wood","stripped_birch_log","stripped_birch_wood");

        // ---- Dark Oak ---------------------------------------------------
        Color darkOak    = new Color( 68,  44,  24);
        Color darkOakLog = new Color( 60,  40,  20);
        putAll(darkOak,    "dark_oak_planks","dark_oak_stairs","dark_oak_slab","dark_oak_fence",
                           "dark_oak_door","dark_oak_trapdoor","dark_oak_fence_gate");
        putAll(darkOakLog, "dark_oak_log","dark_oak_wood","stripped_dark_oak_log","stripped_dark_oak_wood");

        // ---- Acacia -----------------------------------------------------
        Color acacia    = new Color(178, 106,  64);
        Color acaciaLog = new Color( 96,  88,  72);
        putAll(acacia,    "acacia_planks","acacia_stairs","acacia_slab","acacia_fence",
                          "acacia_door","acacia_trapdoor","acacia_fence_gate");
        putAll(acaciaLog, "acacia_log","acacia_wood","stripped_acacia_log","stripped_acacia_wood");

        // ---- Jungle -----------------------------------------------------
        Color jungle    = new Color(160, 120,  80);
        Color jungleLog = new Color( 96,  72,  48);
        putAll(jungle,    "jungle_planks","jungle_stairs","jungle_slab","jungle_fence",
                          "jungle_door","jungle_trapdoor","jungle_fence_gate");
        putAll(jungleLog, "jungle_log","jungle_wood","stripped_jungle_log","stripped_jungle_wood");

        // ---- Mangrove ---------------------------------------------------
        Color mangrove    = new Color(114,  56,  60);
        Color mangroveLog = new Color( 78,  36,  40);
        putAll(mangrove,    "mangrove_planks","mangrove_stairs","mangrove_slab","mangrove_fence",
                            "mangrove_door","mangrove_trapdoor","mangrove_fence_gate");
        putAll(mangroveLog, "mangrove_log","mangrove_wood","stripped_mangrove_log","stripped_mangrove_wood");

        // ---- Natural terrain --------------------------------------------
        put("dirt",          136, 104,  72);
        put("coarse_dirt",   128,  96,  68);
        put("grass_block",    88, 148,  72);
        put("podzol",         88,  68,  44);
        put("mycelium",      120, 100, 112);
        put("sand",          224, 216, 164);
        put("red_sand",      196, 124,  52);
        put("gravel",        136, 128, 120);
        put("clay",          168, 176, 184);
        put("snow_block",    232, 240, 248);
        put("ice",           168, 196, 240);
        put("packed_ice",    152, 188, 236);
        put("blue_ice",      128, 172, 224);
        put("mud",           100,  88,  80);
        put("rooted_dirt",   128,  96,  80);

        // ---- Glass ------------------------------------------------------
        put("glass",         200, 224, 232);
        put("glass_pane",    200, 224, 232);
        put("tinted_glass",   80,  72,  96);

        // ---- Metal / mineral -------------------------------------------
        put("iron_block",    220, 220, 220);
        put("iron_bars",     176, 172, 172);
        put("gold_block",    248, 216,  56);
        put("diamond_block", 108, 228, 220);
        put("emerald_block",  36, 196, 112);
        put("lapis_block",    36,  80, 172);
        put("redstone_block", 192,  32,  28);
        put("coal_block",     28,  28,  28);
        put("netherite_block", 80,  72,  76);
        put("copper_block",  184, 118,  80);
        put("exposed_copper",168, 136, 112);
        put("weathered_copper", 104, 160, 120);
        put("oxidized_copper", 82, 168, 130);
        put("chain",          64,  64,  72);

        // ---- Lighting ---------------------------------------------------
        put("torch",         240, 200, 100);
        put("wall_torch",    240, 200, 100);
        put("lantern",       200, 180, 100);
        put("soul_lantern",  100, 180, 200);
        put("glowstone",     240, 220, 140);
        put("shroomlight",   232, 180, 100);
        put("jack_o_lantern",220, 164,  56);
        put("glow_lichen",   100, 140, 120);

        // ---- Walls (inherit stone family colours) -----------------------
        put("cobblestone_wall",           112, 112, 112);
        put("mossy_cobblestone_wall",     100, 112, 100);
        put("stone_brick_wall",           118, 118, 118);
        put("mossy_stone_brick_wall",     100, 118, 100);
        put("granite_wall",               155, 114,  96);
        put("diorite_wall",               196, 196, 196);
        put("andesite_wall",              136, 136, 136);
        put("sandstone_wall",             220, 200, 156);
        put("red_sandstone_wall",         182, 100,  44);
        put("brick_wall",                 176,  96,  72);
        put("prismarine_wall",             80, 160, 148);
        put("deepslate_brick_wall",        84,  84,  92);
        put("deepslate_tile_wall",         80,  80,  88);
        put("blackstone_wall",             36,  32,  40);
        put("polished_blackstone_wall",    48,  44,  52);
        put("polished_blackstone_brick_wall", 44, 40, 48);

        // ---- Nether -----------------------------------------------------
        put("netherrack",     148,  60,  60);
        put("nether_bricks",   56,  28,  32);
        put("nether_brick_fence", 56, 28, 32);
        put("red_nether_bricks",  68,  24,  28);
        put("quartz_block",   232, 228, 220);
        put("smooth_quartz",  232, 228, 220);
        put("quartz_bricks",  232, 228, 220);
        put("quartz_pillar",  232, 228, 220);
        put("soul_sand",       92,  76,  56);
        put("soul_soil",       84,  68,  52);
        put("crimson_planks",  156,  60,  84);
        put("warped_planks",    48, 132, 140);
        put("crimson_log",     112,  36,  56);
        put("warped_log",       32,  96, 104);

        // ---- Misc structural -------------------------------------------
        put("ladder",          160, 128,  80);
        put("scaffolding",     204, 172,  92);
        put("barrel",          136, 104,  64);
        put("chest",           164, 128,  72);
        put("bookshelf",       162, 130,  78);
        put("crafting_table",  162, 130,  78);
        put("furnace",         118, 118, 118);
        put("blast_furnace",   140, 140, 140);
        put("smithing_table",  120, 100,  80);

        // ---- Water / lava ----------------------------------------------
        put("water",            48, 100, 172);
        put("lava",            228,  80,  24);

        // ---- End -------------------------------------------------------
        put("end_stone",       224, 228, 168);
        put("end_stone_bricks",216, 220, 156);
        put("purpur_block",    172, 128, 172);
        put("purpur_pillar",   172, 128, 172);
        put("obsidian",         28,  24,  36);
        put("crying_obsidian",  60,  20, 100);
        put("crying_obsidian",  60,  20, 100);
    }

    private BlockColorPalette() {}

    /**
     * Returns the palette colour for the given block ID.
     * Strips any Minecraft namespace prefix and block-state bracket before lookup.
     * Falls back on suffix-stripping ({@code _stairs} → base, etc.).
     * Unknown blocks return {@link #UNKNOWN_COLOR}.
     */
    public static Color lookup(String blockId) {
        if (blockId == null || blockId.isBlank()) return UNKNOWN_COLOR;

        // Strip namespace
        String id = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        // Strip state properties
        int bracket = id.indexOf('[');
        if (bracket != -1) id = id.substring(0, bracket);

        Color c = PALETTE.get(id);
        if (c != null) return c;

        // Try stripping common shape suffixes to find base material colour
        c = tryStripSuffix(id);
        if (c != null) return c;

        if (LOGGED_UNKNOWNS.add(id)) {
            System.out.println("[BlockColorPalette] unknown block: " + id + " — using magenta");
        }
        return UNKNOWN_COLOR;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Color tryStripSuffix(String id) {
        // Order matters: check longest suffixes first
        String[] suffixes = {
            "_fence_gate", "_trapdoor", "_pressure_plate", "_button",
            "_stairs", "_slab", "_fence", "_door", "_wall",
            "_wood", // stripped_* wood → log → planks
        };
        for (String suf : suffixes) {
            if (id.endsWith(suf)) {
                String base = id.substring(0, id.length() - suf.length());
                // For stairs/slab on stone variants that use different naming
                Color c = PALETTE.get(base);
                if (c != null) return c;
                // Try appending _planks for wood families (e.g. "oak" → "oak_planks")
                c = PALETTE.get(base + "_planks");
                if (c != null) return c;
                // Try as-is for logs stripped
                c = PALETTE.get(base + "_log");
                if (c != null) return c;
            }
        }
        // Handle "stripped_X_log / stripped_X_wood" → look up X_log
        if (id.startsWith("stripped_")) {
            String inner = id.substring("stripped_".length());
            if (inner.endsWith("_log") || inner.endsWith("_wood")) {
                Color c = PALETTE.get(inner);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static void put(String id, int r, int g, int b) {
        PALETTE.put(id, new Color(r, g, b));
    }

    private static void putAll(Color color, String... ids) {
        for (String id : ids) PALETTE.put(id, color);
    }
}
