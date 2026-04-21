package com.rayyan.tesseract.sandbox;

import com.rayyan.tesseract.agent.BlockOp;
import com.rayyan.tesseract.toolbox.CompositionOps;
import com.rayyan.tesseract.toolbox.Toolbox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the sandboxed script interpreter (§6.2).
 *
 * <p>Workflow:
 * <pre>{@code
 *   SandboxResult r = Sandbox.run(scriptSource, SandboxLimits.defaults());
 *   Set<BlockOp> ops = r.collectedOps(); // whatever the script emitted
 * }</pre>
 *
 * <p>Emission model: scripts interact with the build by calling
 * {@code emit(opSet)} with a toolbox result, or by returning a set
 * directly (captured as {@code r.returnValue}). {@code emit} unions
 * into the sandbox's accumulating op set so scripts can break work
 * into steps.
 */
public final class Sandbox {

    private Sandbox() {}

    public record SandboxResult(
            Set<BlockOp> collectedOps,
            Object returnValue,
            long stepsUsed,
            boolean completed,
            String diagnostic) {}

    /**
     * Runs the given Python-subset source under the default toolbox
     * bindings. Returns a {@link SandboxResult} whose {@code completed}
     * flag is {@code true} on a clean run and {@code false} if the
     * script raised a {@link SandboxError} (the message goes into
     * {@code diagnostic} for L4 feedback).
     */
    public static SandboxResult run(String source, SandboxLimits limits) {
        Environment globals = new Environment(null);
        BuiltIns.registerInto(globals);
        ToolboxBindings bindings = new ToolboxBindings();
        bindings.bind(globals);
        try {
            List<Token> tokens = new Lexer(source).tokenize();
            Ast.Module module = new Parser(tokens).parseModule();
            Interpreter interp = new Interpreter(globals, limits);
            interp.run(module);
            return new SandboxResult(bindings.ops(), null, interp.steps(), true, null);
        } catch (SandboxExceededError e) {
            return new SandboxResult(bindings.ops(), null, -1, false, "exceeded: " + e.getMessage());
        } catch (SandboxError e) {
            return new SandboxResult(bindings.ops(), null, -1, false, e.getMessage());
        }
    }

    /**
     * Registers the 14 toolbox primitives and an {@code emit} accumulator
     * into the sandbox environment. Keeps the accumulated ops inside
     * this instance so multiple sandbox runs don't bleed into each other.
     */
    static final class ToolboxBindings {
        private final Set<BlockOp> accumulated = new LinkedHashSet<>();

        Set<BlockOp> ops() { return accumulated; }

