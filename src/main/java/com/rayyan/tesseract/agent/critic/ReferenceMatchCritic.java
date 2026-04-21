package com.rayyan.tesseract.agent.critic;

import com.rayyan.tesseract.agent.BuildState;
import com.rayyan.tesseract.agent.ReferenceImage;
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
 * §8.1.5 — multimodal compare: concept image vs current cumulative render.
 * "What is in the concept that is missing from the build?"
 */
public final class ReferenceMatchCritic {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.critic.refmatch");

    private static final String SYSTEM = """
            You compare two images: (1) the player's target concept art, (2) the current
            isometric Minecraft build. List the most important visual elements present in
            the concept that are missing or weak in the build. Return ONLY JSON:
            { "score": 0.0-1.0, "summary": "one line", "suggested_patches": ["add X", ...] }
            Score high when the build clearly echoes the concept; low when it diverges.
            """;

    private static final String REMINDER = "Return ONLY the JSON object.";

    private ReferenceMatchCritic() {}

    public static CompletableFuture<CriticOpinion> evaluate(
            BuildState state, GeminiClient gemini, ElementSpec spec, byte[] cumulativeRender) {

        ReferenceImage concept = state == null ? null : state.primaryReferenceImage();
        if (concept == null || cumulativeRender == null || cumulativeRender.length == 0) {
            return CompletableFuture.completedFuture(
                    CriticOpinion.skipped(CriticKind.REFERENCE_MATCH, "no concept or render"));
        }

        String user = "Element being built: " + spec.id() + "\n" + spec.description()
                + "\nWhat from the concept is still missing in the build?";

        List<ImagePart> images = new ArrayList<>(2);
        images.add(new ImagePart(concept.bytes(), concept.mimeType()));
        images.add(new ImagePart(cumulativeRender, "image/png"));

        return gemini.call(TaskKind.CRITIC_INNER, SYSTEM, user, images,
                        CriticJson::validOpinionJson, REMINDER,
                        state == null ? null : state.costTracker())
                .thenApply(raw -> CriticJson.parseOpinion(CriticKind.REFERENCE_MATCH, raw))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED kind=REFERENCE_MATCH element={} reason={}",
                            spec.id(), err.getMessage());
                    return CriticOpinion.skipped(CriticKind.REFERENCE_MATCH, err.getMessage());
                });
    }

}
