package com.rayyan.tesseract.sandbox;

/**
 * Raised when a script overruns a resource ceiling — AST steps, memory,
 * or wall clock (§6.2.2). L4 handles this as "script too expensive,
 * simplify" and prompts the LLM to reduce loop bounds or break work
 * into smaller scripts.
 */
public final class SandboxExceededError extends SandboxError {
    public enum Kind { STEPS, MEMORY, TIME, ITERATIONS }

    private final Kind kind;

    public SandboxExceededError(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
