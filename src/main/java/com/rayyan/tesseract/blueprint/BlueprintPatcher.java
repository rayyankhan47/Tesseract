package com.rayyan.tesseract.blueprint;

import com.google.gson.*;
import com.rayyan.tesseract.agent.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Applies a list of {@link Patch} ops to a {@link Blueprint} and returns the
 * patched version as a new (never-mutating) Blueprint.
 *
 * <p>Works entirely at the JSON level so it is agnostic to the compiler's
 * primitive-specific parameter schemas — it just rewrites the JSON and lets
 * {@link BlueprintParser} re-validate and {@link BlueprintCompiler} pre-flight.
 *
 * <p>If the patched blueprint fails to parse or compile, the original blueprint
 * is returned unchanged and the error is logged (fail-soft: a bad patch should
 * never destroy a structurally valid build).
 */
public final class BlueprintPatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.blueprint.patcher");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private BlueprintPatcher() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Applies patches to {@code source} and returns the resulting Blueprint.
     *
     * <p>If any patch fails (unknown id, malformed path) the entry is skipped.
     * If the final blueprint fails to parse or compile, {@code source} is returned.
     *
     * @param source  the current best blueprint
     * @param patches ordered list of patch operations (may be empty)
     * @return a new Blueprint after applying all valid patches, or {@code source}
     *         if the result would be invalid
     */
    public static Blueprint apply(Blueprint source, List<Patch> patches) {
        if (patches == null || patches.isEmpty()) return source;

        // Parse the raw JSON into a mutable document
        JsonObject root;
        try {
            root = JsonParser.parseString(source.rawJson).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            LOGGER.error("BlueprintPatcher: source rawJson is not valid JSON — returning original", e);
            return source;
        }

        JsonArray primitives = root.has("primitives") && root.get("primitives").isJsonArray()
                ? root.getAsJsonArray("primitives")
                : new JsonArray();

        boolean anyApplied = false;
        for (Patch p : patches) {
            boolean ok = applyPatch(primitives, p);
            if (ok) anyApplied = true;
        }

        if (!anyApplied) {
            LOGGER.debug("BlueprintPatcher: no patches were applicable — returning original");
            return source;
        }

        root.add("primitives", primitives);
        String newJson = GSON.toJson(root);

        // Re-validate and pre-flight compile
        try {
            Blueprint patched = BlueprintParser.parse(newJson);
            BlueprintCompiler.compile(patched); // pre-flight only — result discarded here
            LOGGER.info("BlueprintPatcher: {} patch(es) applied successfully", patches.size());
            return patched;
        } catch (BlueprintParseException | BlueprintCompileException e) {
            LOGGER.warn("BlueprintPatcher: patched blueprint invalid ({}); keeping original", e.getMessage());
            return source;
        } catch (RuntimeException e) {
            LOGGER.warn("BlueprintPatcher: compile threw unexpected exception ({}); keeping original", e.getMessage());
            return source;
        }
    }

    // =========================================================================
    // Per-op handlers
    // =========================================================================

    private static boolean applyPatch(JsonArray primitives, Patch p) {
        try {
            return switch (p.op) {
                case "modify"  -> applyModify(primitives, p);
                case "add"     -> applyAdd(primitives, p);
                case "remove"  -> applyRemove(primitives, p);
                case "replace" -> applyReplace(primitives, p);
                default -> {
                    LOGGER.warn("BlueprintPatcher: unknown op '{}' — skipping", p.op);
                    yield false;
                }
            };
        } catch (Exception e) {
            LOGGER.warn("BlueprintPatcher: exception applying patch {} — skipping: {}", p, e.getMessage());
            return false;
        }
    }

    // ---- modify ----

    private static boolean applyModify(JsonArray primitives, Patch p) {
        JsonObject prim = findById(primitives, p.id);
        if (prim == null) {
            LOGGER.warn("BlueprintPatcher: modify — no primitive with id '{}' found", p.id);
            return false;
        }
        if (p.field == null || p.field.isBlank() || p.value == null) {
            LOGGER.warn("BlueprintPatcher: modify — missing field or value for id '{}'", p.id);
            return false;
        }
        applyDotPath(prim, p.field.trim(), p.value);
        LOGGER.debug("BlueprintPatcher: modified '{}' field '{}' → {}", p.id, p.field, p.value);
        return true;
    }

    // ---- add ----

    private static boolean applyAdd(JsonArray primitives, Patch p) {
        if (p.primitive == null) {
            LOGGER.warn("BlueprintPatcher: add — no primitive object provided");
            return false;
        }
        // Ensure it has an id; generate one if missing
        if (!p.primitive.has("id") || p.primitive.get("id").isJsonNull()) {
            p.primitive.addProperty("id", "patch_" + (primitives.size() + 1));
        }
        primitives.add(p.primitive.deepCopy());
        LOGGER.debug("BlueprintPatcher: added primitive id='{}'",
                p.primitive.has("id") ? p.primitive.get("id").getAsString() : "?");
        return true;
    }

    // ---- remove ----

    private static boolean applyRemove(JsonArray primitives, Patch p) {
        int idx = findIndexById(primitives, p.id);
        if (idx < 0) {
            LOGGER.warn("BlueprintPatcher: remove — id '{}' not found", p.id);
            return false;
        }
        primitives.remove(idx);

        // Nullify any 'on' references to the deleted primitive
        for (JsonElement el : primitives) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("on") && !obj.get("on").isJsonNull()
                    && p.id.equals(obj.get("on").getAsString())) {
                LOGGER.warn("BlueprintPatcher: primitive '{}' had 'on:{}' which was removed — clearing 'on'",
                        obj.has("id") ? obj.get("id").getAsString() : "?", p.id);
                obj.add("on", JsonNull.INSTANCE);
            }
        }
        LOGGER.debug("BlueprintPatcher: removed primitive id='{}'", p.id);
        return true;
    }

    // ---- replace ----

    private static boolean applyReplace(JsonArray primitives, Patch p) {
        int idx = findIndexById(primitives, p.id);
        if (idx < 0) {
            LOGGER.warn("BlueprintPatcher: replace — id '{}' not found", p.id);
            return false;
        }
        if (p.primitive == null) {
            LOGGER.warn("BlueprintPatcher: replace — no primitive object for id '{}'", p.id);
            return false;
        }
        JsonObject replacement = p.primitive.deepCopy();
        // Force the id to match the original
        replacement.addProperty("id", p.id);
        primitives.set(idx, replacement);
        LOGGER.debug("BlueprintPatcher: replaced primitive id='{}'", p.id);
        return true;
    }

    // =========================================================================
    // Dot-path walker
    // =========================================================================

    /**
     * Walks a dot-path string and sets the leaf value on the given JsonObject.
     *
     * <p>Supported path syntax:
     * <ul>
     *   <li>{@code "height"}              — direct field on {@code obj}</li>
     *   <li>{@code "openings[0].u_offset"} — array index then field</li>
     *   <li>{@code "openings[1]"}          — set the whole array element</li>
     * </ul>
     */
    static void applyDotPath(JsonObject obj, String path, JsonElement value) {
        int dotIdx    = path.indexOf('.');
        int bracketIdx = path.indexOf('[');

        // Leaf node — set directly
        if (dotIdx < 0 && bracketIdx < 0) {
            obj.add(path, value);
            return;
        }

        // Array access: "openings[0]..." → descend into "openings", index 0
        if (bracketIdx >= 0 && (dotIdx < 0 || bracketIdx < dotIdx)) {
            String arrayKey   = path.substring(0, bracketIdx);
            int closeBracket  = path.indexOf(']', bracketIdx);
            if (closeBracket < 0) { obj.add(path, value); return; } // malformed
            int arrayIndex    = Integer.parseInt(path.substring(bracketIdx + 1, closeBracket));
            String remainder  = path.substring(closeBracket + 1);
            if (remainder.startsWith(".")) remainder = remainder.substring(1);

            // Get or create the array
            JsonArray arr;
            if (obj.has(arrayKey) && obj.get(arrayKey).isJsonArray()) {
                arr = obj.getAsJsonArray(arrayKey);
            } else {
                arr = new JsonArray();
                obj.add(arrayKey, arr);
            }
            // Grow the array if needed
            while (arr.size() <= arrayIndex) arr.add(JsonNull.INSTANCE);

            if (remainder.isBlank()) {
                // Set the array element itself
                arr.set(arrayIndex, value);
            } else {
                // Descend into the array element (should be a JsonObject)
                JsonElement elem = arr.get(arrayIndex);
                JsonObject child = (elem != null && elem.isJsonObject())
                        ? elem.getAsJsonObject() : new JsonObject();
                applyDotPath(child, remainder, value);
                arr.set(arrayIndex, child);
            }
            return;
        }

        // Dot access: "someObject.field" → descend into "someObject"
        String head = path.substring(0, dotIdx);
        String tail = path.substring(dotIdx + 1);
        JsonObject child;
        if (obj.has(head) && obj.get(head).isJsonObject()) {
            child = obj.getAsJsonObject(head).deepCopy();
        } else {
            child = new JsonObject();
        }
        applyDotPath(child, tail, value);
        obj.add(head, child);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JsonObject findById(JsonArray primitives, String id) {
        int idx = findIndexById(primitives, id);
        return idx < 0 ? null : primitives.get(idx).getAsJsonObject();
    }

    private static int findIndexById(JsonArray primitives, String id) {
        if (id == null) return -1;
        for (int i = 0; i < primitives.size(); i++) {
            JsonElement el = primitives.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("id") && id.equals(obj.get("id").getAsString())) return i;
        }
        return -1;
    }
}
