package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalTexturePipelineTest {

    @Test
    void pipeline_runsWithoutThrowing() {
        List<BlockOp> ops = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BlockOp o = new BlockOp();
            o.x = i % 4;
            o.y = 0;
            o.z = i / 4;
            o.block = "stone_bricks";
            ops.add(o);
        }

        List<BlockOp> out = FractalTexturePipeline.apply(null, ops, "test-seed");
        assertEquals(20, out.size());
    }

    @Test
    void lSystem_expandBounded() {
        String s = LSystemPass.expandLSystem(5);
        assertTrue(s.length() > 10);
        assertTrue(s.contains("F"));
    }

    @Test
    void weatheringPalette_normalizesIds() {
        assertEquals("stone_bricks", WeatheringPalette.norm("minecraft:stone_bricks"));
        assertEquals("stone_bricks", WeatheringPalette.norm("Stone_Bricks"));
    }
}
