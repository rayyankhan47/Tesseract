package com.rayyan.tesseract.texture;

import java.util.Random;

/**
 * Deterministic 3D gradient noise in [-1, 1] (§9.1.1).
 */
public final class PerlinNoise3D {

    private final int[] perm = new int[512];

    public PerlinNoise3D(long seed) {
        Random r = new Random(seed);
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    /** Noise in [0, 1]. */
    public double noise01(double x, double y, double z) {
        return (noise(x, y, z) + 1.0) * 0.5;
    }

    private double noise(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int a = perm[X] + Y;
        int aa = perm[a] + Z;
        int ab = perm[a + 1] + Z;
        int b = perm[X + 1] + Y;
        int ba = perm[b] + Z;
        int bb = perm[b + 1] + Z;
        return lerp(w,
                lerp(v, lerp(u, grad(perm[aa], x, y, z), grad(perm[ba], x - 1, y, z)),
                        lerp(u, grad(perm[ab], x, y - 1, z), grad(perm[bb], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(perm[aa + 1], x, y, z - 1), grad(perm[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[ab + 1], x, y - 1, z - 1), grad(perm[bb + 1], x - 1, y - 1, z - 1))));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
