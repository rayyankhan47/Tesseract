package com.rayyan.tesseract.blueprint;

import com.rayyan.tesseract.agent.BuildSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared palette-building utility used by {@code BlueprintPlanningAgent} and the
 * isometric renderer colour lookup.
 */
public final class PaletteUtils {

    private PaletteUtils() {}

    /**
     * Builds a focused palette of ~50–80 block IDs:
     * <ol>
     *   <li>A universal set (glass, torches, basic stone/oak variants)</li>
     *   <li>Every material from {@code spec.materials} expanded to {@code minecraft:} form</li>
     *   <li>Automatic {@code _slab}, {@code _stairs}, {@code _wall} variants</li>
     *   <li>Wood-family extras for plank/log materials</li>
     * </ol>
     *
     * @param spec the interpreted build spec; may be {@code null} (returns universal set only)
     */
    public static List<String> buildFocusedPalette(BuildSpec spec) {
        Set<String> palette = new LinkedHashSet<>();

        palette.addAll(List.of(
            "minecraft:stone", "minecraft:stone_slab", "minecraft:stone_stairs",
            "minecraft:cobblestone", "minecraft:cobblestone_slab",
            "minecraft:cobblestone_stairs", "minecraft:cobblestone_wall",
            "minecraft:stone_bricks", "minecraft:stone_brick_slab",
            "minecraft:stone_brick_stairs", "minecraft:stone_brick_wall",
            "minecraft:oak_planks", "minecraft:oak_slab", "minecraft:oak_stairs",
            "minecraft:oak_log", "minecraft:oak_fence", "minecraft:oak_door",
            "minecraft:oak_trapdoor",
            "minecraft:glass", "minecraft:glass_pane",
            "minecraft:torch", "minecraft:lantern",
            "minecraft:iron_bars", "minecraft:ladder",
            "minecraft:dirt", "minecraft:gravel"
        ));

        if (spec != null && spec.getMaterials() != null) {
            for (String mat : spec.getMaterials()) {
                String id = mat.contains(":") ? mat : "minecraft:" + mat;
                String base = id.substring("minecraft:".length());
                palette.add(id);
                palette.add("minecraft:" + base + "_slab");
                palette.add("minecraft:" + base + "_stairs");

                if (base.endsWith("_bricks") || base.endsWith("stone")
                        || base.endsWith("cobblestone") || base.endsWith("deepslate")
                        || base.endsWith("sandstone") || base.endsWith("basalt")
                        || base.endsWith("blackstone")) {
                    palette.add("minecraft:" + base + "_wall");
                }

                if (base.endsWith("_planks")) {
                    String wood = base.replace("_planks", "");
                    palette.add("minecraft:" + wood + "_slab");
                    palette.add("minecraft:" + wood + "_stairs");
                    palette.add("minecraft:" + wood + "_fence");
                    palette.add("minecraft:" + wood + "_fence_gate");
                    palette.add("minecraft:" + wood + "_door");
                    palette.add("minecraft:" + wood + "_trapdoor");
                    palette.add("minecraft:" + wood + "_log");
                    palette.add("minecraft:" + wood + "_wood");
                }
            }
        }

        return List.copyOf(palette);
    }
}
