package com.rayyan.tesseract.agent;

import com.google.gson.JsonObject;
import com.rayyan.tesseract.plan.ElementSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduler ordering — L3 is supposed to emit dependency-sorted lists
 * but the REPL driver has to be resilient to imperfect input too.
 * These tests pin the orderSpecs() contract so we can refactor later.
 */
class ElementSchedulerTest {

    @Test
    void orderSpecs_respectsOrderHintWhenNoDependencies() {
        ElementSpec a = spec("a", List.of(), 30);
        ElementSpec b = spec("b", List.of(), 10);
        ElementSpec c = spec("c", List.of(), 20);
        List<ElementSpec> out = ElementScheduler.orderSpecs(List.of(a, b, c));
        assertEquals(List.of("b", "c", "a"), ids(out));
    }

    @Test
    void orderSpecs_placesDependenciesBeforeDependents() {
        ElementSpec foundation = spec("foundation", List.of(), 0);
        ElementSpec wall = spec("wall", List.of("foundation"), 50);
        ElementSpec roof = spec("roof", List.of("wall"), 90);
        List<ElementSpec> out = ElementScheduler.orderSpecs(List.of(roof, foundation, wall));
        List<String> ids = ids(out);
        assertTrue(ids.indexOf("foundation") < ids.indexOf("wall"));
        assertTrue(ids.indexOf("wall") < ids.indexOf("roof"));
    }

    @Test
    void orderSpecs_dropsUnknownDependenciesGracefully() {
        ElementSpec a = spec("a", List.of("ghost"), 10);
        List<ElementSpec> out = ElementScheduler.orderSpecs(List.of(a));
        assertEquals(List.of("a"), ids(out));
    }

    @Test
    void orderSpecs_handlesCycleWithoutInfiniteLoop() {
        ElementSpec a = spec("a", List.of("b"), 10);
        ElementSpec b = spec("b", List.of("a"), 20);
        List<ElementSpec> out = ElementScheduler.orderSpecs(List.of(a, b));
        assertEquals(2, out.size());
        assertTrue(ids(out).containsAll(List.of("a", "b")));
    }

    private static ElementSpec spec(String id, List<String> deps, int orderHint) {
        return new ElementSpec(id, "zone-" + id, "mass", "desc", new JsonObject(),
                new ArrayList<>(deps), orderHint, List.of());
    }

    private static List<String> ids(List<ElementSpec> specs) {
        List<String> out = new ArrayList<>(specs.size());
        for (ElementSpec s : specs) out.add(s.id());
        return out;
    }
}
