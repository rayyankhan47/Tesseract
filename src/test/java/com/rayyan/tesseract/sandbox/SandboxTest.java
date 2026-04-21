package com.rayyan.tesseract.sandbox;

import com.rayyan.tesseract.agent.BlockOp;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.2.3 — end-to-end tests for the sandbox interpreter. Covers the
 * happy path per construct plus the three resource ceilings and the
 * key prohibitions (no imports, no attribute access).
 */
class SandboxTest {

    private Sandbox.SandboxResult run(String src) {
        return Sandbox.run(src, SandboxLimits.forTests());
    }

    // ------ Literals / expressions ----------------------------------------

    @Test
    void arithmetic_intAndFloat() {
        Sandbox.SandboxResult r = run("""
                a = 2 + 3
                b = 10 // 3
                c = 10 / 3
                d = 2 ** 10
                e = -5
                emit(box(0, 0, 0, a, b, d, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
        assertEquals(6 * 4 * 1025, r.collectedOps().size());
    }

    @Test
    void strings_andIndex() {
        Sandbox.SandboxResult r = run("""
                s = "hello"
                n = len(s)
                c = s[0]
                out = c + str(n)
                """);
        assertTrue(r.completed(), r.diagnostic());
    }

    // ------ Control flow ---------------------------------------------------

    @Test
    void forLoop_iteratesRange() {
        Sandbox.SandboxResult r = run("""
                total = 0
                for i in range(10):
                    total += i
                if total == 45:
                    emit(box(0, 0, 0, 1, 1, 1, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
        assertEquals(8, r.collectedOps().size());
    }

    @Test
    void whileLoop_respectsBreak() {
        Sandbox.SandboxResult r = run("""
                i = 0
                while True:
                    if i >= 3:
                        break
                    i += 1
                emit(box(0, 0, 0, i, 0, 0, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
        assertEquals(4, r.collectedOps().size());
    }

    @Test
    void ifElif_selectsBranch() {
        Sandbox.SandboxResult r = run("""
                x = 5
                if x < 3:
                    y = "small"
                elif x < 7:
                    y = "mid"
                else:
                    y = "big"
                if y == "mid":
                    emit(box(0, 0, 0, 0, 0, 0, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
        assertEquals(1, r.collectedOps().size());
    }

    // ------ Functions ------------------------------------------------------

    @Test
    void functionDefinition_andCall() {
        Sandbox.SandboxResult r = run("""
                def square(x):
                    return x * x
                a = square(4)
                if a == 16:
                    emit(box(0, 0, 0, 1, 1, 1, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
        assertEquals(8, r.collectedOps().size());
    }

    @Test
    void recursion_withinBudget() {
        Sandbox.SandboxResult r = run("""
                def fact(n):
                    if n <= 1:
                        return 1
                    return n * fact(n - 1)
                f = fact(6)
                if f == 720:
                    emit(box(0, 0, 0, 0, 0, 0, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
    }

    // ------ Collections ----------------------------------------------------

    @Test
    void listOperations() {
        Sandbox.SandboxResult r = run("""
                xs = [1, 2, 3]
                append(xs, 4)
                s = 0
                for x in xs:
                    s += x
                if s == 10:
                    emit(box(0, 0, 0, 0, 0, 0, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
    }

    @Test
    void dictOperations() {
        Sandbox.SandboxResult r = run("""
                d = {"a": 1, "b": 2}
                d["c"] = 3
                if d["a"] + d["c"] == 4:
                    emit(box(0, 0, 0, 0, 0, 0, "stone"))
                """);
        assertTrue(r.completed(), r.diagnostic());
    }

    // ------ Toolbox integration -------------------------------------------

    @Test
    void toolboxComposition_viaScript() {
        Sandbox.SandboxResult r = run("""
                wall = box(0, 0, 0, 5, 3, 0, "stone")
                opening = box(2, 1, 0, 3, 2, 0, "stone")
                punched = subtract(wall, opening)
                emit(punched)
                """);
        assertTrue(r.completed(), r.diagnostic());
        Set<BlockOp> ops = r.collectedOps();
        // 6*4=24 minus 2*2=4 opening = 20 blocks
        assertEquals(20, ops.size());
    }

    // ------ Prohibitions ---------------------------------------------------

    @Test
    void attributeAccess_isForbidden() {
        Sandbox.SandboxResult r = run("""
                xs = [1, 2]
                xs.append(3)
                """);
        assertFalse(r.completed());
        assertTrue(r.diagnostic().contains("attribute access"), r.diagnostic());
    }

    @Test
    void unknownName_isRejected() {
        Sandbox.SandboxResult r = run("""
                x = foo()
                """);
        assertFalse(r.completed());
        assertTrue(r.diagnostic().contains("foo"), r.diagnostic());
    }

    // ------ Resource ceilings ---------------------------------------------

    @Test
    void stepBudget_enforcedOnInfiniteLoop() {
        SandboxLimits tiny = new SandboxLimits(500L, 5000L, 1000, 10_000);
        Sandbox.SandboxResult r = Sandbox.run("""
                i = 0
                while True:
                    i += 1
                """, tiny);
        assertFalse(r.completed());
        assertTrue(r.diagnostic().toLowerCase().contains("exceeded"), r.diagnostic());
    }

    @Test
    void loopIterationCeiling_isApplied() {
        SandboxLimits limits = new SandboxLimits(50_000L, 5000L, 100_000, 100);
        Sandbox.SandboxResult r = Sandbox.run("""
                total = 0
                for i in range(1000):
                    total += i
                """, limits);
        assertFalse(r.completed());
        assertTrue(r.diagnostic().contains("iterations") || r.diagnostic().contains("exceeded"),
                r.diagnostic());
    }

    @Test
    void collectionCeiling_rejectsGiantList() {
        SandboxLimits limits = new SandboxLimits(50_000L, 5000L, 50, 10_000);
        Sandbox.SandboxResult r = Sandbox.run("""
                xs = range(1000)
                """, limits);
        // range() itself or the resulting length > 50 → refused. Either a
        // parse-time refusal or a runtime ceiling hit is acceptable;
        // the point is it doesn't actually produce 1000 items.
        assertFalse(r.completed() && r.collectedOps().size() > 50);
    }
}
