/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reandroid.wallpaper.grass;

import java.util.Random;

import static com.reandroid.wallpaper.grass.GrassConstants.MAX_BEND;
import static com.reandroid.wallpaper.grass.GrassConstants.TESSELATION;
import com.reandroid.utils.MathUtils;

final class GrassBladeSystem {
    private static final float ANGLE_DIRTY_EPSILON = 0.00075f;
    private static final int WIND_SAMPLE_COUNT = 64;
    private static final float MIN_SAMPLE_SPAN = 0.0001f;

    private final Random random;
    private final GrassWindField windField;

    private int width;
    private int height;
    private int bladeCount;

    private Blade[] blades = new Blade[0];
    private int[] bladeSizes = new int[0];
    private int vertexCount;
    private int indexCount;
    private final float[] windSamples = new float[WIND_SAMPLE_COUNT];

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

    boolean updateBladeAngles(float noiseNow, float windAmplitudeScale) {
        if (blades == null || blades.length == 0) return false;

        float minTx = Float.MAX_VALUE;
        float maxTx = -Float.MAX_VALUE;
        for (Blade blade : blades) {
            if (blade.turbulencex < minTx) {
                minTx = blade.turbulencex;
            }
            if (blade.turbulencex > maxTx) {
                maxTx = blade.turbulencex;
            }
        }

        float span = maxTx - minTx;
        float sampleStep;
        if (span < MIN_SAMPLE_SPAN) {
            float sample = windField.turbulencef2(minTx, noiseNow, 4.0f);
            for (int i = 0; i < WIND_SAMPLE_COUNT; i++) {
                windSamples[i] = sample;
            }
            sampleStep = 1.0f;
        } else {
            sampleStep = span / (WIND_SAMPLE_COUNT - 1);
            for (int i = 0; i < WIND_SAMPLE_COUNT; i++) {
                float x = minTx + i * sampleStep;
                windSamples[i] = windField.turbulencef2(x, noiseNow, 4.0f);
            }
        }

        boolean dirty = false;
        for (Blade blade : blades) {
            float previousAngle = blade.angle;

            float noiseValue;
            if (span < MIN_SAMPLE_SPAN) {
                noiseValue = windSamples[0];
            } else {
                float t = (blade.turbulencex - minTx) / sampleStep;
                int idx = (int) t;
                if (idx < 0) {
                    idx = 0;
                }
                if (idx >= WIND_SAMPLE_COUNT - 1) {
                    idx = WIND_SAMPLE_COUNT - 2;
                }
                float frac = t - idx;
                float n0 = windSamples[idx];
                float n1 = windSamples[idx + 1];
                noiseValue = n0 + (n1 - n0) * frac;
            }

            float newAngle = (noiseValue - 0.5f) * 0.5f * windAmplitudeScale;
            blade.angle = MathUtils.clamp(blade.angle + (newAngle + blade.offset - blade.angle) * 0.15f,
                    -MAX_BEND, MAX_BEND);
            if (!dirty && Math.abs(blade.angle - previousAngle) >= ANGLE_DIRTY_EPSILON) {
                dirty = true;
            }
        }
        return dirty;
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
}
