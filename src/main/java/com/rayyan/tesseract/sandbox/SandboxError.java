package com.rayyan.tesseract.sandbox;

/**
 * Raised when a sandbox script misbehaves — parse errors, runtime type
 * errors, disallowed constructs, missing names. The L4 REPL catches
 * these and shows the message back to the LLM as feedback.
 *
 * <p>Distinct from {@link SandboxExceededError} so resource exhaustion
 * (which means "simplify") is easy to tell apart from logic bugs
 * (which means "rewrite").
 */
public class SandboxError extends RuntimeException {
    private final int line;

    public SandboxError(String message) {
        this(message, -1);
    }

    public SandboxError(String message, int line) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    public int line() { return line; }
}
