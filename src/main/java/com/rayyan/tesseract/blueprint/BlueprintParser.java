package com.rayyan.tesseract.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and structurally validates a Blueprint JSON string.
 *
 * <p>Parse steps:
 * <ol>
 *   <li>Strip optional markdown code fences.</li>
 *   <li>Parse as JSON object.</li>
 *   <li>Extract {@code name}, {@code bounds}, {@code primitives}.</li>
 *   <li>Validate invariants (unique ids, legal {@code on} refs, known types, bounds).</li>
 * </ol>
 *
 * <p>Unknown primitive types log a WARNING and are skipped (forward-compatible
 * for future primitive additions) unless {@link #parseStrict} is called.
 */
public final class BlueprintParser {

    private static final Logger LOGGER = LoggerFactory.getLogger("tesseract.blueprint.parser");

    /** Generous upper limit per axis; well above any buildable selection. */
    private static final int MAX_AXIS = 128;

    private BlueprintParser() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses a Blueprint from a JSON string, skipping unknown primitive types.
     *
     * @param json raw JSON (may be wrapped in markdown fences)
     * @return parsed and validated Blueprint
     * @throws BlueprintParseException on any structural error
     */
    public static Blueprint parse(String json) throws BlueprintParseException {
        return parse(json, false);
    }

    /**
     * Like {@link #parse(String)} but fails immediately on any unknown
     * primitive type instead of skipping it.  Used by unit tests.
     */
    public static Blueprint parseStrict(String json) throws BlueprintParseException {
        return parse(json, true);
    }

    // -------------------------------------------------------------------------
    // Internal implementation
    // -------------------------------------------------------------------------

    private static Blueprint parse(String rawInput, boolean strict) throws BlueprintParseException {
        String json = stripFences(rawInput);

        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                throw new BlueprintParseException(
                        "Blueprint must be a JSON object, got: "
                        + el.getClass().getSimpleName()
                        + " — raw: " + preview(json));
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new BlueprintParseException(
                    "JSON parse error: " + e.getMessage() + " — raw: " + preview(json), e);
        }

        // ---- name ----
        String name = root.has("name") && !root.get("name").isJsonNull()
                ? root.get("name").getAsString()
                : "unnamed";

        // ---- bounds ----
        Blueprint.Bounds bounds = parseBounds(root, json);

        // ---- primitives ----
        if (!root.has("primitives") || !root.get("primitives").isJsonArray()) {
            throw new BlueprintParseException(
                    "Blueprint missing required 'primitives' array — raw: " + preview(json));
        }
        JsonArray primArr = root.getAsJsonArray("primitives");
        if (primArr.size() == 0) {
            throw new BlueprintParseException("Blueprint 'primitives' array is empty.");
        }

        List<Primitive> primitives = parsePrimitives(primArr, strict, json);

        // ---- structural validation ----
        validateStructure(primitives, json);

        return new Blueprint(name, bounds, primitives, json);
    }

    // ---- Bounds parsing ----

    private static Blueprint.Bounds parseBounds(JsonObject root, String rawJson)
            throws BlueprintParseException {
        if (!root.has("bounds") || root.get("bounds").isJsonNull()) {
            throw new BlueprintParseException(
                    "Blueprint missing required 'bounds' field — raw: " + preview(rawJson));
        }
        JsonObject b = root.getAsJsonObject("bounds");
        int sizeX = requirePositiveInt(b, "sizeX", "bounds", rawJson);
        int sizeY = requirePositiveInt(b, "sizeY", "bounds", rawJson);
        int sizeZ = requirePositiveInt(b, "sizeZ", "bounds", rawJson);
        if (sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS) {
            throw new BlueprintParseException(
                    "bounds exceed maximum (" + MAX_AXIS + " per axis): "
                    + sizeX + "×" + sizeY + "×" + sizeZ);
        }
        return new Blueprint.Bounds(sizeX, sizeY, sizeZ);
    }

    // ---- Primitive parsing ----

    private static List<Primitive> parsePrimitives(JsonArray arr, boolean strict, String rawJson)
            throws BlueprintParseException {
        List<Primitive> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).isJsonObject()) {
                throw new BlueprintParseException(
                        "primitives[" + i + "] is not a JSON object — raw: " + preview(rawJson));
            }
            JsonObject obj = arr.get(i).getAsJsonObject();

            String id = requireString(obj, "id", "primitives[" + i + "]", rawJson);
            String type = requireString(obj, "type", "primitives[" + i + "]", rawJson);
            String on = obj.has("on") && !obj.get("on").isJsonNull()
                    ? obj.get("on").getAsString()
                    : null;

            if (!Primitive.KNOWN_TYPES.contains(type)) {
                if (strict) {
                    throw new BlueprintParseException(
                            "Unknown primitive type '" + type + "' at primitives[" + i + "]");
                }
                LOGGER.warn("BlueprintParser: unknown primitive type '{}' at index {}; skipping.", type, i);
                continue;
            }

            // Build the params JsonObject from all keys except the common ones.
            JsonObject params = new JsonObject();
            for (String key : obj.keySet()) {
                if (!key.equals("id") && !key.equals("type") && !key.equals("on")) {
                    params.add(key, obj.get(key));
                }
            }

            result.add(new Primitive(id, type, on, params));
        }
        return result;
    }

    // ---- Structural validation ----

    private static void validateStructure(List<Primitive> primitives, String rawJson)
            throws BlueprintParseException {
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < primitives.size(); i++) {
            Primitive p = primitives.get(i);

            // Unique id
            if (!seenIds.add(p.id)) {
                throw new BlueprintParseException(
                        "Duplicate primitive id '" + p.id + "' at index " + i
                        + " — raw: " + preview(rawJson));
            }

            // on references an earlier id
            if (p.on != null) {
                if (p.on.equals(p.id)) {
                    throw new BlueprintParseException(
                            "Primitive '" + p.id + "' references itself via 'on'.");
                }
                boolean found = false;
                for (int j = 0; j < i; j++) {
                    if (primitives.get(j).id.equals(p.on)) { found = true; break; }
                }
                if (!found) {
                    throw new BlueprintParseException(
                            "Primitive '" + p.id + "' has 'on: \"" + p.on + "\"' which "
                            + "does not reference any earlier-declared primitive id.");
                }
            }
        }

        // First primitive must have no on reference
        if (primitives.get(0).on != null) {
            throw new BlueprintParseException(
                    "The first primitive ('" + primitives.get(0).id + "') must not have "
                    + "an 'on' reference — it is the anchor.");
        }
    }

    // ---- Helpers ----

    private static int requirePositiveInt(JsonObject obj, String key, String ctx, String rawJson)
            throws BlueprintParseException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new BlueprintParseException(
                    ctx + " missing required int field '" + key + "' — raw: " + preview(rawJson));
        }
        int v = obj.get(key).getAsInt();
        if (v <= 0) {
            throw new BlueprintParseException(
                    ctx + " field '" + key + "' must be positive, got " + v);
        }
        return v;
    }

    private static String requireString(JsonObject obj, String key, String ctx, String rawJson)
            throws BlueprintParseException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new BlueprintParseException(
                    ctx + " missing required string field '" + key + "' — raw: " + preview(rawJson));
        }
        String v = obj.get(key).getAsString();
        if (v.isBlank()) {
            throw new BlueprintParseException(
                    ctx + " field '" + key + "' must not be blank — raw: " + preview(rawJson));
        }
        return v;
    }

    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }
        return s;
    }

    private static String preview(String text) {
        if (text == null) return "<null>";
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }
}
