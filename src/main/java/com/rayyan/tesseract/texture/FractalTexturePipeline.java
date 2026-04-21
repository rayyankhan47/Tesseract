package com.rayyan.tesseract.texture;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.agent.BuildState;

import java.util.ArrayList;
import java.util.List;

/**
 * §9 — Phase 6 post-L4 pass: pure-code weathering, WFC surfaces, organic
 * growth, decay, and crack lines. No LLM.
 */
public final class FractalTexturePipeline {

    private FractalTexturePipeline() {}

    /**
     * Returns a new list with copied ops so callers keep their original
     * geometry list if needed.
     *
     * @param buildIdSeed stable string (e.g. player id + prompt hash) for §9.2.3 / §9.2.3
     */
    public static List<BlockOp> apply(BuildState state, List<BlockOp> ops, String buildIdSeed) {
        if (ops == null || ops.isEmpty()) return ops;
        long seed = (buildIdSeed == null ? "tesseract" : buildIdSeed).hashCode();
        if (state != null && state.massPlan() != null) {
            seed ^= Double.doubleToLongBits(state.massPlan().age());
        }

        List<BlockOp> work = new ArrayList<>(ops.size());
        for (BlockOp o : ops) {
            if (o == null) continue;
            BlockOp c = new BlockOp();
            c.x = o.x;
            c.y = o.y;
            c.z = o.z;
            c.block = o.block;
            work.add(c);
        }

        WeatheringPass.apply(state, work, seed);
        WFCPass.apply(state, work, seed);
        LSystemPass.apply(state, work, seed);
        DecayPass.apply(state, work, seed);
        CrackPass.apply(state, work, seed);
        return work;
    }
}
