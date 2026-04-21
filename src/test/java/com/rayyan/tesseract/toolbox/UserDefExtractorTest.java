package com.rayyan.tesseract.toolbox;

import com.rayyan.tesseract.toolbox.UserDefExtractor.UserDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserDefExtractorTest {

    @Test
    void extractsSingleDef() {
        String src = """
                def add(a, b):
                    return a + b
                """;
        List<UserDef> defs = UserDefExtractor.extract(src);
        assertEquals(1, defs.size());
        assertEquals("add", defs.get(0).name());
        assertEquals(List.of("a", "b"), defs.get(0).params());
        assertTrue(defs.get(0).source().contains("return a + b"));
    }

    @Test
    void extractsMultipleDefs() {
        String src = """
                def first(x):
                    return x

                def second(y):
                    return y * 2
                """;
        List<UserDef> defs = UserDefExtractor.extract(src);
        assertEquals(2, defs.size());
        assertEquals("first", defs.get(0).name());
        assertEquals("second", defs.get(1).name());
    }

    @Test
    void preservesBlankLinesInsideBlock() {
        String src = """
                def foo(x):
                    y = x + 1

                    return y
                """;
        List<UserDef> defs = UserDefExtractor.extract(src);
        assertEquals(1, defs.size());
        String body = defs.get(0).source();
        assertTrue(body.contains("y = x + 1"));
        assertTrue(body.contains("return y"));
    }

    @Test
    void skipsNestedDefs() {
        String src = """
                def outer(x):
                    def inner(y):
                        return y
                    return inner(x)
                """;
        List<UserDef> defs = UserDefExtractor.extract(src);
        assertEquals(1, defs.size());
        assertEquals("outer", defs.get(0).name());
    }

    @Test
    void handlesTrailingTopLevelStatements() {
        String src = """
                def make_tower(height):
                    return box(0, 0, 0, 2, height, 2, "stone")

                t = make_tower(10)
                emit(t)
                """;
        List<UserDef> defs = UserDefExtractor.extract(src);
        assertEquals(1, defs.size());
        assertTrue(defs.get(0).source().endsWith("\"stone\")"));
    }

    @Test
    void emptySourceReturnsEmpty() {
        assertTrue(UserDefExtractor.extract("").isEmpty());
        assertTrue(UserDefExtractor.extract(null).isEmpty());
    }
}
