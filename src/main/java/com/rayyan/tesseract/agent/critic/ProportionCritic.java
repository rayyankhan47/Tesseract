package com.rayyan.tesseract.agent.critic;

import com.rayyan.tesseract.agent.BuildState;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.ElementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * §8.1.3 — human-scale proportions (windows vs walls, door widths, hints).
 * Flash vision.
 */
public final class ProportionCritic {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic.proportion");

    private static final String SYSTEM = """
            You are a proportion critic for block-scale architecture. From the isometric
            render, judge whether openings, vertical rhythm, and masses feel human-scaled.
            Return ONLY JSON:
            { "score": 0.0-1.0, "summary": "one line", "suggested_patches": ["hint", ...] }
            """;

    private static final String REMINDER = "Return ONLY the JSON object.";

    private ProportionCritic() {}

    public static CompletableFuture<CriticOpinion> evaluate(
            BuildState state, GeminiClient gemini, ElementSpec spec, byte[] cumulativeRender) {

        if (cumulativeRender == null || cumulativeRender.length == 0) {
            return CompletableFuture.completedFuture(
                    CriticOpinion.skipped(CriticKind.PROPORTION, "no render"));
        }

        String user = "Element: " + spec.id() + "\n" + spec.description()
                + "\nAssess proportion and rhythm. Flag oversized flat walls or tiny windows.";

        List<ImagePart> images = new ArrayList<>(1);
        images.add(new ImagePart(cumulativeRender, "image/png"));

        return gemini.call(TaskKind.CRITIC_INNER, SYSTEM, user, images,
                        CriticJson::validOpinionJson, REMINDER,
                        state == null ? null : state.costTracker)
                .thenApply(raw -> CriticJson.parseOpinion(CriticKind.PROPORTION, raw))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED kind=PROPORTION element={} reason={}",
                            spec.id(), err.getMessage());
                    return CriticOpinion.skipped(CriticKind.PROPORTION, err.getMessage());
                });
    }
}
