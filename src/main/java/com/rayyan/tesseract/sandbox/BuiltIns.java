package com.rayyan.tesseract.sandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in Python-like functions registered into the root environment:
 * {@code len, range, abs, min, max, round, int, float, str, print,
 * sorted, sum, any, all, enumerate, append}.
 *
 * <p>{@code append(list, v)} and the other container helpers exist in
 * function form because attribute access ({@code list.append(v)}) is
 * intentionally disallowed by the parser — see §6.2.1.
 */
final class BuiltIns {

    private BuiltIns() {}

    static void registerInto(Environment env) {
        env.define("len", (NativeFn) args -> {
            requireArity("len", args, 1);
            Object v = args.get(0);
            if (v instanceof String s) return (long) s.length();
            if (v instanceof List<?> l) return (long) l.size();
            if (v instanceof Map<?, ?> m) return (long) m.size();
            throw new SandboxError("len() argument must be str/list/dict");
        });

        env.define("range", (NativeFn) args -> {
            long start = 0, stop, step = 1;
            if (args.size() == 1) stop = asLong(args.get(0));
            else if (args.size() == 2) { start = asLong(args.get(0)); stop = asLong(args.get(1)); }
            else if (args.size() == 3) {
                start = asLong(args.get(0));
                stop = asLong(args.get(1));
                step = asLong(args.get(2));
            } else throw new SandboxError("range() takes 1–3 args");
            if (step == 0) throw new SandboxError("range() step must be non-zero");
            List<Object> out = new ArrayList<>();
            if (step > 0) for (long i = start; i < stop; i += step) out.add(i);
            else           for (long i = start; i > stop; i += step) out.add(i);
            if (out.size() > 1_000_000) {
                throw new SandboxExceededError(SandboxExceededError.Kind.MEMORY,
                        "range() would produce " + out.size() + " values");
            }
            return out;
        });

        env.define("abs", (NativeFn) args -> {
            requireArity("abs", args, 1);
            Object v = args.get(0);
            if (v instanceof Long n) return Math.abs(n);
            if (v instanceof Double n) return Math.abs(n);
            throw new SandboxError("abs() argument must be a number");
        });

        env.define("min", (NativeFn) args -> reduceMinMax(args, true));
        env.define("max", (NativeFn) args -> reduceMinMax(args, false));

        env.define("round", (NativeFn) args -> {
            if (args.size() < 1 || args.size() > 2) throw new SandboxError("round() takes 1 or 2 args");
            double d = asDouble(args.get(0));
            int ndigits = args.size() == 2 ? (int) asLong(args.get(1)) : 0;
            if (ndigits == 0) return (long) Math.round(d);
            double f = Math.pow(10, ndigits);
            return Math.round(d * f) / f;
        });

        env.define("int", (NativeFn) args -> {
            requireArity("int", args, 1);
            Object v = args.get(0);
            if (v instanceof Long n) return n;
            if (v instanceof Double n) return (long) (double) n;
            if (v instanceof Boolean b) return b ? 1L : 0L;
            if (v instanceof String s) return Long.parseLong(s.trim());
            throw new SandboxError("cannot convert to int: " + Interpreter.typeName(v));
        });

        env.define("float", (NativeFn) args -> {
            requireArity("float", args, 1);
            Object v = args.get(0);
            if (v instanceof Long n) return (double) n;
            if (v instanceof Double n) return n;
            if (v instanceof Boolean b) return b ? 1.0 : 0.0;
            if (v instanceof String s) return Double.parseDouble(s.trim());
            throw new SandboxError("cannot convert to float: " + Interpreter.typeName(v));
        });

        env.define("str", (NativeFn) args -> {
            requireArity("str", args, 1);
            Object v = args.get(0);
            if (v == null) return "None";
            if (v instanceof Boolean b) return b ? "True" : "False";
            return String.valueOf(v);
        });

        env.define("bool", (NativeFn) args -> {
            requireArity("bool", args, 1);
            return Interpreter.truthy(args.get(0));
        });

        env.define("print", (NativeFn) args -> {
            // print() is captured-only; we don't actually write to
            // stdout to keep the sandbox silent. The REPL can choose
            // to grab it via a custom binding instead.
            return null;
        });

        env.define("sum", (NativeFn) args -> {
            requireArity("sum", args, 1);
            if (!(args.get(0) instanceof List<?> list)) {
                throw new SandboxError("sum() argument must be a list");
            }
            boolean anyFloat = false;
            double dd = 0.0;
            long ll = 0;
            for (Object e : list) {
                if (e instanceof Long n) { ll += n; dd += n; }
                else if (e instanceof Double n) { anyFloat = true; dd += n; }
                else throw new SandboxError("sum() item must be numeric");
            }
            return anyFloat ? (Object) dd : (Object) ll;
        });

        env.define("any", (NativeFn) args -> {
            requireArity("any", args, 1);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("any() needs a list");
            for (Object e : list) if (Interpreter.truthy(e)) return true;
            return false;
        });

        env.define("all", (NativeFn) args -> {
            requireArity("all", args, 1);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("all() needs a list");
            for (Object e : list) if (!Interpreter.truthy(e)) return false;
            return true;
        });

        env.define("sorted", (NativeFn) args -> {
            requireArity("sorted", args, 1);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("sorted() needs a list");
            List<Object> copy = new ArrayList<>(list);
            copy.sort((a, b) -> {
                if (a instanceof Number na && b instanceof Number nb) {
                    return Double.compare(na.doubleValue(), nb.doubleValue());
                }
                if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
                throw new SandboxError("sorted() cannot order mixed types");
            });
            return copy;
        });

        env.define("enumerate", (NativeFn) args -> {
            requireArity("enumerate", args, 1);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("enumerate() needs a list");
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                List<Object> pair = new ArrayList<>(2);
                pair.add((long) i); pair.add(list.get(i));
                out.add(pair);
            }
            return out;
        });

