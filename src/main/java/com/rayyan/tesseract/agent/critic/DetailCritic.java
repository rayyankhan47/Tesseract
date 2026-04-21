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
 * §8.1.4 — surface richness vs flat mass. Flash vision.
 */
public final class DetailCritic {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic.detail");

    private static final String SYSTEM = """
            You judge surface articulation in a Minecraft isometric render. Is this a flat
            monolith or does it have relief, texture changes, cornices, or rhythm?
            Return ONLY JSON:
            { "score": 0.0-1.0, "summary": "one line", "suggested_patches": ["density hint", ...] }
            """;

    private static final String REMINDER = "Return ONLY the JSON object.";

    private DetailCritic() {}

    public static CompletableFuture<CriticOpinion> evaluate(
            BuildState state, GeminiClient gemini, ElementSpec spec, byte[] cumulativeRender) {

        if (cumulativeRender == null || cumulativeRender.length == 0) {
            return CompletableFuture.completedFuture(
                    CriticOpinion.skipped(CriticKind.DETAIL, "no render"));
        }

        String user = "Element: " + spec.id() + "\n" + spec.description()
                + "\nRate detail density and suggest one concrete improvement.";

        List<ImagePart> images = new ArrayList<>(1);
        images.add(new ImagePart(cumulativeRender, "image/png"));

        return gemini.call(TaskKind.CRITIC_INNER, SYSTEM, user, images,
                        CriticJson::validOpinionJson, REMINDER,
                        state == null ? null : state.costTracker())
                .thenApply(raw -> CriticJson.parseOpinion(CriticKind.DETAIL, raw))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED kind=DETAIL element={} reason={}",
                            spec.id(), err.getMessage());
                    return CriticOpinion.skipped(CriticKind.DETAIL, err.getMessage());
                });
    }
}
