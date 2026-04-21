package com.rayyan.tesseract.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.blueprint.Blueprint;
import com.rayyan.tesseract.plan.BoundingBox;
import com.rayyan.tesseract.plan.ElementSpec;
import com.rayyan.tesseract.plan.MajorMass;
import com.rayyan.tesseract.plan.StructuralZone;
import com.rayyan.tesseract.render.IsoRenderer;
import com.rayyan.tesseract.sandbox.Sandbox;
import com.rayyan.tesseract.sandbox.SandboxLimits;
import com.rayyan.tesseract.agent.critic.CriticOpinion;
import com.rayyan.tesseract.agent.critic.CriticSwarm;
import com.rayyan.tesseract.toolbox.Toolbox;
import com.rayyan.tesseract.toolbox.ToolboxExtensionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * §7.1 — the L4 REPL. Runs one {@link ElementSpec} through the
 * write-script / run / render / critique loop until the element is
 * either approved by the §8 critic swarm + {@link ReconcilerAgent} or
 * hits the §7.2 budget caps.
 *
 * <p>Per turn the agent sees (1) the concept image(s), (2) the render
 * of the current cumulative build, (3) the previous turn's critic
 * feedback (if any), and (4) the last sandbox diagnostic (if the
 * previous script crashed). It writes a Python-subset script that
 * calls {@link Toolbox} primitives and {@code emit()}s the resulting
 * block ops. The REPL executes it in {@link Sandbox}, renders the
 * accumulated build, and calls the critic again.
 *
 * <p>Graceful-degradation paths:
 * <ul>
 *   <li>§7.2.2 — 10 turns or {@value #PER_ELEMENT_USD_CAP} USD
 *       whichever first; commits the last valid ops + logs
 *       {@code ELEMENT_BUDGET_EXCEEDED}.</li>
 *   <li>§7.2.3 — 3 consecutive sandbox errors → fall back to a plain
 *       {@link Toolbox#box} spanning the element's zone bounds.</li>
 *   <li>Critic timeouts become {@code CRITIC_SKIPPED}; reconciler
 *       fail-soft still returns a merged brief (§8.2 / §8.3).</li>
 * </ul>
 *
 * <p>Finalised scripts are appended to {@link BuildState#buildScripts}
 * so Step 6.3's ToolPromoter can review user-defined helpers after the
 * build completes.
 */
public final class L4ReplAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l4");

    /** §7.2.2 cap — 10 REPL turns per element. */
    public static final int MAX_TURNS_PER_ELEMENT = 10;
    /** §7.2.2 cap — $0.15 cost budget per element. */
    public static final double PER_ELEMENT_USD_CAP = 0.15;
    /** §7.2.3 — three consecutive sandbox errors triggers a plain-box fallback. */
    public static final int MAX_CONSECUTIVE_SANDBOX_ERRORS = 3;

    /** Render scale for mid-REPL frames. Cheap, fits Gemini context. */
    private static final int RENDER_PPB = 10;
    /** Default mass resolution — matches {@link VoxelMass#DEFAULT_RESOLUTION}. */
    private static final int DEFAULT_RESOLUTION = 16;

    private static final String TOOLBOX_DOC_RESOURCE = "/toolbox.md";
    private static final String TOOLBOX_DOC_FALLBACK =
            "Toolbox API (Set<BlockOp> returning):\n" +
            "  box(x1,y1,z1,x2,y2,z2, material)\n" +
            "  cylinder(cx,cz,y1,y2,radius, material)\n" +
            "  pyramid(cx,cz,y1,height,baseRadius, material)\n" +
            "  sphere(cx,cy,cz,radius, material)\n" +
            "  walls(x1,y1,z1,x2,y2,z2, material)\n" +
            "  frame(x1,y1,z1,x2,y2,z2, material)\n" +
            "  line(x1,y1,z1,x2,y2,z2, material)\n" +
            "  arc(cx,cy,cz,radius,startDeg,endDeg,axis, material)  axis is 'x'|'y'|'z'\n" +
            "  crenellate(wallTop, period, offset, material)\n" +
            "  scatter(x1,y1,z1,x2,y2,z2, density, seed, material)\n" +
            "Composition:\n" +
            "  repeat(ops, dx, dy, dz, count)\n" +
            "  mirror(ops, axis, pivot)  axis is 'x'|'y'|'z'\n" +
            "  subtract(a, b)\n" +
            "  intersect(a, b)\n" +
            "Emission: call emit(opSet) for each part of the element. " +
            "emit is the ONLY way to contribute ops — return values are ignored.";

    private L4ReplAgent() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs the REPL for a single element and returns the resulting
     * {@link ElementLock}. Blocks on Gemini calls so the caller can
     * sequence element processing in dependency order (§7.2.1).
     *
     * @param extensionPrompt rendered text of the persistent toolbox
     *                        extensions (see {@link ToolboxExtensionStore})
     *                        — empty string disables the block.
     */
    public static ElementLock runElement(BuildState state,
                                         GeminiClient gemini,
                                         CumulativeBuild cumulative,
                                         ElementSpec spec,
                                         String extensionPrompt) {
        String elementId = spec.id();
        ElementBudget budget = new ElementBudget(elementId, MAX_TURNS_PER_ELEMENT, 180_000L);
        double usdAtStart = state.costTracker.totalUsd();

        BoundingBox zoneBox = zoneBounds(state, spec);
        String systemPrompt = buildSystemPrompt(extensionPrompt);
        String lastCriticNotes = "";
        List<String> lastIssues = List.of();
        List<String> lastSuggestions = List.of();
        String lastSandboxError = "";
        String lastScript = "";
        Set<BlockOp> lastGoodOps = new LinkedHashSet<>();
        double lastScore = 0.0;
        int sandboxErrorStreak = 0;
        int totalSandboxErrors = 0;
        byte[] lastRender = cumulative == null ? null : cumulative.render(RENDER_PPB);

        while (!budget.shouldStop()
               && (state.costTracker.totalUsd() - usdAtStart) < PER_ELEMENT_USD_CAP) {
            budget.recordTurn();
            int turn = budget.turnsUsed();

            String userPrompt = buildUserPrompt(state, spec, zoneBox, turn,
                    lastCriticNotes, lastIssues, lastSuggestions, lastSandboxError);
            List<ImagePart> images = buildImages(state, lastRender);

            TurnResponse response;
            try {
                response = callAgent(gemini, systemPrompt, userPrompt, images, state);
            } catch (Exception err) {
                LOGGER.warn("L4 turn LLM error element={} turn={} reason={}",
                        elementId, turn, err.getMessage());
                lastSandboxError = "";
                if (++sandboxErrorStreak >= MAX_CONSECUTIVE_SANDBOX_ERRORS) {
                    return fallbackBox(spec, zoneBox, budget, totalSandboxErrors, lastScore,
                            "LLM failed " + sandboxErrorStreak + "x");
                }
                continue;
            }

            if (response == null) {
                if (++sandboxErrorStreak >= MAX_CONSECUTIVE_SANDBOX_ERRORS) {
                    return fallbackBox(spec, zoneBox, budget, totalSandboxErrors, lastScore,
                            "unparseable LLM responses");
                }
                continue;
            }

            Sandbox.SandboxResult sandboxResult = Sandbox.run(response.script, SandboxLimits.defaults());
            if (!sandboxResult.completed()) {
                totalSandboxErrors++;
                sandboxErrorStreak++;
                lastSandboxError = sandboxResult.diagnostic() == null
                        ? "unknown sandbox error" : sandboxResult.diagnostic();
                lastScript = response.script;
                LOGGER.info("L4_SANDBOX_ERROR element={} turn={} streak={} detail={}",
                        elementId, turn, sandboxErrorStreak, lastSandboxError);
                if (sandboxErrorStreak >= MAX_CONSECUTIVE_SANDBOX_ERRORS) {
                    return fallbackBox(spec, zoneBox, budget, totalSandboxErrors, lastScore,
                            "sandbox errors: " + lastSandboxError);
                }
                continue;
            }
            sandboxErrorStreak = 0;
            lastSandboxError = "";
            lastScript = response.script;
            Set<BlockOp> candidateOps = sandboxResult.collectedOps();
            if (candidateOps == null) candidateOps = new LinkedHashSet<>();

            byte[] cumulativePreview = renderWithPreview(cumulative, candidateOps);

            List<CriticOpinion> opinions = CriticSwarm.runSync(
                    state, gemini, spec, candidateOps, cumulativePreview, turn);

            if (ReconcilerAgent.lockEarly(opinions, candidateOps)) {
                double m = ReconcilerAgent.meanScore(opinions);
                lastGoodOps = candidateOps;
                lastRender = cumulativePreview;
                lastScore = m;
                return completed(spec, lastGoodOps, cumulativePreview, lastScript,
                        budget, totalSandboxErrors, m, "swarm lockEarly");
            }

            ReconcilerAgent.ReconciledCritique reconciled = ReconcilerAgent.reconcileSync(
                    state, gemini, spec, opinions, cumulativePreview, candidateOps);

            lastCriticNotes = reconciled.summary();
            lastIssues = reconciled.issues();
            ArrayList<String> sug = new ArrayList<>();
            sug.addAll(reconciled.consolidatedPatches());
            sug.addAll(reconciled.suggestions());
            lastSuggestions = sug;
            lastScore = reconciled.score();
            lastGoodOps = candidateOps;
            lastRender = cumulativePreview;

            LOGGER.info("L4_TURN element={} turn={} done={} score={} notes={}",
                    elementId, turn, response.done, reconciled.score(),
                    summarise(reconciled.summary()));

            if (response.done && reconciled.approved()) {
                return completed(spec, lastGoodOps, cumulativePreview, lastScript,
                        budget, totalSandboxErrors, reconciled.score(), reconciled.summary());
            }
            if (reconciled.score() >= 0.95 && !candidateOps.isEmpty()) {
                return completed(spec, lastGoodOps, cumulativePreview, lastScript,
                        budget, totalSandboxErrors, reconciled.score(),
                        "reconciler high score: " + reconciled.summary());
            }
        }

        budget.logExceeded();
        if (lastGoodOps.isEmpty()) {
            return fallbackBox(spec, zoneBox, budget, totalSandboxErrors, lastScore,
                    "budget exhausted with no valid script");
        }
        return new ElementLock(spec, lastGoodOps, new LinkedHashSet<>(),
                lastRender, lastScript, budget.turnsUsed(), totalSandboxErrors,
                false, true, lastScore, "BUDGET_EXCEEDED: " + lastCriticNotes);
    }

    // =========================================================================
    // LLM plumbing
    // =========================================================================

    private record TurnResponse(String script, boolean done, String notes) {}

    private static TurnResponse callAgent(GeminiClient gemini, String system, String user,
                                          List<ImagePart> images, BuildState state) {
        String raw = gemini.call(TaskKind.L4_REPL, system, user, images,
                        L4ReplAgent::isValidTurnResponse,
                        "RETURN ONLY JSON with keys script (string), done (bool), notes (string).",
                        state == null ? null : state.costTracker)
                .join();
        return parseTurn(raw);
    }

    static boolean isValidTurnResponse(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            return obj.has("script") && obj.get("script").isJsonPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

    private static TurnResponse parseTurn(String raw) {
        try {
            JsonObject obj = JsonParser.parseString(stripJsonFence(raw)).getAsJsonObject();
            String script = obj.get("script").getAsString();
            boolean done = obj.has("done") && obj.get("done").getAsBoolean();
            String notes = obj.has("notes") && obj.get("notes").isJsonPrimitive()
                    ? obj.get("notes").getAsString() : "";
            return new TurnResponse(script, done, notes);
        } catch (Exception e) {
            LOGGER.warn("L4 turn parse error: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Prompts
    // =========================================================================

    private static String buildSystemPrompt(String extensionPrompt) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("You are the L4 geometric REPL agent for Tesseract's Minecraft build pipeline.\n");
        sb.append("You author short Python scripts that emit BlockOp sets for ONE architectural ");
        sb.append("element at a time. The scripts run in a restricted sandbox (no imports, no ");
        sb.append("attribute access, no file/network I/O). Variables and functions are allowed; ");
        sb.append("`def` blocks you create may later be promoted into the persistent toolbox.\n\n");

        sb.append("=== TOOLBOX ===\n");
        sb.append(loadToolboxDoc()).append("\n\n");

        if (extensionPrompt != null && !extensionPrompt.isBlank()) {
            sb.append("=== EXTENSIONS (community-promoted helpers) ===\n");
            sb.append(extensionPrompt).append("\n\n");
        }

        sb.append("=== WORKFLOW ===\n");
        sb.append("Each turn you receive the element spec, zone bounds, the current cumulative ");
        sb.append("render, and (after turn 1) the critic's feedback. Write a script, call emit(...) ");
        sb.append("for every shape that contributes to THIS element, and set done=true when the ");
        sb.append("render matches the spec. The critic has veto power — it will reject a ");
        sb.append("premature done.\n\n");

        sb.append("=== RESPONSE FORMAT ===\n");
        sb.append("Return ONLY this JSON:\n");
        sb.append("{ \"script\": \"<python source>\", \"done\": bool, \"notes\": \"one-liner\" }\n");
        sb.append("No markdown fences, no prose outside the JSON.\n");

        sb.append("=== RULES ===\n");
        sb.append("1. Stay inside the zone bounds given in the user prompt.\n");
        sb.append("2. Use Minecraft block ids in material strings (e.g. 'stone_bricks', 'oak_log').\n");
        sb.append("3. Prefer calling toolbox primitives over hand-rolling voxel loops.\n");
        sb.append("4. Always call emit(...) on sets you want committed.\n");
        sb.append("5. Keep scripts under 60 lines — concise geometry > clever geometry.\n");
        return sb.toString();
    }

    private static String buildUserPrompt(BuildState state, ElementSpec spec,
                                          BoundingBox zoneBox, int turn,
                                          String criticNotes, List<String> issues,
                                          List<String> suggestions, String sandboxError) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Turn ").append(turn).append(" of ").append(MAX_TURNS_PER_ELEMENT).append('\n');
        sb.append("Element id: ").append(spec.id()).append('\n');
        sb.append("Mass / zone: ").append(spec.massLabel()).append(" / ").append(spec.zoneLabel()).append('\n');
        sb.append("Description: ").append(spec.description()).append('\n');
        if (spec.parameters() != null && spec.parameters().size() > 0) {
            sb.append("Parameters: ").append(spec.parameters()).append('\n');
        }
        sb.append("Zone bounds (inclusive): ");
        sb.append('[').append(zoneBox.minX()).append(',').append(zoneBox.minY()).append(',').append(zoneBox.minZ());
        sb.append("] .. [");
        sb.append(zoneBox.maxX()).append(',').append(zoneBox.maxY()).append(',').append(zoneBox.maxZ());
        sb.append("]\n");
        if (state != null && state.massPlan != null) {
            sb.append("Overall style: ").append(state.massPlan.overallStyle()).append('\n');
        }
        if (!sandboxError.isBlank()) {
            sb.append("\nLast turn's sandbox error: ").append(sandboxError).append('\n');
            sb.append("Fix the script so it runs cleanly.\n");
        }
        if (!criticNotes.isBlank() || !issues.isEmpty() || !suggestions.isEmpty()) {
            sb.append("\nCritic feedback:\n");
            if (!criticNotes.isBlank()) sb.append("  notes: ").append(criticNotes).append('\n');
            for (String i : issues) sb.append("  issue: ").append(i).append('\n');
            for (String s : suggestions) sb.append("  suggest: ").append(s).append('\n');
        }
        sb.append("\nReturn ONLY the JSON response.");
        return sb.toString();
    }

    private static List<ImagePart> buildImages(BuildState state, byte[] cumulativeRender) {
        List<ImagePart> parts = new ArrayList<>(2);
        ReferenceImage concept = primaryConcept(state);
        if (concept != null) parts.add(new ImagePart(concept.bytes(), concept.mimeType()));
        if (cumulativeRender != null && cumulativeRender.length > 0) {
            parts.add(new ImagePart(cumulativeRender, "image/png"));
        }
        return parts;
    }

    private static ReferenceImage primaryConcept(BuildState state) {
        if (state == null || state.referenceImages.isEmpty()) return null;
        int idx = Math.max(0, Math.min(state.selectedConceptIndex, state.referenceImages.size() - 1));
        return state.referenceImages.get(idx);
    }

    // =========================================================================
    // Rendering helpers
    // =========================================================================

    private static int resolution(CumulativeBuild cumulative) {
        return cumulative == null ? DEFAULT_RESOLUTION : cumulative.resolution();
    }

    private static byte[] renderWithPreview(CumulativeBuild cumulative, Set<BlockOp> candidateOps) {
        int res = resolution(cumulative);
        List<BlockOp> all = new ArrayList<>();
        if (cumulative != null) all.addAll(cumulative.allOps());
        all.addAll(candidateOps);
        if (all.isEmpty()) return null;
        Blueprint.Bounds bounds = new Blueprint.Bounds(res, res, res);
        return IsoRenderer.renderPng(all, bounds, RENDER_PPB);
    }

    // =========================================================================
    // Bounds helpers
    // =========================================================================

    private static BoundingBox zoneBounds(BuildState state, ElementSpec spec) {
        MajorMass mass = findMass(state, spec.massLabel());
        StructuralZone zone = findZone(state, spec.zoneLabel());
        int r = DEFAULT_RESOLUTION;
        int minX = 0, minZ = 0, maxX = r - 1, maxZ = r - 1;
        int minY = 0, maxY = r - 1;
        if (mass != null) {
            minX = mass.bounds().minX(); maxX = mass.bounds().maxX();
            minZ = mass.bounds().minZ(); maxZ = mass.bounds().maxZ();
            minY = mass.bounds().minY(); maxY = mass.bounds().maxY();
        }
        if (zone != null) {
            minY = Math.max(minY, zone.yMin());
            maxY = Math.min(maxY, zone.yMax());
            if (maxY < minY) { maxY = minY; }
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static MajorMass findMass(BuildState state, String label) {
        if (state == null || state.massPlan == null || label == null) return null;
        for (MajorMass m : state.massPlan.masses()) {
            if (label.equals(m.label())) return m;
        }
        return null;
    }

    private static StructuralZone findZone(BuildState state, String label) {
        if (state == null || label == null) return null;
        for (StructuralZone z : state.zoneSpecs) {
            if (label.equals(z.label())) return z;
        }
        return null;
    }

    // =========================================================================
    // Terminal outcomes
    // =========================================================================

    private static ElementLock completed(ElementSpec spec, Set<BlockOp> ops, byte[] render,
                                         String script, ElementBudget budget,
                                         int sandboxErrors, double score, String notes) {
        return new ElementLock(spec, ops, new LinkedHashSet<>(), render, script,
                budget.turnsUsed(), sandboxErrors, false, false, score, notes);
    }

    private static ElementLock fallbackBox(ElementSpec spec, BoundingBox zoneBox,
                                           ElementBudget budget, int sandboxErrors,
                                           double lastScore, String reason) {
        LOGGER.warn("L4_FALLBACK_BOX element={} reason={} bounds={}", spec.id(), reason, zoneBox);
        String material = pickFallbackMaterial(spec);
        Set<BlockOp> ops = Toolbox.box(
                zoneBox.minX(), zoneBox.minY(), zoneBox.minZ(),
                zoneBox.maxX(), zoneBox.maxY(), zoneBox.maxZ(), material);
        String script = String.format(
                "# SANDBOX_FALLBACK — box(%d,%d,%d .. %d,%d,%d) material=%s%n",
                zoneBox.minX(), zoneBox.minY(), zoneBox.minZ(),
                zoneBox.maxX(), zoneBox.maxY(), zoneBox.maxZ(), material);
        return new ElementLock(spec, ops, new LinkedHashSet<>(), null, script,
                budget.turnsUsed(), sandboxErrors, true, false, lastScore,
                "SANDBOX_FALLBACK: " + reason);
    }

    private static String pickFallbackMaterial(ElementSpec spec) {
        if (spec.parameters() != null && spec.parameters().has("material_family")) {
            try {
                return spec.parameters().get("material_family").getAsString();
            } catch (Exception ignored) {}
        }
        return "stone_bricks";
    }

    // =========================================================================
    // Misc
    // =========================================================================

    private static String loadToolboxDoc() {
        try (InputStream in = L4ReplAgent.class.getResourceAsStream(TOOLBOX_DOC_RESOURCE)) {
            if (in == null) return TOOLBOX_DOC_FALLBACK;
            StringBuilder sb = new StringBuilder(6_000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return TOOLBOX_DOC_FALLBACK;
        }
    }

    private static String stripJsonFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private static String summarise(String text) {
        if (text == null) return "";
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
