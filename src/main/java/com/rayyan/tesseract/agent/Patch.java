package com.rayyan.tesseract.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One entry in a {@link Critique#patch()} list.
 *
 * <p>Op types:
 * <ul>
 *   <li>{@code modify}  — change one field (dot-path) on an existing primitive</li>
 *   <li>{@code add}     — append a new primitive; full primitive JSON in {@link #primitive}</li>
 *   <li>{@code remove}  — delete a primitive by id</li>
 *   <li>{@code replace} — swap a primitive body in-place, preserving list position</li>
 * </ul>
 *
 * Deserialized by Gson; all fields are public and nullable.
 */
public final class Patch {

    /** Operation type: "modify" | "add" | "remove" | "replace". */
    public String op;

    /** Target primitive id. Required for modify, remove, replace; optional for add. */
    public String id;

    /**
     * Dot-path field name for {@code modify}, e.g. {@code "height"},
     * {@code "openings[0].u_offset"}, {@code "ridge_axis"}.
     */
    public String field;

    /**
     * New value for {@code modify}. May be a string, number, boolean, array,
     * or object depending on the field being patched.
     */
    public JsonElement value;

    /**
     * Full primitive JSON object for {@code add} and {@code replace}.
     * Serialized as a nested JSON object; Gson maps this to a JsonObject.
     */
    public JsonObject primitive;

    @Override
    public String toString() {
        return "Patch{op=" + op + ", id=" + id + ", field=" + field + "}";
    }
}
