package com.rayyan.tesseract.blueprint;

import java.util.List;

/**
 * One static method per Blueprint primitive type.
 *
 * Each method reads the primitive's params, calls {@link BlueprintCompiler.CompileContext#emit}
 * for every block it generates, and returns the {@link PrimitiveBounds} to store
 * for child primitives to inherit from.
 *
 * Coordinate convention: all coordinates are blueprint-local (0,0,0 = min corner of build).
 * PlacementAgent applies the world-space {@code placementOrigin} offset after this stage.
 */
final class PrimitiveCompilers {

    private PrimitiveCompilers() {}

    // -------------------------------------------------------------------------
    // platform
    // -------------------------------------------------------------------------

    static PrimitiveBounds compilePlatform(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] origin = p.requireIntArray3("origin");
        int[] size   = p.requireIntArray3("size");
        String mat     = p.requireString("material");
        String edgeMat = p.getString("edge_material", null);

        for (int x = origin[0]; x < origin[0] + size[0]; x++) {
            for (int y = origin[1]; y < origin[1] + size[1]; y++) {
                for (int z = origin[2]; z < origin[2] + size[2]; z++) {
                    boolean edge = edgeMat != null
                            && isPerimeter2D(x - origin[0], z - origin[2], size[0], size[2]);
                    ctx.emit(x, y, z, edge ? edgeMat : mat);
                }
            }
        }
        return new PrimitiveBounds(origin[0], origin[1], origin[2], size[0], size[1], size[2]);
    }

    // -------------------------------------------------------------------------
    // walls
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileWalls(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        PrimitiveBounds parent = ctx.requireParentBounds(p);

        int baseX  = parent.originX();
        int baseY  = parent.topFaceY();
        int baseZ  = parent.originZ();
        int W      = parent.sizeX();
        int D      = parent.sizeZ();
        int height = p.requireInt("height");
        String mat       = p.requireString("material");
        String cornerMat = p.getString("corner_material", mat);
        List<Opening> openings = p.getOpenings();

        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < W; dx++) {
                for (int dz = 0; dz < D; dz++) {
                    // Only perimeter cells
                    if (dx > 0 && dx < W - 1 && dz > 0 && dz < D - 1) continue;
                    // Skip opening positions
                    if (isOpeningBlocked(dx, dy, dz, openings, W, D)) continue;

                    boolean corner = (dx == 0 || dx == W - 1) && (dz == 0 || dz == D - 1);
                    ctx.emit(baseX + dx, baseY + dy, baseZ + dz, corner ? cornerMat : mat);
                }
            }
        }
        return new PrimitiveBounds(baseX, baseY, baseZ, W, height, D);
    }

    // -------------------------------------------------------------------------
    // wall_segment
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileWallSegment(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] from   = p.requireIntArray3("from");
        int[] to     = p.requireIntArray3("to");
        int height   = p.requireInt("height");
        String mat   = p.requireString("material");

        boolean xVaries = from[0] != to[0];
        boolean zVaries = from[2] != to[2];
        if (xVaries && zVaries) {
            throw new BlueprintCompileException(
                    "wall_segment '" + p.id + "': from/to must differ on at most one horizontal axis");
        }

        int minX = Math.min(from[0], to[0]);
        int maxX = Math.max(from[0], to[0]);
        int minZ = Math.min(from[2], to[2]);
        int maxZ = Math.max(from[2], to[2]);
        int baseY = from[1];

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int dy = 0; dy < height; dy++) {
                    ctx.emit(x, baseY + dy, z, mat);
                }
            }
        }
        return new PrimitiveBounds(minX, baseY, minZ,
                maxX - minX + 1, height, maxZ - minZ + 1);
    }

    // -------------------------------------------------------------------------
    // gable_roof
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileGableRoof(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        PrimitiveBounds parent = ctx.requireParentBounds(p);

        String ridgeAxis    = p.getString("ridge_axis", "z");
        int overhang        = p.getInt("overhang", 0);
        String stairsMat    = p.requireString("stairs_material");
        String slabMat      = p.getString("slab_material", stairsMat);
        String ridgeMat     = p.getString("ridge_material", slabMat);

        int parentW = parent.sizeX();
        int parentD = parent.sizeZ();
        int roofStartY = parent.topFaceY();

        int roofOriginX, roofOriginZ, spanX, spanZ;
        if ("z".equalsIgnoreCase(ridgeAxis)) {
            // Ridge runs along Z → overhang extends the X span
            roofOriginX = parent.originX() - overhang;
            roofOriginZ = parent.originZ();
            spanX       = parentW + 2 * overhang;
            spanZ       = parentD;
        } else {
            // Ridge runs along X → overhang extends the Z span
            roofOriginX = parent.originX();
            roofOriginZ = parent.originZ() - overhang;
            spanX       = parentW;
            spanZ       = parentD + 2 * overhang;
        }

        int maxRoofHeight;
        if ("z".equalsIgnoreCase(ridgeAxis)) {
            maxRoofHeight = emitGableAlongX(
                    roofOriginX, roofStartY, roofOriginZ, spanX, spanZ,
                    stairsMat, slabMat, ridgeMat, ctx);
        } else {
            maxRoofHeight = emitGableAlongZ(
                    roofOriginX, roofStartY, roofOriginZ, spanX, spanZ,
                    stairsMat, slabMat, ridgeMat, ctx);
        }

        return new PrimitiveBounds(roofOriginX, roofStartY, roofOriginZ,
                spanX, maxRoofHeight, spanZ);
    }

    /**
     * Gable roof whose ridge runs along Z (slopes on the X faces).
     * Returns the roof height (number of y levels used).
     */
    private static int emitGableAlongX(int ox, int oy, int oz, int spanX, int spanZ,
                                        String stairsMat, String slabMat, String ridgeMat,
                                        BlueprintCompiler.CompileContext ctx) {
        int halfX = spanX / 2;

        for (int dz = 0; dz < spanZ; dz++) {
            for (int step = 0; step < halfX; step++) {
                int y = oy + step;
                // West slope: facing east (climbs toward east)
                ctx.emit(ox + step, y, oz + dz,
                        stairsMat + "[facing=east,half=bottom]");
                // East slope: facing west (climbs toward west)
                ctx.emit(ox + spanX - 1 - step, y, oz + dz,
                        stairsMat + "[facing=west,half=bottom]");
            }
            // Ridge / top
            if (spanX % 2 == 1) {
                // Odd span: single centre column
                ctx.emit(ox + halfX, oy + halfX, oz + dz, ridgeMat);
            } else {
                // Even span: two adjacent blocks at the peak row
                // (stairs already placed the two innermost blocks)
                // Place ridge slab/block on top of the innermost stair pair
                ctx.emit(ox + halfX - 1, oy + halfX, oz + dz, ridgeMat);
                ctx.emit(ox + halfX,     oy + halfX, oz + dz, ridgeMat);
            }
        }
        return halfX + 1;
    }

    /**
     * Gable roof whose ridge runs along X (slopes on the Z faces).
     */
    private static int emitGableAlongZ(int ox, int oy, int oz, int spanX, int spanZ,
                                        String stairsMat, String slabMat, String ridgeMat,
                                        BlueprintCompiler.CompileContext ctx) {
        int halfZ = spanZ / 2;

        for (int dx = 0; dx < spanX; dx++) {
            for (int step = 0; step < halfZ; step++) {
                int y = oy + step;
                ctx.emit(ox + dx, y, oz + step,
                        stairsMat + "[facing=south,half=bottom]");
                ctx.emit(ox + dx, y, oz + spanZ - 1 - step,
                        stairsMat + "[facing=north,half=bottom]");
            }
            if (spanZ % 2 == 1) {
                ctx.emit(ox + dx, oy + halfZ, oz + halfZ, ridgeMat);
            } else {
                ctx.emit(ox + dx, oy + halfZ, oz + halfZ - 1, ridgeMat);
                ctx.emit(ox + dx, oy + halfZ, oz + halfZ,     ridgeMat);
            }
        }
        return halfZ + 1;
    }

    // -------------------------------------------------------------------------
    // hip_roof
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileHipRoof(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        PrimitiveBounds parent = ctx.requireParentBounds(p);

        String stairsMat = p.requireString("stairs_material");
        String slabMat   = p.getString("slab_material", stairsMat);
        String apexMat   = p.getString("apex_material", slabMat);

        int ox = parent.originX();
        int oy = parent.topFaceY();
        int oz = parent.originZ();
        int W  = parent.sizeX();
        int D  = parent.sizeZ();

        int maxRings = Math.min(W / 2, D / 2);

        for (int ring = 0; ring < maxRings; ring++) {
            int y = oy + ring;
            int x0 = ox + ring, x1 = ox + W - 1 - ring;
            int z0 = oz + ring, z1 = oz + D - 1 - ring;

            // North face (z = z0), stairs facing south
            for (int x = x0; x <= x1; x++) {
                ctx.emit(x, y, z0, stairsMat + "[facing=south,half=bottom]");
            }
            // South face (z = z1), stairs facing north
            for (int x = x0; x <= x1; x++) {
                ctx.emit(x, y, z1, stairsMat + "[facing=north,half=bottom]");
            }
            // West face (x = x0), inner z range, stairs facing east
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                ctx.emit(x0, y, z, stairsMat + "[facing=east,half=bottom]");
            }
            // East face (x = x1), inner z range, stairs facing west
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                ctx.emit(x1, y, z, stairsMat + "[facing=west,half=bottom]");
            }
        }

        // Apex / ridge
        int apexY = oy + maxRings;
        int ax0 = ox + maxRings, ax1 = ox + W - 1 - maxRings;
        int az0 = oz + maxRings, az1 = oz + D - 1 - maxRings;
        for (int x = ax0; x <= ax1; x++) {
            for (int z = az0; z <= az1; z++) {
                ctx.emit(x, apexY, z, apexMat);
            }
        }

        return new PrimitiveBounds(ox, oy, oz, W, maxRings + 1, D);
    }

    // -------------------------------------------------------------------------
    // flat_roof
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileFlatRoof(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        PrimitiveBounds parent = ctx.requireParentBounds(p);

        String mat           = p.requireString("material");
        boolean battlements  = p.getBoolean("battlements", false);
        String battlementMat = p.getString("battlement_material", mat);

        int ox = parent.originX();
        int oy = parent.topFaceY();
        int oz = parent.originZ();
        int W  = parent.sizeX();
        int D  = parent.sizeZ();

        // Base flat layer
        for (int x = ox; x < ox + W; x++) {
            for (int z = oz; z < oz + D; z++) {
                ctx.emit(x, oy, z, mat);
            }
        }

        int height = 1;
        if (battlements) {
            // Every other perimeter block raised; corners are always merlons
            for (int x = ox; x < ox + W; x++) {
                for (int z = oz; z < oz + D; z++) {
                    if (!isPerimeter2D(x - ox, z - oz, W, D)) continue;
                    // Merlon pattern: (x+z) % 2 == 0
                    if ((x + z) % 2 == 0) {
                        ctx.emit(x, oy + 1, z, battlementMat);
                    }
                }
            }
            height = 2;
        }

        return new PrimitiveBounds(ox, oy, oz, W, height, D);
    }

    // -------------------------------------------------------------------------
    // column
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileColumn(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] origin   = p.requireIntArray3("origin");
        int height     = p.requireInt("height");
        String mat     = p.requireString("material");
        String capMat  = p.getString("cap_material", mat);
        String baseMat = p.getString("base_material", mat);

        for (int dy = 0; dy < height; dy++) {
            String block = (dy == 0) ? baseMat : (dy == height - 1) ? capMat : mat;
            ctx.emit(origin[0], origin[1] + dy, origin[2], block);
        }
        return new PrimitiveBounds(origin[0], origin[1], origin[2], 1, height, 1);
    }

    // -------------------------------------------------------------------------
    // arch
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileArch(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] from   = p.requireIntArray3("from");
        int[] to     = p.requireIntArray3("to");
        int height   = p.requireInt("height");
        String mat   = p.requireString("material");

        boolean xVaries = from[0] != to[0];
        boolean zVaries = from[2] != to[2];
        if (xVaries && zVaries) {
            throw new BlueprintCompileException(
                    "arch '" + p.id + "': from/to must differ on at most one horizontal axis");
        }
        if (from[1] != to[1]) {
            throw new BlueprintCompileException(
                    "arch '" + p.id + "': from and to must share the same y level");
        }

        int baseY = from[1];
        int span, fixedX, fixedZ, fixedAxis; // fixedAxis: 0=x varies, 1=z varies
        if (xVaries) {
            span    = Math.abs(to[0] - from[0]) + 1;
            fixedX  = -1;
            fixedZ  = from[2];
            fixedAxis = 0;
        } else {
            span    = Math.abs(to[2] - from[2]) + 1;
            fixedX  = from[0];
            fixedZ  = -1;
            fixedAxis = 1;
        }

        int startX = xVaries ? Math.min(from[0], to[0]) : from[0];
        int startZ = zVaries ? Math.min(from[2], to[2]) : from[2];

        for (int s = 0; s < span; s++) {
            // Arc: y = baseY + round(height * sin(pi * s / (span - 1)))
            double t = (span == 1) ? Math.PI / 2.0 : Math.PI * s / (span - 1.0);
            int arcY = baseY + (int) Math.round(height * Math.sin(t));

            // Also fill the pillar below the arc at both ends
            int pillarTopY = (s == 0 || s == span - 1) ? arcY : baseY;
            for (int y = baseY; y <= pillarTopY; y++) {
                int emitX = (fixedAxis == 0) ? (startX + s) : fixedX;
                int emitZ = (fixedAxis == 1) ? (startZ + s) : fixedZ;
                ctx.emit(emitX, y, emitZ, mat);
            }
            if (s > 0 && s < span - 1) {
                // Arc block above pillar
                int emitX = (fixedAxis == 0) ? (startX + s) : fixedX;
                int emitZ = (fixedAxis == 1) ? (startZ + s) : fixedZ;
                ctx.emit(emitX, arcY, emitZ, mat);
            }
        }

        int minX = xVaries ? startX : fixedX;
        int minZ = zVaries ? startZ : fixedZ;
        int sizeX = xVaries ? span : 1;
        int sizeZ = zVaries ? span : 1;
        return new PrimitiveBounds(minX, baseY, minZ, sizeX, height + 1, sizeZ);
    }

    // -------------------------------------------------------------------------
    // staircase
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileStaircase(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] from  = p.requireIntArray3("from");
        int[] to    = p.requireIntArray3("to");
        int width   = p.getInt("width", 1);
        String mat  = p.requireString("material");

        int dy = to[1] - from[1];
        if (dy <= 0) {
            throw new BlueprintCompileException(
                    "staircase '" + p.id + "': 'to' must be higher than 'from' (to.y > from.y)");
        }

        int dx = to[0] - from[0];
        int dz = to[2] - from[2];
        int nSteps = dy;

        // Step direction (unit vector)
        int stepDX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
        int stepDZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);

        // Stair facing = direction of ascent
        String facing;
        if (stepDX > 0)      facing = "east";
        else if (stepDX < 0) facing = "west";
        else if (stepDZ > 0) facing = "south";
        else                  facing = "north";

        // Width extrudes perpendicular to step direction
        int perpDX = (stepDX == 0) ? 1 : 0;
        int perpDZ = (stepDZ == 0) ? 1 : 0;

        for (int i = 0; i < nSteps; i++) {
            int stepX = from[0] + stepDX * i;
            int stepY = from[1] + i;
            int stepZ = from[2] + stepDZ * i;
            for (int w = 0; w < width; w++) {
                ctx.emit(stepX + perpDX * w, stepY, stepZ + perpDZ * w,
                        mat + "[facing=" + facing + ",half=bottom]");
            }
        }

        int minX = Math.min(from[0], to[0]);
        int minZ = Math.min(from[2], to[2]);
        int sizeX = Math.abs(dx) + 1 + (stepDX == 0 ? width - 1 : 0);
        int sizeZ = Math.abs(dz) + 1 + (stepDZ == 0 ? width - 1 : 0);
        return new PrimitiveBounds(minX, from[1], minZ, sizeX, dy, sizeZ);
    }

    // -------------------------------------------------------------------------
    // frame
    // -------------------------------------------------------------------------

    static PrimitiveBounds compileFrame(Primitive p, BlueprintCompiler.CompileContext ctx)
            throws BlueprintCompileException {
        int[] origin = p.requireIntArray3("origin");
        int[] size   = p.requireIntArray3("size");
        String mat   = p.requireString("material");

        for (int x = origin[0]; x < origin[0] + size[0]; x++) {
            for (int y = origin[1]; y < origin[1] + size[1]; y++) {
                for (int z = origin[2]; z < origin[2] + size[2]; z++) {
                    // Emit only the outer shell
                    int dx = x - origin[0], dy = y - origin[1], dz = z - origin[2];
                    boolean onShell = dx == 0 || dx == size[0] - 1
                            || dy == 0 || dy == size[1] - 1
                            || dz == 0 || dz == size[2] - 1;
                    if (onShell) ctx.emit(x, y, z, mat);
                }
            }
        }
        return new PrimitiveBounds(origin[0], origin[1], origin[2], size[0], size[1], size[2]);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code (dx, dz)} is on the perimeter of a {@code W × D}
     * horizontal rectangle (2D edge check).
     */
    static boolean isPerimeter2D(int dx, int dz, int W, int D) {
        return dx == 0 || dx == W - 1 || dz == 0 || dz == D - 1;
    }

    /**
     * Returns true if the block at relative position {@code (dx, dy, dz)} in a
     * wall of size {@code W × D} is blocked by any of the given openings.
     */
    static boolean isOpeningBlocked(int dx, int dy, int dz,
                                    List<Opening> openings, int W, int D) {
        for (Opening o : openings) {
            if (blockedByOpening(dx, dy, dz, o, W, D)) return true;
        }
        return false;
    }

    private static boolean blockedByOpening(int dx, int dy, int dz,
                                             Opening o, int W, int D) {
        int u, wallFaceCoord, expectedFaceCoord;
        switch (o.face()) {
            case "north" -> { wallFaceCoord = 0;     expectedFaceCoord = dz; u = dx; }
            case "south" -> { wallFaceCoord = D - 1; expectedFaceCoord = dz; u = dx; }
            case "west"  -> { wallFaceCoord = 0;     expectedFaceCoord = dx; u = dz; }
            case "east"  -> { wallFaceCoord = W - 1; expectedFaceCoord = dx; u = dz; }
            default -> { return false; }
        }
        // For north/south: the test is dz == wallFaceCoord
        // For west/east:   the test is dx == wallFaceCoord
        boolean onFace = expectedFaceCoord == wallFaceCoord;
        if (!onFace) return false;
        return u >= o.uOffset() && u < o.uOffset() + o.width()
                && dy >= o.vOffset() && dy < o.vOffset() + o.height();
    }
}
