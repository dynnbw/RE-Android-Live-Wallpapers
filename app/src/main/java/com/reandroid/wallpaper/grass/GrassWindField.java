package com.reandroid.wallpaper.grass;

import java.util.Random;

final class GrassWindField {
    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;

    private final int[] p = new int[B + B + 2];
    private final float[][] g2 = new float[B + B + 2][2];

    void init(Random random) {
        for (int i = 0; i < B; i++) {
            p[i] = i;
            g2[i][0] = random.nextFloat() * 2.0f - 1.0f;
            g2[i][1] = random.nextFloat() * 2.0f - 1.0f;
            float len = (float) Math.sqrt(g2[i][0] * g2[i][0] + g2[i][1] * g2[i][1]);
            g2[i][0] /= len;
            g2[i][1] /= len;
        }
        for (int i = B - 1; i >= 0; i--) {
            int j = random.nextInt(B);
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
        }
        for (int i = 0; i < B + 2; i++) {
            p[B + i] = p[i];
            g2[B + i][0] = g2[i][0];
            g2[B + i][1] = g2[i][1];
        }
    }

    float turbulencef2(float x, float y, float octaves) {
        float t = 0.0f;
        for (float f = 1.0f; f <= octaves; f *= 2.0f) {
            t += Math.abs(noisef2(f * x, f * y)) / f;
        }
        return t;
    }

    private float noisef2(float x, float y) {
        float t = x + N;
        int bx0 = ((int) t) & BM;
        int bx1 = (bx0 + 1) & BM;
        float rx0 = t - (int) t;
        float rx1 = rx0 - 1.0f;

        t = y + N;
        int by0 = ((int) t) & BM;
        int by1 = (by0 + 1) & BM;
        float ry0 = t - (int) t;
        float ry1 = ry0 - 1.0f;

        int i = p[bx0];
        int j = p[bx1];
        int b00 = p[i + by0];
        int b10 = p[j + by0];
        int b01 = p[i + by1];
        int b11 = p[j + by1];

        float sx = noiseSCurve(rx0);
        float sy = noiseSCurve(ry0);

        float u = rx0 * g2[b00][0] + ry0 * g2[b00][1];
        float v = rx1 * g2[b10][0] + ry0 * g2[b10][1];
        float a = mix(u, v, sx);

        u = rx0 * g2[b01][0] + ry1 * g2[b01][1];
        v = rx1 * g2[b11][0] + ry1 * g2[b11][1];
        float b = mix(u, v, sx);

        return 1.5f * mix(a, b, sy);
    }

    private static float noiseSCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }
}
