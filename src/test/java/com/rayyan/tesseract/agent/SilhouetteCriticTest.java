package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.agent.critic.CriticOpinion;
import com.rayyan.tesseract.agent.critic.SilhouetteCritic;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SilhouetteCriticTest {

    @Test
    void insideMass_scoresHigh() {
        BuildState state = new BuildState(UUID.randomUUID(), null, "test", null, null, null);
        VoxelMass m = new VoxelMass(16);
        m.add(1, 1, 1);
        state.massSketch = m;

        Set<BlockOp> ops = singleOp(1, 1, 1, "stone");
        CriticOpinion o = SilhouetteCritic.evaluate(state, ops);
        assertEquals(1.0, o.score(), 0.01);
        assertFalse(o.skipped());
        assertEquals(0.0, o.fractionOutsideMass(), 0.001);
    }

    @Test
    void outsideMass_fractionAndHardViolation() {
        BuildState state = new BuildState(UUID.randomUUID(), null, "test", null, null, null);
        VoxelMass m = new VoxelMass(16);
        m.add(0, 0, 0);
        state.massSketch = m;

        Set<BlockOp> ops = new LinkedHashSet<>();
        ops.add(op(5, 5, 5, "stone"));
        CriticOpinion o = SilhouetteCritic.evaluate(state, ops);
        assertEquals(1.0, o.fractionOutsideMass(), 0.001);
        assertTrue(SilhouetteCritic.hardViolation(o));
    }

    @Test
    void noMass_waived() {
        BuildState state = new BuildState(UUID.randomUUID(), null, "test", null, null, null);
        Set<BlockOp> ops = singleOp(0, 0, 0, "x");
        CriticOpinion o = SilhouetteCritic.evaluate(state, ops);
        assertTrue(o.summary().contains("waived") || o.score() >= 0.99);
    }

    private static Set<BlockOp> singleOp(int x, int y, int z, String b) {
        Set<BlockOp> s = new LinkedHashSet<>();
        s.add(op(x, y, z, b));
        return s;
    }

    private static BlockOp op(int x, int y, int z, String b) {
        BlockOp o = new BlockOp();
        o.x = x;
        o.y = y;
        o.z = z;
        o.block = b;
        return o;
    }
}
