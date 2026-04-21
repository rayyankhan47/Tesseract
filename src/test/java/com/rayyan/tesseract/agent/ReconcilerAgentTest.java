package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.agent.critic.CriticKind;
import com.rayyan.tesseract.agent.critic.CriticOpinion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcilerAgentTest {

    @Test
    void meanScore_ignoresSkipped() {
        List<CriticOpinion> list = List.of(
                new CriticOpinion(CriticKind.STYLE, 0.8, "", List.of(), false, "", null),
                CriticOpinion.skipped(CriticKind.DETAIL, "timeout"),
                new CriticOpinion(CriticKind.PROPORTION, 0.6, "", List.of(), false, "", null));
        assertEquals(0.7, ReconcilerAgent.meanScore(list), 0.001);
    }

    @Test
    void lockEarly_requiresOpsAndScores() {
        List<CriticOpinion> good = List.of(
                new CriticOpinion(CriticKind.SILHOUETTE, 1.0, "", List.of(), false, "", 0.0),
                new CriticOpinion(CriticKind.STYLE, 0.9, "", List.of(), false, "", null));
        Set<BlockOp> ops = new LinkedHashSet<>();
        BlockOp b = new BlockOp();
        b.x = 0;
        b.y = 0;
        b.z = 0;
        b.block = "stone";
        ops.add(b);
        assertTrue(ReconcilerAgent.lockEarly(good, ops));
        assertFalse(ReconcilerAgent.lockEarly(good, Set.of()));
    }
}