        env.define("append", (NativeFn) args -> {
            requireArity("append", args, 2);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("append() first arg must be list");
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
            l.add(args.get(1));
            return null;
        });

        env.define("extend", (NativeFn) args -> {
            requireArity("extend", args, 2);
            if (!(args.get(0) instanceof List<?> list)) throw new SandboxError("extend() first arg must be list");
            if (!(args.get(1) instanceof List<?> other)) throw new SandboxError("extend() second arg must be list");
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
            l.addAll(other);
            return null;
        });

        env.define("keys", (NativeFn) args -> {
            requireArity("keys", args, 1);
            if (!(args.get(0) instanceof Map<?, ?> m)) throw new SandboxError("keys() argument must be a dict");
            return new ArrayList<>(m.keySet());
        });

        env.define("values", (NativeFn) args -> {
            requireArity("values", args, 1);
            if (!(args.get(0) instanceof Map<?, ?> m)) throw new SandboxError("values() argument must be a dict");
            return new ArrayList<>(m.values());
        });

        env.define("items", (NativeFn) args -> {
            requireArity("items", args, 1);
            if (!(args.get(0) instanceof Map<?, ?> m)) throw new SandboxError("items() argument must be a dict");
            List<Object> out = new ArrayList<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                List<Object> pair = new ArrayList<>(2);
                pair.add(e.getKey()); pair.add(e.getValue());
                out.add(pair);
            }
            return out;
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Object reduceMinMax(List<Object> args, boolean isMin) {
        if (args.isEmpty()) throw new SandboxError((isMin ? "min" : "max") + "() needs at least one arg");
        List<Object> items;
        if (args.size() == 1 && args.get(0) instanceof List<?> l) {
            items = new ArrayList<>(l);
        } else {
            items = args;
        }
        if (items.isEmpty()) throw new SandboxError((isMin ? "min" : "max") + "() arg is empty");
        Object best = items.get(0);
        for (int i = 1; i < items.size(); i++) {
            int cmp;
            Object cur = items.get(i);
            if (best instanceof Number nb && cur instanceof Number nc) {
                cmp = Double.compare(nc.doubleValue(), nb.doubleValue());
            } else if (best instanceof String sb && cur instanceof String sc) {
                cmp = sc.compareTo(sb);
            } else {
                throw new SandboxError((isMin ? "min" : "max") + "() cannot order mixed types");
            }
            if ((isMin && cmp < 0) || (!isMin && cmp > 0)) best = cur;
        }
        return best;
    }

    private static void requireArity(String name, List<Object> args, int n) {
        if (args.size() != n) {
            throw new SandboxError(name + "() expected " + n + " args, got " + args.size());
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Long n) return n;
        if (v instanceof Integer n) return n;
        if (v instanceof Double d) return (long) (double) d;
        throw new SandboxError("expected int, got " + Interpreter.typeName(v));
    }

    private static double asDouble(Object v) {
        if (v instanceof Long n) return n;
        if (v instanceof Double n) return n;
        throw new SandboxError("expected number, got " + Interpreter.typeName(v));
    }
}
