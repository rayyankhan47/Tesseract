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
 * §8.1.2 — does the build read as the target architectural style?
 * Routed through {@link TaskKind#CRITIC_INNER} (Flash).
 */
public final class StyleCritic {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic.style");

    private static final String SYSTEM = """
            You are a style critic for Minecraft architecture. You see an isometric
            render (two views). Decide whether the visible structure matches the stated
            overall style (gothic, brutalist, etc.). Return ONLY JSON:
            { "score": 0.0-1.0, "summary": "one line", "suggested_patches": ["short hint", ...] }
            Score 1.0 when the style reads clearly; 0.0 when it is generic or wrong.
            """;

    private static final String REMINDER = "Return ONLY the JSON object. No markdown.";

    private StyleCritic() {}

    public static CompletableFuture<CriticOpinion> evaluate(
            BuildState state, GeminiClient gemini, ElementSpec spec, byte[] cumulativeRender) {

        if (cumulativeRender == null || cumulativeRender.length == 0) {
            return CompletableFuture.completedFuture(
                    CriticOpinion.skipped(CriticKind.STYLE, "no render"));
        }

        String style = (state != null && state.massPlan() != null)
                ? state.massPlan().overallStyle() : "unspecified";
        String user = "Element: " + spec.id() + "\nDescription: " + spec.description()
                + "\nTarget style: " + style + "\nDoes this element read as that style?";

        List<ImagePart> images = new ArrayList<>(1);
        images.add(new ImagePart(cumulativeRender, "image/png"));

        return gemini.call(TaskKind.CRITIC_INNER, SYSTEM, user, images,
                        CriticJson::validOpinionJson, REMINDER,
                        state == null ? null : state.costTracker())
                .thenApply(raw -> CriticJson.parseOpinion(CriticKind.STYLE, raw))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED kind=STYLE element={} reason={}",
                            spec.id(), err.getMessage());
                    return CriticOpinion.skipped(CriticKind.STYLE, err.getMessage());
                });
    }
}
