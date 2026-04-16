package com.rayyan.tesseract.agent;

import com.rayyan.tesseract.gumloop.GumloopPayload;

import java.util.List;

/**
 * Stage 4 of the build pipeline — programmatic validation of a single component's block array.
 *
 * No LLM involved. Validates that a generated component meets structural and palette
 * constraints before any blocks are placed in the world.
 *
 * Step 7 implements the full checks. This class exists here so GenerationAgent can
 * compile against the correct method signature.
 */
public final class CriticAgent {

    private CriticAgent() {}

    /**
     * Validates the generated block operations for one component.
     *
     * @param ops       block operations produced by GenerationAgent
     * @param component the component plan (provides bounding box)
     * @param palette   allowed block IDs (full minecraft: form)
     * @return a {@link CriticResult} with pass/fail and an optional failure reason
     */
    public static CriticResult validate(List<GumloopPayload.BlockOp> ops,
                                        ComponentPlan component,
                                        List<String> palette) {
        // Step 7 replaces this stub with the full validation suite:
        //   Check 1 — null/empty
        //   Check 2 — palette conformance
        //   Check 3 — in-bounds coordinates
        //   Check 4 — floating block detection
        //   Check 5 — budget enforcement (non-fatal truncation)
        if (ops == null || ops.isEmpty()) {
            return CriticResult.fail("component generated zero blocks");
        }
        return CriticResult.pass();
    }
}
