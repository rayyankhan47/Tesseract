package com.rayyan.tesseract.blueprint;

/**
 * Thrown by {@link BlueprintCompiler} on an irrecoverable compile error:
 * missing parent reference, cyclic dependency, unknown type, or empty output.
 */
public final class BlueprintCompileException extends Exception {

    public BlueprintCompileException(String message) {
        super(message);
    }

    public BlueprintCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