        void bind(Environment env) {
            env.define("emit", (NativeFn) args -> {
                BuiltInsGuard.requireArity("emit", args, 1);
                Set<BlockOp> ops = BuiltInsGuard.asOpSet(args.get(0));
                accumulated.addAll(ops);
                return (long) ops.size();
            });

            env.define("box", (NativeFn) args -> {
                BuiltInsGuard.requireArity("box", args, 7);
                return Toolbox.box(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asInt(args.get(5)),
                        BuiltInsGuard.asString(args.get(6)));
            });
            env.define("cylinder", (NativeFn) args -> {
                BuiltInsGuard.requireArity("cylinder", args, 6);
                return Toolbox.cylinder(
                        BuiltInsGuard.asDouble(args.get(0)), BuiltInsGuard.asDouble(args.get(1)),
                        BuiltInsGuard.asInt(args.get(2)), BuiltInsGuard.asInt(args.get(3)),
                        BuiltInsGuard.asDouble(args.get(4)), BuiltInsGuard.asString(args.get(5)));
            });
            env.define("pyramid", (NativeFn) args -> {
                BuiltInsGuard.requireArity("pyramid", args, 6);
                return Toolbox.pyramid(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)),
                        BuiltInsGuard.asInt(args.get(2)), BuiltInsGuard.asInt(args.get(3)),
                        BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asString(args.get(5)));
            });
            env.define("sphere", (NativeFn) args -> {
                BuiltInsGuard.requireArity("sphere", args, 5);
                return Toolbox.sphere(
                        BuiltInsGuard.asDouble(args.get(0)), BuiltInsGuard.asDouble(args.get(1)),
                        BuiltInsGuard.asDouble(args.get(2)),
                        BuiltInsGuard.asDouble(args.get(3)), BuiltInsGuard.asString(args.get(4)));
            });
            env.define("walls", (NativeFn) args -> {
                BuiltInsGuard.requireArity("walls", args, 7);
                return Toolbox.walls(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asInt(args.get(5)),
                        BuiltInsGuard.asString(args.get(6)));
            });
            env.define("frame", (NativeFn) args -> {
                BuiltInsGuard.requireArity("frame", args, 7);
                return Toolbox.frame(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asInt(args.get(5)),
                        BuiltInsGuard.asString(args.get(6)));
            });
            env.define("line", (NativeFn) args -> {
                BuiltInsGuard.requireArity("line", args, 7);
                return Toolbox.line(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asInt(args.get(5)),
                        BuiltInsGuard.asString(args.get(6)));
            });
            env.define("arc", (NativeFn) args -> {
                BuiltInsGuard.requireArity("arc", args, 8);
                return Toolbox.arc(
                        BuiltInsGuard.asDouble(args.get(0)), BuiltInsGuard.asDouble(args.get(1)),
                        BuiltInsGuard.asDouble(args.get(2)), BuiltInsGuard.asDouble(args.get(3)),
                        BuiltInsGuard.asDouble(args.get(4)), BuiltInsGuard.asDouble(args.get(5)),
                        BuiltInsGuard.asAxisChar(args.get(6)), BuiltInsGuard.asString(args.get(7)));
            });
            env.define("crenellate", (NativeFn) args -> {
                BuiltInsGuard.requireArity("crenellate", args, 4);
                return Toolbox.crenellate(
                        BuiltInsGuard.asOpSet(args.get(0)),
                        BuiltInsGuard.asInt(args.get(1)),
                        BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asString(args.get(3)));
            });
            env.define("scatter", (NativeFn) args -> {
                BuiltInsGuard.requireArity("scatter", args, 9);
                return Toolbox.scatter(
                        BuiltInsGuard.asInt(args.get(0)), BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)), BuiltInsGuard.asInt(args.get(5)),
                        BuiltInsGuard.asDouble(args.get(6)), BuiltInsGuard.asLong(args.get(7)),
                        BuiltInsGuard.asString(args.get(8)));
            });
            env.define("repeat", (NativeFn) args -> {
                BuiltInsGuard.requireArity("repeat", args, 5);
                return CompositionOps.repeat(
                        BuiltInsGuard.asOpSet(args.get(0)),
                        BuiltInsGuard.asInt(args.get(1)), BuiltInsGuard.asInt(args.get(2)),
                        BuiltInsGuard.asInt(args.get(3)), BuiltInsGuard.asInt(args.get(4)));
            });
            env.define("mirror", (NativeFn) args -> {
                BuiltInsGuard.requireArity("mirror", args, 3);
                return CompositionOps.mirror(
                        BuiltInsGuard.asOpSet(args.get(0)),
                        BuiltInsGuard.asAxisChar(args.get(1)),
                        BuiltInsGuard.asInt(args.get(2)));
            });
            env.define("subtract", (NativeFn) args -> {
                BuiltInsGuard.requireArity("subtract", args, 2);
                return CompositionOps.subtract(
                        BuiltInsGuard.asOpSet(args.get(0)),
                        BuiltInsGuard.asOpSet(args.get(1)));
            });
            env.define("intersect", (NativeFn) args -> {
                BuiltInsGuard.requireArity("intersect", args, 2);
                return CompositionOps.intersect(
                        BuiltInsGuard.asOpSet(args.get(0)),
                        BuiltInsGuard.asOpSet(args.get(1)));
            });
        }
    }

    /**
     * Centralised type-coercion helpers shared by the toolbox bindings.
     * Kept private because they know about both sandbox values and
     * toolbox types and we don't want either to leak.
     */
    private static final class BuiltInsGuard {
        static void requireArity(String name, List<Object> args, int n) {
            if (args.size() != n) {
                throw new SandboxError(name + "() expected " + n + " args, got " + args.size());
            }
        }

        static int asInt(Object v) {
            if (v instanceof Long n) return Math.toIntExact(n);
            if (v instanceof Double d) return (int) (double) d;
            throw new SandboxError("expected int, got " + Interpreter.typeName(v));
        }

        static long asLong(Object v) {
            if (v instanceof Long n) return n;
            if (v instanceof Double d) return (long) (double) d;
            throw new SandboxError("expected int, got " + Interpreter.typeName(v));
        }

        static double asDouble(Object v) {
            if (v instanceof Long n) return n;
            if (v instanceof Double n) return n;
            throw new SandboxError("expected number, got " + Interpreter.typeName(v));
        }

        static String asString(Object v) {
            if (v instanceof String s) return s;
            throw new SandboxError("expected string, got " + Interpreter.typeName(v));
        }

        static char asAxisChar(Object v) {
            if (v instanceof String s && s.length() == 1) {
                char c = Character.toUpperCase(s.charAt(0));
                if (c == 'X' || c == 'Y' || c == 'Z') return c;
            }
            throw new SandboxError("expected axis 'X'/'Y'/'Z', got " + v);
        }

        @SuppressWarnings("unchecked")
        static Set<BlockOp> asOpSet(Object v) {
            if (v instanceof Set<?> s) {
                Set<BlockOp> out = new LinkedHashSet<>();
                for (Object o : s) {
                    if (o instanceof BlockOp op) out.add(op);
                    else throw new SandboxError("expected BlockOp in set, got " + Interpreter.typeName(o));
                }
                return out;
            }
            if (v instanceof List<?> list) {
                Set<BlockOp> out = new LinkedHashSet<>();
                for (Object o : list) {
                    if (o instanceof BlockOp op) out.add(op);
                    else throw new SandboxError("expected BlockOp in list, got " + Interpreter.typeName(o));
                }
                return out;
            }
            throw new SandboxError("expected op set, got " + Interpreter.typeName(v));
        }
    }

    /** Convenience re-export — used by callers that want an empty-ops placeholder. */
    public static Set<BlockOp> emptyOps() { return new LinkedHashSet<>(); }

    /** Convenience re-export — shallow copy of the final op list. */
    public static List<BlockOp> toList(Set<BlockOp> ops) {
        return ops == null ? List.of() : new ArrayList<>(ops);
    }
}
