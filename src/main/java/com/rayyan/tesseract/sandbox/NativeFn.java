package com.rayyan.tesseract.sandbox;

import java.util.List;

/**
 * Callable bound to a Java implementation and visible to sandbox scripts.
 * {@link Toolbox} bindings and built-ins ({@code len}, {@code range}, …)
 * are registered as {@code NativeFn} instances in the
 * {@link Interpreter}'s root {@link Environment}.
 *
 * <p>Arguments are already evaluated to Java objects before the call.
 * The callee is responsible for type-checking and may throw
 * {@link SandboxError} with a helpful message.
 */
@FunctionalInterface
interface NativeFn {
    Object call(List<Object> args);
}
