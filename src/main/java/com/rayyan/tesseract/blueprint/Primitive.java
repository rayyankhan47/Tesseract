package com.rayyan.tesseract.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One entry in a {@link Blueprint#primitives()} list.
 *
 * <p>The {@code params} field holds the raw parsed JSON so the compiler can
 * read whatever fields each primitive type requires without a brittle class
 * hierarchy.  All read access goes through the typed convenience getters below,
 * which provide clear error messages when a required field is missing.
 *
 * <p>Known primitive types (validated by {@link BlueprintParser}):
 * {@code platform}, {@code walls}, {@code wall_segment},
 * {@code gable_roof}, {@code hip_roof}, {@code flat_roof},
 * {@code column}, {@code arch}, {@code staircase}, {@code frame}.
 */
public final class Primitive {

    /** All type names the compiler understands. */
    public static final Set<String> KNOWN_TYPES = Set.of(
            "platform", "walls", "wall_segment",
            "gable_roof", "hip_roof", "flat_roof",
            "column", "arch", "staircase", "frame"
    );

    // ---- Common fields ----

    /** Unique id within the blueprint, e.g. {@code "foundation"}. */
    public final String id;

    /** Primitive type name, one of {@link #KNOWN_TYPES}. */
    public final String type;

    /**
     * Id of the parent primitive, or {@code null}.
     * When non-null: y-origin inherits parent top face; x/z footprint
     * inherits parent footprint unless overridden by explicit params.
     */
    public final String on;

    /**
     * Raw params JSON — all fields beyond {@code id}, {@code type}, {@code on}
     * are stored here by the parser and read back by the compiler via the
     * typed getters below.
     */
    final JsonObject params;

    Primitive(String id, String type, String on, JsonObject params) {
        this.id     = id;
        this.type   = type;
        this.on     = on;
        this.params = params;
    }

    // -------------------------------------------------------------------------
    // Typed convenience getters
    // -------------------------------------------------------------------------

    /**
     * Returns the integer value of {@code key}, or {@code defaultValue} if
     * the key is absent or null.
     */
    public int getInt(String key, int defaultValue) {
        if (!params.has(key) || params.get(key).isJsonNull()) return defaultValue;
        return params.get(key).getAsInt();
    }

    /**
     * Returns the integer value of {@code key}.
     * @throws BlueprintFieldException if the key is absent or null.
     */
    public int requireInt(String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            throw new BlueprintFieldException(type, id, key, "required int field missing");
        }
        return params.get(key).getAsInt();
    }

    /**
     * Returns the string value of {@code key}, or {@code defaultValue} if
     * absent or null.
     */
    public String getString(String key, String defaultValue) {
        if (!params.has(key) || params.get(key).isJsonNull()) return defaultValue;
        return params.get(key).getAsString();
    }

    /**
     * Returns the string value of {@code key}.
     * @throws BlueprintFieldException if the key is absent or null.
     */
    public String requireString(String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            throw new BlueprintFieldException(type, id, key, "required string field missing");
        }
        return params.get(key).getAsString();
    }

    /**
     * Returns the boolean value of {@code key}, or {@code defaultValue} if
     * absent or null.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        if (!params.has(key) || params.get(key).isJsonNull()) return defaultValue;
        return params.get(key).getAsBoolean();
    }

    /**
     * Returns the {@code [x, y, z]} int-array for {@code key}.
     * @throws BlueprintFieldException if absent, null, or wrong length.
     */
    public int[] requireIntArray3(String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            throw new BlueprintFieldException(type, id, key, "required [x,y,z] array missing");
        }
        JsonArray arr = params.getAsJsonArray(key);
        if (arr.size() != 3) {
            throw new BlueprintFieldException(type, id, key,
                    "expected array of length 3, got " + arr.size());
        }
        return new int[]{ arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt() };
    }

    /**
     * Returns the integer array for {@code key}, or an empty array if absent.
     */
    public int[] getIntArray(String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return new int[0];
        JsonArray arr = params.getAsJsonArray(key);
        int[] result = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).getAsInt();
        return result;
    }

    /**
     * Returns the raw {@link JsonArray} for {@code key}, or an empty array if absent.
     */
    public JsonArray getArray(String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) return new JsonArray();
        return params.getAsJsonArray(key);
    }

    /**
     * Parses the {@code openings} array into a typed {@link List<Opening>}.
     * Entries with unknown faces or types are skipped with a logged warning.
     */
    public List<Opening> getOpenings() {
        JsonArray arr = getArray("openings");
        List<Opening> result = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String face = obj.has("face") ? obj.get("face").getAsString() : "south";
            if (!Opening.VALID_FACES.contains(face)) continue;
            String oType = obj.has("type") ? obj.get("type").getAsString() : "window";
            if (!Opening.VALID_TYPES.contains(oType)) continue;
            int uOffset = obj.has("u_offset") ? obj.get("u_offset").getAsInt() : 0;
            int vOffset = obj.has("v_offset") ? obj.get("v_offset").getAsInt() : 0;
            int width   = obj.has("width")    ? obj.get("width").getAsInt()    : 1;
            int height  = obj.has("height")   ? obj.get("height").getAsInt()   : 1;
            result.add(new Opening(face, uOffset, vOffset, width, height, oType));
        }
        return result;
    }

    @Override
    public String toString() {
        return "Primitive{id='" + id + "', type='" + type + "'" +
               (on != null ? ", on='" + on + "'" : "") + "}";
    }

    // -------------------------------------------------------------------------
    // Inner exception
    // -------------------------------------------------------------------------

    /** Thrown by typed getters when a required field is missing or wrong. */
    public static final class BlueprintFieldException extends RuntimeException {
        public BlueprintFieldException(String primitiveType, String primitiveId,
                                       String field, String detail) {
            super("Primitive[type=" + primitiveType + ", id=" + primitiveId + "] "
                  + "field '" + field + "': " + detail);
        }
    }
}
