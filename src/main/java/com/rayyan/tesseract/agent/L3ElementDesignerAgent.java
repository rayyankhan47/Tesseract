package com.rayyan.tesseract.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rayyan.tesseract.api.GeminiClient;
import com.rayyan.tesseract.api.GeminiClient.ImagePart;
import com.rayyan.tesseract.api.TaskKind;
import com.rayyan.tesseract.plan.ElementSpec;
import com.rayyan.tesseract.plan.MajorMass;
import com.rayyan.tesseract.plan.MassPlan;
import com.rayyan.tesseract.plan.StructuralZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * §5.3 — L3 Element Designer. For each {@link StructuralZone} in the L2
 * output, produces {@link ElementSpec}s: descriptive + parameterised
 * architectural elements the L4 REPL will realise as block geometry.
 *
 * <p>Runs one Flash call per zone rather than one mega-call, so a
 * parse-failure on a single zone doesn't nuke the whole build (§3.3's
 * graceful-degradation pattern). Per-zone calls are launched in
 * parallel with a small concurrency cap.
 *
 * <p>Per-zone style critic (§5.3.2): optional second Flash call that
 * returns {@code score, drop_ids, notes}. We remove any drop_ids the
 * critic explicitly flagged and keep everything else. Critic failures
 * are non-fatal.
 *
 * <p>Dependency ordering (§5.3.3): after all per-zone responses return,
 * elements are topologically sorted using the explicit {@code depends_on}
 * edges, with {@code order_hint} (usually the element's Y-center) as
 * the tiebreaker. This order drives L4 scheduling.
 */
public final class L3ElementDesignerAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.l3");

    public static final double MIN_STYLE_SCORE = 0.6;

    private static final String SYSTEM_PROMPT = """
            You are the L3 Element Designer. For a single structural zone
            of a Minecraft build, propose a small list (3-10) of concrete
            architectural elements that will be realised later by a scripting
            agent.

            Each element is either a repeated motif ("arched window course at
            y=12, bays of 5") or a singleton ("crossing tower finial, centered
            at [x=8,z=8]"). Keep coordinates in the parent mass's 16³ voxel
            space. You do not choose block ids — suggest material_family
            only (e.g. "cut_stone", "oak_trim"). You do choose:
              - element_id: stable snake_case id, unique per zone
              - description: one-sentence natural language spec
              - parameters: a JSON object with numeric/string fields the
                scripting agent will reference (heights, spans, bay counts,
                material_family, style_tags)
              - depends_on: other element_ids within this same zone or
                earlier zones that must be placed first
              - order_hint: integer 0-255; lower = placed earlier (typically
                the element's Y center)

            Draw vocabulary from the retrieved corpus entries and cite any
            corpus ids you used.

            Return ONLY this JSON object — no prose, no markdown:
            {
              "elements": [
                {
                  "element_id": "foundation_course",
                  "description": "...",
                  "parameters": { "y": 0, "material_family": "cut_stone" },
                  "depends_on": [],
                  "order_hint": 0,
                  "citing": ["<corpus_id>"]
                }
              ],
              "citing": ["<corpus_id>"]
            }
            """;

    private static final String STRICT_REMINDER = """
            Return ONLY the JSON object. No prose, no code fences. Every
            element must have element_id, description, parameters (object),
            depends_on (array), order_hint (int). element_id must be unique
            within this zone's elements list.
            """;

    private static final String CRITIC_SYSTEM = """
            You are the Style Critic (L3 inner-loop, optional). Given the
            stated style and a proposed list of elements for a single zone,
            score 0-1 on stylistic coherence, then flag any elements that
            clash with the style. Return JSON:
            {
              "score": 0.0,
              "notes": "one sentence",
              "drop_ids": ["element_id_that_clashes", ...]
            }
            """;

    private L3ElementDesignerAgent() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs L3 for every zone in {@link BuildState#zoneSpecs}, populating
     * {@link BuildState#elementSpecs} with the dependency-sorted result.
     */
    public static CompletableFuture<List<ElementSpec>> run(BuildState state, GeminiClient gemini) {
        if (state.zoneSpecs.isEmpty() || state.massPlan == null) {
            CompletableFuture<List<ElementSpec>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "L3ElementDesignerAgent requires massPlan + zoneSpecs"));
            return failed;
        }

        List<StructuralZone> zones = new ArrayList<>(state.zoneSpecs);
        List<ImagePart> images = selectedImageParts(state);

        List<CompletableFuture<List<ElementSpec>>> perZone = new ArrayList<>(zones.size());
        for (StructuralZone zone : zones) {
            perZone.add(runZoneWithCritic(state, gemini, zone, images)
                    .exceptionally(err -> {
                        LOGGER.warn("L3 dropping zone {}/{} — {}",
                                zone.massLabel(), zone.label(), err.getMessage());
                        return List.of();
                    }));
        }

        return allOf(perZone).thenApply(results -> {
            List<ElementSpec> merged = new ArrayList<>();
            for (List<ElementSpec> zoneOut : results) merged.addAll(zoneOut);
            List<ElementSpec> ordered = topologicallySort(merged);
            state.elementSpecs.clear();
            state.elementSpecs.addAll(ordered);
            LOGGER.info("L3 produced {} elements across {} zones (post-sort)",
                    ordered.size(), zones.size());
            return ordered;
        });
    }

    // -------------------------------------------------------------------------
    // Per-zone execution
    // -------------------------------------------------------------------------

    private static CompletableFuture<List<ElementSpec>> runZoneWithCritic(
            BuildState state, GeminiClient gemini, StructuralZone zone, List<ImagePart> images) {

        MajorMass parent = findMass(state.massPlan, zone.massLabel());
        String userPrompt = buildUserPrompt(state, zone, parent);

        return gemini.call(TaskKind.L3_ELEMENT,
                        SYSTEM_PROMPT, userPrompt, images,
                        L3ElementDesignerAgent::isValidElementsResponse,
                        STRICT_REMINDER, state.costTracker)
                .thenApply(raw -> parseElements(raw, zone, state))
                .thenCompose(elements -> runStyleCritic(state, gemini, zone, elements));
    }

    private static String buildUserPrompt(BuildState state, StructuralZone zone, MajorMass parent) {
        StringBuilder sb = new StringBuilder(3072);
        sb.append("Overall style: ").append(state.massPlan.overallStyle()).append('\n');
        sb.append("User prompt: ").append(state.originalPrompt).append('\n');
        if (state.conceptCaption != null && !state.conceptCaption.isBlank()) {
            sb.append("Concept caption: ").append(state.conceptCaption).append('\n');
        }
        sb.append('\n');

        if (!state.ragContext.isEmpty()) {
            sb.append(RagAgent.formatContextBlock(state.ragContext, 3)).append("\n\n");
        }

        sb.append("Parent mass: ").append(zone.massLabel());
        if (parent != null) {
            sb.append(" role=").append(parent.role())
              .append(" bounds=").append(parent.bounds());
        }
        sb.append('\n');

        sb.append("Zone: ").append(zone.label())
          .append(" role=").append(zone.role())
          .append(" y=[").append(zone.yMin()).append("..").append(zone.yMax()).append("]")
          .append(" height=").append(zone.height())
          .append('\n');
        if (!zone.featureHints().isEmpty()) {
            sb.append("Feature hints: ").append(zone.featureHints()).append('\n');
        }
        if (!zone.materialFamilies().isEmpty()) {
            sb.append("Material families: ").append(zone.materialFamilies()).append('\n');
        }

        sb.append("\nReturn ONLY the JSON object listing this zone's elements.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Style critic
    // -------------------------------------------------------------------------

    private static CompletableFuture<List<ElementSpec>> runStyleCritic(
            BuildState state, GeminiClient gemini, StructuralZone zone, List<ElementSpec> elements) {
        if (elements.isEmpty()) return CompletableFuture.completedFuture(elements);

        String prompt = buildCriticPrompt(state, zone, elements);
        return gemini.call(TaskKind.CRITIC_INNER,
                        CRITIC_SYSTEM, prompt, List.of(), state.costTracker)
                .thenApply(raw -> applyStyleCritic(elements, raw, zone))
                .exceptionally(err -> {
                    LOGGER.warn("CRITIC_SKIPPED task=l3_style zone={}/{} reason={}",
                            zone.massLabel(), zone.label(), err.getMessage());
                    return elements;
                });
    }

    private static String buildCriticPrompt(BuildState state, StructuralZone zone, List<ElementSpec> elements) {
        StringBuilder sb = new StringBuilder(1500);
        sb.append("Style: ").append(state.massPlan.overallStyle()).append('\n');
        sb.append("Zone: ").append(zone.massLabel()).append('/').append(zone.label())
          .append(" role=").append(zone.role()).append('\n');
        sb.append("Proposed elements:\n");
        for (ElementSpec e : elements) {
            sb.append("  - ").append(e.id()).append(": ").append(e.description()).append('\n');
        }
        sb.append("\nReturn JSON { score, notes, drop_ids }.");
        return sb.toString();
    }

    private static List<ElementSpec> applyStyleCritic(List<ElementSpec> elements,
                                                      String raw, StructuralZone zone) {
        try {
            String trimmed = stripJsonFence(raw);
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            double score = obj.has("score") && obj.get("score").isJsonPrimitive()
                    ? obj.get("score").getAsDouble() : 1.0;
            String notes = obj.has("notes") && obj.get("notes").isJsonPrimitive()
                    ? obj.get("notes").getAsString() : "";
            LOGGER.info("L3_STYLE_CRITIC zone={}/{} score={} notes={}",
                    zone.massLabel(), zone.label(), String.format("%.2f", score), notes);

            if (!obj.has("drop_ids") || !obj.get("drop_ids").isJsonArray()) return elements;
            JsonArray drops = obj.getAsJsonArray("drop_ids");
            if (drops.size() == 0) return elements;

            Set<String> dropSet = new HashSet<>();
            for (JsonElement d : drops) {
                if (d.isJsonPrimitive()) dropSet.add(d.getAsString());
            }
            if (dropSet.isEmpty()) return elements;

            // Only honour drops if the score is low enough to warrant surgery.
            if (score >= MIN_STYLE_SCORE) return elements;

            List<ElementSpec> filtered = new ArrayList<>(elements.size());
            for (ElementSpec e : elements) {
                if (!dropSet.contains(e.id())) filtered.add(e);
            }
            LOGGER.info("L3_STYLE_CRITIC dropped {} element(s) in {}/{}",
                    elements.size() - filtered.size(), zone.massLabel(), zone.label());
            return filtered.isEmpty() ? elements : filtered;
        } catch (RuntimeException ex) {
            LOGGER.warn("CRITIC_SKIPPED task=l3_style zone={}/{} reason=parse:{}",
                    zone.massLabel(), zone.label(), ex.getMessage());
            return elements;
        }
    }

    // -------------------------------------------------------------------------
    // Parsing / validation
    // -------------------------------------------------------------------------

    static boolean isValidElementsResponse(String raw) {
        if (raw == null) return false;
        String trimmed = stripJsonFence(raw);
        if (!trimmed.startsWith("{")) return false;
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!obj.has("elements") || !obj.get("elements").isJsonArray()) return false;
            JsonArray arr = obj.getAsJsonArray("elements");
            if (arr.size() == 0) return false;
            Set<String> ids = new HashSet<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) return false;
                JsonObject el = e.getAsJsonObject();
                if (!el.has("element_id") || !el.has("description")) return false;
                String id = el.get("element_id").getAsString();
                if (id == null || id.isBlank()) return false;
                if (!ids.add(id)) return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<ElementSpec> parseElements(String raw, StructuralZone zone, BuildState state) {
        String trimmed = stripJsonFence(raw);
        JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
        if (obj.has("citing") && obj.get("citing").isJsonArray()) {
            RagAgent.recordCitations(state, stringArray(obj.getAsJsonArray("citing")));
        }
        JsonArray arr = obj.getAsJsonArray("elements");
        List<ElementSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject e = el.getAsJsonObject();
            String id = stringOr(e, "element_id", null);
            if (id == null) continue;
            String desc = stringOr(e, "description", "");
            JsonObject params = e.has("parameters") && e.get("parameters").isJsonObject()
                    ? e.getAsJsonObject("parameters") : new JsonObject();
            List<String> deps = e.has("depends_on") && e.get("depends_on").isJsonArray()
                    ? stringArray(e.getAsJsonArray("depends_on")) : List.of();
            int orderHint = e.has("order_hint") && e.get("order_hint").isJsonPrimitive()
                    ? e.get("order_hint").getAsInt()
                    : (zone.yMin() + zone.yMax()) / 2;
            List<String> citing = e.has("citing") && e.get("citing").isJsonArray()
                    ? stringArray(e.getAsJsonArray("citing")) : List.of();

            String qualifiedId = zone.massLabel() + "/" + zone.label() + "/" + id;
            out.add(new ElementSpec(qualifiedId, zone.label(), zone.massLabel(),
                    desc, params, qualifyDeps(deps, zone), orderHint, citing));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("L3 produced no usable elements for zone " + zone.label());
        }
        return out;
    }

    /**
     * Turns raw depends_on ids into the fully-qualified form used by
     * {@link ElementSpec#id()}. Unqualified ids are assumed to live in the
     * same zone; already-qualified ids (containing '/') pass through.
     */
    private static List<String> qualifyDeps(List<String> raw, StructuralZone zone) {
        if (raw.isEmpty()) return raw;
        List<String> out = new ArrayList<>(raw.size());
        for (String d : raw) {
            if (d.contains("/")) out.add(d);
            else out.add(zone.massLabel() + "/" + zone.label() + "/" + d);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Topological sort (§5.3.3 — base before walls before roof)
    // -------------------------------------------------------------------------

    static List<ElementSpec> topologicallySort(List<ElementSpec> elements) {
        Map<String, ElementSpec> byId = new HashMap<>();
        for (ElementSpec e : elements) byId.put(e.id(), e);

        // Kahn's algorithm with a stable tiebreaker by orderHint then id.
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (ElementSpec e : elements) {
            Set<String> known = new LinkedHashSet<>();
            for (String d : e.dependsOn()) {
                if (byId.containsKey(d)) known.add(d);
            }
            deps.put(e.id(), known);
            dependents.computeIfAbsent(e.id(), k -> new LinkedHashSet<>());
            for (String d : known) {
                dependents.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(e.id());
            }
        }

        Comparator<ElementSpec> tiebreak = Comparator
                .comparingInt(ElementSpec::orderHint)
                .thenComparing(ElementSpec::id);

        List<ElementSpec> ready = new ArrayList<>();
        for (ElementSpec e : elements) {
            if (deps.get(e.id()).isEmpty()) ready.add(e);
        }
        ready.sort(tiebreak);

        List<ElementSpec> sorted = new ArrayList<>(elements.size());
        while (!ready.isEmpty()) {
            ready.sort(tiebreak);
            ElementSpec head = ready.remove(0);
            sorted.add(head);
            for (String dep : dependents.getOrDefault(head.id(), Set.of())) {
                Set<String> remaining = deps.get(dep);
                remaining.remove(head.id());
                if (remaining.isEmpty() && byId.containsKey(dep)) {
                    ElementSpec next = byId.get(dep);
                    if (!sorted.contains(next) && !ready.contains(next)) ready.add(next);
                }
            }
        }

        if (sorted.size() < elements.size()) {
            // Cycle (or missing dep) — append the stragglers in orderHint order so
            // the build still has a fully-ordered list to schedule.
            LOGGER.warn("L3 dependency graph had a cycle or missing nodes — {} element(s) appended by orderHint",
                    elements.size() - sorted.size());
            Set<String> inSorted = new HashSet<>();
            for (ElementSpec e : sorted) inSorted.add(e.id());
            List<ElementSpec> rest = new ArrayList<>();
            for (ElementSpec e : elements) {
                if (!inSorted.contains(e.id())) rest.add(e);
            }
            rest.sort(tiebreak);
            sorted.addAll(rest);
        }
        return sorted;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<ImagePart> selectedImageParts(BuildState state) {
        List<ImagePart> parts = new ArrayList<>();
        if (!state.referenceImages.isEmpty()) {
            int idx = state.selectedConceptIndex;
            if (idx < 0 || idx >= state.referenceImages.size()) idx = 0;
            ReferenceImage concept = state.referenceImages.get(idx);
            parts.add(new ImagePart(concept.bytes(), concept.mimeType()));
        }
        return parts;
    }

    private static MajorMass findMass(MassPlan plan, String label) {
        for (MajorMass m : plan.masses()) if (label.equals(m.label())) return m;
        return null;
    }

    private static <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<T> out = new ArrayList<>(futures.size());
                    for (CompletableFuture<T> f : futures) out.add(f.join());
                    return out;
                });
    }

    private static String stripJsonFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull() || !el.isJsonPrimitive()) ? fallback : el.getAsString();
    }

    private static List<String> stringArray(JsonArray arr) {
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
