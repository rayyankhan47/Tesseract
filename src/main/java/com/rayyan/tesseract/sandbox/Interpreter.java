package com.rayyan.tesseract.sandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tree-walking interpreter for the sandbox AST (§6.2). Enforces
 * per-run resource ceilings via {@code step()} on every node visit and
 * a wall-clock check on every statement.
 *
 * <p>Values are stock Java types:
 * <ul>
 *   <li>{@code Long} for integers, {@code Double} for floats</li>
 *   <li>{@code String} for strings</li>
 *   <li>{@code Boolean}</li>
 *   <li>{@code null} for {@code None}</li>
 *   <li>{@code ArrayList<Object>} for lists and tuples (tuples are
 *       tagged immutable via a wrapper only when they reach native
 *       code; inside scripts, assignment is rejected elsewhere)</li>
 *   <li>{@code LinkedHashMap<Object, Object>} for dicts</li>
 *   <li>{@link NativeFn} or {@link UserFn} for callables</li>
 * </ul>
 *
 * <p>Attribute access is rejected at the parser level — there is no
 * {@code .} dispatch in this interpreter by design.
 */
final class Interpreter {

    /** Control-flow signal for {@code return}. */
    static final class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { super(null, null, false, false); this.value = value; }
    }
    static final class BreakSignal extends RuntimeException {
        BreakSignal() { super(null, null, false, false); }
    }
    static final class ContinueSignal extends RuntimeException {
        ContinueSignal() { super(null, null, false, false); }
    }

    record UserFn(String name, List<String> params, List<Ast.Stmt> body, Environment closure)
            implements java.io.Serializable {}

    private final Environment globals;
    private final long maxSteps;
    private final long wallClockDeadlineNs;
    private final int maxCollectionSize;
    private final int maxLoopIterations;
    private long steps;

    Interpreter(Environment globals, SandboxLimits limits) {
        this.globals = globals;
        this.maxSteps = limits.maxSteps();
        this.wallClockDeadlineNs = System.nanoTime() + limits.maxWallMs() * 1_000_000L;
        this.maxCollectionSize = limits.maxCollectionSize();
        this.maxLoopIterations = limits.maxLoopIterations();
    }

    Environment globals() { return globals; }

    void run(Ast.Module module) {
        for (Ast.Stmt s : module.body()) execStmt(s, globals);
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    private void execStmt(Ast.Stmt stmt, Environment env) {
        step();
        if (System.nanoTime() > wallClockDeadlineNs) {
            throw new SandboxExceededError(SandboxExceededError.Kind.TIME,
                    "wall-clock limit exceeded at line " + stmt.line());
        }
        if (stmt instanceof Ast.PassStmt) return;
        if (stmt instanceof Ast.BreakStmt) throw new BreakSignal();
        if (stmt instanceof Ast.ContinueStmt) throw new ContinueSignal();
        if (stmt instanceof Ast.ExprStmt e) { evalExpr(e.expr(), env); return; }
        if (stmt instanceof Ast.Assign a) { doAssign(a.target(), evalExpr(a.value(), env), env); return; }
        if (stmt instanceof Ast.AugAssign a) {
            Object cur = evalExpr(a.target(), env);
            Object rhs = evalExpr(a.value(), env);
            String baseOp = a.op().substring(0, a.op().length() - 1);
            Object result = binop(baseOp, cur, rhs, a.line());
            doAssign(a.target(), result, env);
            return;
        }
        if (stmt instanceof Ast.ReturnStmt r) {
            Object v = r.value() == null ? null : evalExpr(r.value(), env);
            throw new ReturnSignal(v);
        }
        if (stmt instanceof Ast.IfStmt i) { execIf(i, env); return; }
        if (stmt instanceof Ast.WhileStmt w) { execWhile(w, env); return; }
        if (stmt instanceof Ast.ForStmt f) { execFor(f, env); return; }
        if (stmt instanceof Ast.FuncDef def) {
            env.define(def.name(), new UserFn(def.name(), def.params(), def.body(), env));
            return;
        }
        throw new SandboxError("unsupported statement " + stmt.getClass().getSimpleName(), stmt.line());
    }

    private void execIf(Ast.IfStmt stmt, Environment env) {
        for (int i = 0; i < stmt.conditions().size(); i++) {
            if (truthy(evalExpr(stmt.conditions().get(i), env))) {
                for (Ast.Stmt s : stmt.branches().get(i)) execStmt(s, env);
                return;
            }
        }
        if (stmt.elseBranch() != null) {
            for (Ast.Stmt s : stmt.elseBranch()) execStmt(s, env);
        }
    }

    private void execWhile(Ast.WhileStmt stmt, Environment env) {
        int iterations = 0;
        while (truthy(evalExpr(stmt.condition(), env))) {
            if (++iterations > maxLoopIterations) {
                throw new SandboxExceededError(SandboxExceededError.Kind.ITERATIONS,
                        "while-loop exceeded " + maxLoopIterations + " iterations at line " + stmt.line());
            }
            try {
                for (Ast.Stmt s : stmt.body()) execStmt(s, env);
            } catch (BreakSignal b) { return; }
            catch (ContinueSignal c) { /* next iteration */ }
        }
    }

    private void execFor(Ast.ForStmt stmt, Environment env) {
        Object iterable = evalExpr(stmt.iter(), env);
        int iterations = 0;
        for (Object item : toIterable(iterable, stmt.line())) {
            if (++iterations > maxLoopIterations) {
                throw new SandboxExceededError(SandboxExceededError.Kind.ITERATIONS,
                        "for-loop exceeded " + maxLoopIterations + " iterations at line " + stmt.line());
            }
            env.assign(stmt.var(), item);
            try {
                for (Ast.Stmt s : stmt.body()) execStmt(s, env);
            } catch (BreakSignal b) { return; }
            catch (ContinueSignal c) { /* next iteration */ }
        }
    }

    private void doAssign(Ast.Expr target, Object value, Environment env) {
        if (target instanceof Ast.NameRef nr) {
            env.assign(nr.name(), value);
            return;
        }
        if (target instanceof Ast.Index idx) {
            Object container = evalExpr(idx.target(), env);
            Object key = evalExpr(idx.index(), env);
            if (container instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) list;
                int i = asInt(key, idx.line());
                if (i < 0) i += l.size();
                if (i < 0 || i >= l.size()) {
                    throw new SandboxError("list index out of range: " + i, idx.line());
                }
                l.set(i, value);
                return;
            }
            if (container instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = (Map<Object, Object>) map;
                m.put(key, value);
                return;
            }
            throw new SandboxError("cannot index into " + typeName(container), idx.line());
        }
        throw new SandboxError("invalid assignment target", target.line());
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    Object evalExpr(Ast.Expr expr, Environment env) {
        step();
        if (expr instanceof Ast.NumLit n) return n.value();
        if (expr instanceof Ast.StrLit s) return s.value();
        if (expr instanceof Ast.BoolLit b) return b.value();
        if (expr instanceof Ast.NoneLit) return null;
        if (expr instanceof Ast.NameRef n) return env.get(n.name());
        if (expr instanceof Ast.BinOp b) {
            return binop(b.op(), evalExpr(b.left(), env), evalExpr(b.right(), env), b.line());
        }
        if (expr instanceof Ast.UnaryOp u) {
            return unaryop(u.op(), evalExpr(u.operand(), env), u.line());
        }
        if (expr instanceof Ast.BoolOp bo) return boolop(bo, env);
        if (expr instanceof Ast.Compare c) return compare(c, env);
        if (expr instanceof Ast.Call c) return doCall(c, env);
        if (expr instanceof Ast.Index i) return doIndex(i, env);
        if (expr instanceof Ast.ListLit l) {
            checkCollection(l.items().size(), l.line());
            List<Object> out = new ArrayList<>(l.items().size());
            for (Ast.Expr e : l.items()) out.add(evalExpr(e, env));
            return out;
        }
        if (expr instanceof Ast.DictLit d) {
            checkCollection(d.keys().size(), d.line());
            Map<Object, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < d.keys().size(); i++) {
                out.put(evalExpr(d.keys().get(i), env), evalExpr(d.values().get(i), env));
            }
            return out;
        }
        if (expr instanceof Ast.TupleLit t) {
            checkCollection(t.items().size(), t.line());
            List<Object> out = new ArrayList<>(t.items().size());
            for (Ast.Expr e : t.items()) out.add(evalExpr(e, env));
            return out;
        }
        throw new SandboxError("unsupported expression " + expr.getClass().getSimpleName(), expr.line());
    }

    private Object boolop(Ast.BoolOp op, Environment env) {
        if (op.op().equals("and")) {
            Object last = true;
            for (Ast.Expr e : op.operands()) {
                last = evalExpr(e, env);
                if (!truthy(last)) return last;
            }
            return last;
        }
        // or
        Object last = false;
        for (Ast.Expr e : op.operands()) {
            last = evalExpr(e, env);
            if (truthy(last)) return last;
        }
        return last;
    }

    private Object compare(Ast.Compare c, Environment env) {
        Object prev = evalExpr(c.operands().get(0), env);
        for (int i = 0; i < c.ops().size(); i++) {
            Object cur = evalExpr(c.operands().get(i + 1), env);
            if (!compareOne(c.ops().get(i), prev, cur, c.line())) return false;
            prev = cur;
        }
        return true;
    }

    private Object doCall(Ast.Call call, Environment env) {
        Object target = evalExpr(call.target(), env);
        List<Object> args = new ArrayList<>(call.args().size());
        for (Ast.Expr a : call.args()) args.add(evalExpr(a, env));
        if (target instanceof NativeFn fn) return fn.call(args);
        if (target instanceof UserFn fn) {
            if (args.size() != fn.params().size()) {
                throw new SandboxError(
                        fn.name() + "() expected " + fn.params().size() + " args, got " + args.size(),
                        call.line());
            }
            Environment child = new Environment(fn.closure());
            for (int i = 0; i < args.size(); i++) child.define(fn.params().get(i), args.get(i));
            try {
                for (Ast.Stmt s : fn.body()) execStmt(s, child);
            } catch (ReturnSignal r) {
                return r.value;
            }
            return null;
        }
        throw new SandboxError("not callable: " + typeName(target), call.line());
    }

    private Object doIndex(Ast.Index idx, Environment env) {
        Object target = evalExpr(idx.target(), env);
        Object key = evalExpr(idx.index(), env);
        if (target instanceof List<?> list) {
            int i = asInt(key, idx.line());
            if (i < 0) i += list.size();
            if (i < 0 || i >= list.size()) {
                throw new SandboxError("list index out of range: " + i, idx.line());
            }
            return list.get(i);
        }
        if (target instanceof String s) {
            int i = asInt(key, idx.line());
            if (i < 0) i += s.length();
            if (i < 0 || i >= s.length()) {
                throw new SandboxError("string index out of range: " + i, idx.line());
            }
            return String.valueOf(s.charAt(i));
        }
        if (target instanceof Map<?, ?> map) {
            Object v = map.get(key);
            if (v == null && !map.containsKey(key)) {
                throw new SandboxError("key not in dict: " + repr(key), idx.line());
            }
            return v;
        }
        throw new SandboxError("cannot index into " + typeName(target), idx.line());
    }

    // -------------------------------------------------------------------------
    // Operators
    // -------------------------------------------------------------------------

    private Object binop(String op, Object a, Object b, int line) {
        if (op.equals("+") && a instanceof String sa && b instanceof String sb) return sa + sb;
        if (op.equals("+") && a instanceof List<?> la && b instanceof List<?> lb) {
            List<Object> out = new ArrayList<>(la);
            @SuppressWarnings("unchecked") List<Object> lbo = (List<Object>) lb;
            out.addAll(lbo);
            checkCollection(out.size(), line);
            return out;
        }
        if (op.equals("*") && a instanceof String s && b instanceof Long n) return s.repeat(n.intValue());
        if (op.equals("*") && a instanceof Long n && b instanceof String s) return s.repeat(n.intValue());
        if (op.equals("*") && a instanceof List<?> la && b instanceof Long n) {
            List<Object> out = new ArrayList<>((int) Math.max(0, la.size() * n));
            for (long i = 0; i < n; i++) out.addAll(la);
            checkCollection(out.size(), line);
            return out;
        }

        if (!(a instanceof Number) || !(b instanceof Number)) {
            throw new SandboxError("unsupported operand: " + typeName(a) + " " + op + " " + typeName(b), line);
        }

        boolean anyFloat = a instanceof Double || b instanceof Double;
        double da = ((Number) a).doubleValue();
        double db = ((Number) b).doubleValue();

        switch (op) {
            case "+": return anyFloat ? (Object) (da + db) : (Object) (longOf(a) + longOf(b));
            case "-": return anyFloat ? (Object) (da - db) : (Object) (longOf(a) - longOf(b));
            case "*": return anyFloat ? (Object) (da * db) : (Object) (longOf(a) * longOf(b));
            case "/":
                if (db == 0.0) throw new SandboxError("division by zero", line);
                return da / db;
            case "//":
                if (db == 0.0) throw new SandboxError("floor division by zero", line);
                if (anyFloat) return Math.floor(da / db);
                return (long) Math.floorDiv(longOf(a), longOf(b));
            case "%":
                if (db == 0.0) throw new SandboxError("modulo by zero", line);
                if (anyFloat) return da - Math.floor(da / db) * db;
                return (long) Math.floorMod(longOf(a), longOf(b));
            case "**":
                return anyFloat ? (Object) Math.pow(da, db) : (Object) longPow(longOf(a), longOf(b), line);
            default: throw new SandboxError("unknown binary operator '" + op + "'", line);
        }
    }

    private Object unaryop(String op, Object v, int line) {
        if (op.equals("-")) {
            if (v instanceof Long n) return -n;
            if (v instanceof Double n) return -n;
            throw new SandboxError("cannot negate " + typeName(v), line);
        }
        if (op.equals("+")) {
            if (v instanceof Number) return v;
            throw new SandboxError("cannot apply + to " + typeName(v), line);
        }
        if (op.equals("not")) return !truthy(v);
        throw new SandboxError("unknown unary operator '" + op + "'", line);
    }

    private boolean compareOne(String op, Object a, Object b, int line) {
        switch (op) {
            case "==": return valueEquals(a, b);
            case "!=": return !valueEquals(a, b);
            case "<":  return numCompare(a, b, line) <  0;
            case "<=": return numCompare(a, b, line) <= 0;
            case ">":  return numCompare(a, b, line) >  0;
            case ">=": return numCompare(a, b, line) >= 0;
            case "in": return containsValue(b, a, line);
            case "not in": return !containsValue(b, a, line);
            default: throw new SandboxError("unknown comparison '" + op + "'", line);
        }
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    private static int numCompare(Object a, Object b, int line) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        throw new SandboxError("cannot order " + typeName(a) + " and " + typeName(b), line);
    }

    private static boolean containsValue(Object container, Object item, int line) {
        if (container instanceof List<?> l) {
            for (Object e : l) if (valueEquals(e, item)) return true;
            return false;
        }
        if (container instanceof Map<?, ?> m) return m.containsKey(item);
        if (container instanceof String s && item instanceof String it) return s.contains(it);
        throw new SandboxError("'in' not supported on " + typeName(container), line);
    }

    // -------------------------------------------------------------------------
    // Conversion helpers
    // -------------------------------------------------------------------------

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Long n) return n != 0;
        if (v instanceof Double n) return n != 0.0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    static int asInt(Object v, int line) {
        if (v instanceof Long n) return Math.toIntExact(n);
        if (v instanceof Integer n) return n;
        if (v instanceof Double d && d == Math.floor(d)) return (int) (double) d;
        throw new SandboxError("expected int, got " + typeName(v), line);
    }

    @SuppressWarnings("unchecked")
    static Iterable<Object> toIterable(Object v, int line) {
        if (v instanceof List<?> l) return (Iterable<Object>) l;
        if (v instanceof Map<?, ?> m) return (Iterable<Object>) m.keySet();
        if (v instanceof String s) {
            List<Object> out = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
            return out;
        }
        throw new SandboxError("cannot iterate over " + typeName(v), line);
    }

    static String typeName(Object v) {
        if (v == null) return "NoneType";
        if (v instanceof Long) return "int";
        if (v instanceof Double) return "float";
        if (v instanceof String) return "str";
        if (v instanceof Boolean) return "bool";
        if (v instanceof List<?>) return "list";
        if (v instanceof Map<?, ?>) return "dict";
        if (v instanceof NativeFn) return "builtin_function";
        if (v instanceof UserFn) return "function";
        return v.getClass().getSimpleName();
    }

    static String repr(Object v) {
        if (v == null) return "None";
        if (v instanceof String s) return "'" + s + "'";
        return String.valueOf(v);
    }

    private static long longOf(Object v) {
        if (v instanceof Long n) return n;
        if (v instanceof Integer n) return n;
        if (v instanceof Double d) return (long) (double) d;
        return ((Number) v).longValue();
    }

    private long longPow(long base, long exp, int line) {
        if (exp < 0) throw new SandboxError("negative integer power not supported", line);
        long out = 1;
        long b = base;
        while (exp > 0) {
            if ((exp & 1) == 1) out = Math.multiplyExact(out, b);
            exp >>= 1;
            if (exp > 0) b = Math.multiplyExact(b, b);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Resource bookkeeping
    // -------------------------------------------------------------------------

    private void step() {
        if (++steps > maxSteps) {
            throw new SandboxExceededError(SandboxExceededError.Kind.STEPS,
                    "AST step budget exceeded (" + maxSteps + ")");
        }
    }

    private void checkCollection(int size, int line) {
        if (size > maxCollectionSize) {
            throw new SandboxExceededError(SandboxExceededError.Kind.MEMORY,
                    "collection size " + size + " exceeds limit " + maxCollectionSize + " at line " + line);
        }
    }

    long steps() { return steps; }
}
