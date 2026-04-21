package com.rayyan.tesseract.sandbox;

import java.util.HashMap;
import java.util.Map;

/**
 * Lexical scope chain. Lookups walk parent pointers until a binding is
 * found; assignments go into the innermost scope.
 *
 * <p>Function scopes are created with a parent pointing at the defining
 * scope (so closures resolve over the enclosing module's names). The
 * global scope's parent is {@code null}.
 */
final class Environment {

    private final Environment parent;
    private final Map<String, Object> bindings = new HashMap<>();

    Environment(Environment parent) { this.parent = parent; }

    Object get(String name) {
        Environment env = this;
        while (env != null) {
            if (env.bindings.containsKey(name)) return env.bindings.get(name);
            env = env.parent;
        }
        throw new SandboxError("name not defined: '" + name + "'");
    }

    boolean has(String name) {
        Environment env = this;
        while (env != null) {
            if (env.bindings.containsKey(name)) return true;
            env = env.parent;
        }
        return false;
    }

    void define(String name, Object value) {
        bindings.put(name, value);
    }

    /**
     * Assigns through to whichever scope already declares {@code name}.
     * If no ancestor defines it, the binding is created in this scope —
     * matching Python's "assignment declares in current function" rule.
     */
    void assign(String name, Object value) {
        Environment env = this;
        while (env != null) {
            if (env.bindings.containsKey(name)) { env.bindings.put(name, value); return; }
            env = env.parent;
        }
        bindings.put(name, value);
    }
}
