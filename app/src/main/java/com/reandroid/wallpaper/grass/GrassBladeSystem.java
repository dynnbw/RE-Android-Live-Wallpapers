package com.reandroid.wallpaper.grass;

import java.util.Random;

import static com.reandroid.wallpaper.grass.GrassConstants.MAX_BEND;
import static com.reandroid.wallpaper.grass.GrassConstants.TESSELATION;

final class GrassBladeSystem {
    private final Random random;
    private final GrassWindField windField;

    private int width;
    private int height;
    private int bladeCount;

    private Blade[] blades = new Blade[0];
    private int[] bladeSizes = new int[0];
    private int vertexCount;
    private int indexCount;

    GrassBladeSystem(Random random, GrassWindField windField, int width, int height, int bladeCount) {
        this.random = random;
        this.windField = windField;
        this.width = width;
        this.height = height;
        this.bladeCount = bladeCount;
    }

    void initBlades() {
        blades = new Blade[bladeCount];
        bladeSizes = new int[bladeCount];
        for (int i = 0; i < bladeCount; i++) {
            Blade blade = new Blade();
            createBlade(blade);
            blades[i] = blade;
            bladeSizes[i] = blade.size;
        }
        computeBladeBufferCounts();
    }

    void setBladeCount(int count) {
        bladeCount = count;
    }

    void setViewport(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void updateBladePositionsForViewport() {
        if (blades == null) return;
        for (Blade blade : blades) {
            float xpos = random(-width, width);
            blade.xPos = xpos;
            blade.turbulencex = xpos * 0.006f;
            blade.yPos = height;
        }
    }

    void updateBladeAngles(float noiseNow, float windAmplitudeScale) {
        if (blades == null) return;
        for (Blade blade : blades) {
            float newAngle = (windField.turbulencef2(blade.turbulencex, noiseNow, 4.0f) - 0.5f)
                    * 0.5f * windAmplitudeScale;
            blade.angle = clamp(blade.angle + (newAngle + blade.offset - blade.angle) * 0.15f,
                    -MAX_BEND, MAX_BEND);
        }
    }

    Blade[] getBlades() {
        return blades;
    }

    int[] getBladeSizes() {
        return bladeSizes;
    }

    int getVertexCount() {
        return vertexCount;
    }

    int getIndexCount() {
        return indexCount;
    }

    private void computeBladeBufferCounts() {
        vertexCount = 0;
        indexCount = 0;
        for (int size : bladeSizes) {
            indexCount += size * 2 * 3;
            vertexCount += size + 2;
        }
    }

    private void createBlade(Blade blade) {
        float size = random(4.0f) + 4.0f;
        float xpos = random(-width, width);
        blade.angle = 0.0f;
        blade.size = (int) (size / TESSELATION);
        blade.xPos = xpos;
        blade.yPos = height;
        blade.offset = random(0.2f) - 0.1f;
        blade.scale = 4.0f / (size / TESSELATION) + (random(0.6f) + 0.2f) * TESSELATION;
        blade.lengthX = (random(4.5f) + 3.0f) * TESSELATION * size;
        blade.lengthY = (random(5.5f) + 2.0f) * TESSELATION * size;
        blade.hardness = (random(1.0f) + 0.2f) * TESSELATION;
        blade.h = random(0.02f) + 0.2f;
        blade.s = random(0.22f) + 0.78f;
        blade.b = random(0.65f) + 0.35f;
        blade.turbulencex = xpos * 0.006f;
    }

    private float random(float range) {
        return random.nextFloat() * range;
    }

    private float random(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
