package com.rayyan.tesseract.agent;

import com.google.gson.JsonObject;
import com.rayyan.tesseract.blueprint.CompiledBlueprint;
import com.rayyan.tesseract.blueprint.PrimitiveBounds;
import com.rayyan.tesseract.plan.ElementSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CumulativeBuildTest {

    @Test
    void commit_dropsOverlappingVoxels_earlierWins() {
        CumulativeBuild build = new CumulativeBuild(16);
        ElementLock first = lock("foundation",
                ops(new int[][] {{0, 0, 0}, {1, 0, 0}, {2, 0, 0}}, "stone_bricks"));
        Set<BlockOp> committedFirst = build.commit(first);
        assertEquals(3, committedFirst.size());

        ElementLock second = lock("walls",
                ops(new int[][] {{1, 0, 0}, {1, 1, 0}, {1, 2, 0}}, "oak_planks"));
        Set<BlockOp> committedSecond = build.commit(second);

        assertEquals(2, committedSecond.size(), "overlapping voxel (1,0,0) should be dropped");
        assertEquals(5, build.ownedVoxels());
    }

    @Test
    void toCompiledBlueprint_tracksPerElementBounds() {
        CumulativeBuild build = new CumulativeBuild(16);
        build.commit(lock("a", ops(new int[][] {{0, 0, 0}, {3, 2, 1}}, "stone_bricks")));
        build.commit(lock("b", ops(new int[][] {{5, 0, 5}, {7, 3, 5}}, "oak_planks")));

        CompiledBlueprint snapshot = build.toCompiledBlueprint();
        assertNotNull(snapshot);
        assertEquals(4, snapshot.ops().size());

        PrimitiveBounds aBounds = snapshot.primitiveBounds().get("a");
        assertNotNull(aBounds, "bounds for element a must be tracked");
        assertEquals(0, aBounds.originX());
        assertEquals(4, aBounds.sizeX(), "maxX 3 inclusive → sizeX 4");

        PrimitiveBounds bBounds = snapshot.primitiveBounds().get("b");
        assertNotNull(bBounds);
        assertEquals(5, bBounds.originX());
    }

    @Test
    void incremental_rebuildsWithEachCommit() {
        CumulativeBuild build = new CumulativeBuild(16);
        build.commit(lock("a", ops(new int[][] {{0, 0, 0}}, "stone_bricks")));
        CompiledBlueprint first = build.toCompiledBlueprint();
        assertEquals(1, first.ops().size());
        assertTrue(first.primitiveBounds().containsKey("a"));

        build.commit(lock("b", ops(new int[][] {{1, 0, 0}}, "oak_planks")));
        CompiledBlueprint second = build.toCompiledBlueprint();
        assertEquals(2, second.ops().size());
        assertEquals(2, second.primitiveBounds().size());
    }

    // ------------------------------------------------------------------

    private static ElementLock lock(String id, Set<BlockOp> ops) {
        ElementSpec spec = new ElementSpec(id, "zone-" + id, "mass", "desc",
                new JsonObject(), List.of(), 0, List.of());
        return new ElementLock(spec, ops, new LinkedHashSet<>(), null, "", 1, 0,
                false, false, 1.0, "");
    }

    private static Set<BlockOp> ops(int[][] coords, String material) {
        Set<BlockOp> out = new LinkedHashSet<>();
        for (int[] c : coords) {
            BlockOp op = new BlockOp();
            op.x = c[0]; op.y = c[1]; op.z = c[2]; op.block = material;
            out.add(op);
        }
        return out;
    }
}
