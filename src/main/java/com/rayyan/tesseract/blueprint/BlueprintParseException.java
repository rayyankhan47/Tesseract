package com.rayyan.tesseract.blueprint;

/**
 * Thrown by {@link BlueprintParser} when a blueprint JSON string cannot be
 * parsed or fails structural validation.
 */
public final class BlueprintParseException extends Exception {

    public BlueprintParseException(String message) {
        super(message);
    }

    public BlueprintParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
