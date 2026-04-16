package com.reandroid.wallpaper.grass;

import android.graphics.Color;

import static com.reandroid.wallpaper.grass.GrassConstants.HALF_TESSELATION;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_INTERVAL_VARIANCE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_EXTRAS;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_FLARE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_INTERVAL;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_NORMAL;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_DANDELION;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_FIREFLY;

final class GrassRenderDataBuilder {
    interface LegacyParticleOps {
        LegacyParticle createLegacyParticle(int type);

        void flyLegacyFirefly(LegacyParticle p, boolean isInit);

        void flyLegacyDandelion(LegacyParticle p, boolean isInit);
    }

    private final LegacyParticleOps legacyOps;

    private int width;
    private int height;
    private int vertexCount;
    private int indexCount;
    private int[] bladeSizes = new int[0];

    private final float[] vkSkyParams = new float[6];
    private final float[] vkMoonParams = new float[12];

    private float[] vkGrassVertices = new float[0];
    private int vkGrassFloatCount;

    private final float[] vkSunVerts = new float[30];
    private int vkSunFloatCount;

    private final float[] vkMoonVerts = new float[30];
    private int vkMoonFloatCount;

    private float[] vkDandelionVerts = new float[0];
    private int vkDandelionFloatCount;

    private float[] vkFireflyVerts = new float[0];
    private int vkFireflyFloatCount;

    private float[] vkFireflyFlareVerts = new float[0];
    private int vkFireflyFlareFloatCount;

    private int vkTempSpriteFloatCount;

    GrassRenderDataBuilder(LegacyParticleOps legacyOps) {
        this.legacyOps = legacyOps;
    }

    void setGeometry(int width, int height, int vertexCount, int indexCount, int[] bladeSizes) {
        this.width = width;
        this.height = height;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.bladeSizes = bladeSizes != null ? bladeSizes : new int[0];
    }

    float[] computeSkyParams(SceneData sd) {
        float[] out = vkSkyParams;
        out[0] = 0.0f;
        out[1] = 0.0f;
        out[2] = 0.0f;
        out[3] = 0.0f;
        out[4] = sd.nightInvert ? 1.0f : 0.0f;
        out[5] = clamp(sd.solarEclipseWeight, 0.0f, 1.0f);

        if (sd.useAccurateSun) {
            out[0] = sd.accurateWeights[0];
            out[1] = sd.accurateWeights[1];
            out[2] = sd.accurateWeights[2];
            out[3] = sd.accurateWeights[3];
            return out;
        }

        float now = sd.timeFraction;
        float dawn = sd.dawn;
        float morning = sd.morning;
        float afternoon = sd.afternoon;
        float dusk = sd.dusk;

        if (now >= 0.0f && now < dawn) {
            out[0] = 1.0f;
            return out;
        }
        if (now >= dawn && now <= morning) {
            float half = dawn + (morning - dawn) * 0.5f;
            if (now <= half) {
                float t = normf(dawn, half, now);
                out[0] = 1.0f - t;
                out[1] = t;
            } else {
                float t = normf(half, morning, now);
                out[1] = 1.0f - t;
                out[3] = t;
            }
            return out;
        }
        if (now > morning && now < afternoon) {
            out[3] = 1.0f;
            return out;
        }
        if (now >= afternoon && now <= dusk) {
            float half = afternoon + (dusk - afternoon) * 0.5f;
            if (now <= half) {
                float t = normf(afternoon, half, now);
                out[3] = 1.0f - t;
                out[2] = t;
            } else {
                float t = normf(half, dusk, now);
                out[2] = 1.0f - t;
                out[0] = t;
            }
            return out;
        }

        out[0] = 1.0f;
        return out;
    }

    float[] buildMoonParams(SceneData sd) {
        float[] out = vkMoonParams;
        for (int i = 0; i < out.length; i++) {
            out[i] = 0.0f;
        }
        if (!sd.useAccurateSun || !sd.moonEnabled || !sd.moonVisible) {
            return out;
        }

        out[0] = sd.moonPhaseAngle;
        out[1] = sd.moonBrightness;
        out[2] = sd.moonAlpha;
        out[3] = sd.moonIsDaytime ? 1.0f : 0.0f;
        out[4] = sd.moonContrast;
        out[5] = sd.moonSaturation;
        out[6] = sd.moonBlueTint;

        if (sd.moonEclipse != null) {
            out[7] = sd.moonEclipse.type;
            out[8] = sd.moonEclipse.fraction;
            out[9] = sd.moonEclipse.phase;
            out[10] = sd.moonEclipse.shadowOffsetX;
            out[11] = sd.moonEclipse.shadowOffsetY;
        }
        return out;
    }

    float[] buildGrassVertexArray(SceneData sd) {
        if (!sd.grassEnabled || sd.blades == null || sd.blades.length == 0) {
            vkGrassFloatCount = 0;
            return vkGrassVertices;
        }

        float eclipseImpact = sd.useAccurateSun ? clamp(sd.solarEclipseWeight, 0.0f, 1.0f) : 0.0f;
        float grassBrightness;
        float nightDesat;
        if (sd.useAccurateSun) {
            grassBrightness = sd.newB;
            nightDesat = 0.0f;
            if (sd.nightDesaturateGrass) {
                grassBrightness = mix(1.0f, 0.72f, eclipseImpact);
                float baseNightDesat = sd.accurateWeights[0];
                nightDesat = clamp(baseNightDesat + eclipseImpact * 0.85f, 0.0f, 1.0f);
            } else {
                grassBrightness *= mix(1.0f, 0.62f, eclipseImpact);
            }
        } else {
            grassBrightness = sd.newB;
            nightDesat = 0.0f;
            if (sd.nightDesaturateGrass) {
                grassBrightness = 1.0f;
                nightDesat = clamp(1.0f - sd.newB, 0.0f, 1.0f);
            }
        }

        final int stride = 8;
        int required = Math.max(0, vertexCount * 2) * stride;
        if (vkGrassVertices.length < required) {
            vkGrassVertices = new float[required];
        }
        float[] out = vkGrassVertices;
        int cursor = 0;
        for (Blade blade : sd.blades) {
            cursor = appendBladeVertices(sd, blade, grassBrightness, sd.xDraw, nightDesat, out, cursor);
            if (cursor > out.length) {
                break;
            }
        }

        vkGrassFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    short[] buildGrassIndexArray() {
        short[] idx = new short[Math.max(0, indexCount)];
        int idxIdx = 0;
        int vtxIdx = 0;
        for (int size : bladeSizes) {
            for (int ct = 0; ct < size; ct++) {
                idx[idxIdx] = (short) (vtxIdx);
                idx[idxIdx + 1] = (short) (vtxIdx + 1);
                idx[idxIdx + 2] = (short) (vtxIdx + 2);
                idx[idxIdx + 3] = (short) (vtxIdx + 1);
                idx[idxIdx + 4] = (short) (vtxIdx + 3);
                idx[idxIdx + 5] = (short) (vtxIdx + 2);
                idxIdx += 6;
                vtxIdx += 2;
            }
            vtxIdx += 2;
        }
        return idx;
    }

    int getGrassVertexCount() {
        return vkGrassFloatCount / 8;
    }

    float[] buildSunSpriteVertices(SceneData sd) {
        if (!sd.useAccurateSun || !sd.sunEnabled || !sd.hasSunData) {
            vkSunFloatCount = 0;
            return vkSunVerts;
        }
        appendSpriteQuadVertices(vkSunVerts, 0, sd.sunX, sd.sunY, sd.sunSize, sd.sunAlpha, false, 0.0f);
        vkSunFloatCount = 30;
        return vkSunVerts;
    }

    int getSunVertexCount() {
        return vkSunFloatCount / 5;
    }

    float[] buildMoonSpriteVertices(SceneData sd) {
        if (!sd.useAccurateSun || !sd.moonEnabled || !sd.moonVisible) {
            vkMoonFloatCount = 0;
            return vkMoonVerts;
        }
        float alpha = clamp(sd.moonAlpha * sd.moonBrightness, 0.0f, 1.0f);
        if (alpha <= 0.001f) {
            vkMoonFloatCount = 0;
            return vkMoonVerts;
        }
        appendSpriteQuadVertices(vkMoonVerts, 0, sd.moonX, sd.moonY, sd.moonSize, alpha, false, 0.0f);
        vkMoonFloatCount = 30;
        return vkMoonVerts;
    }

    int getMoonVertexCount() {
        return vkMoonFloatCount / 5;
    }

    float[] buildDandelionSpriteVertices(SceneData sd) {
        if (sd.legacyParticles) {
            vkDandelionVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_DANDELION,
                    false, false, true, vkDandelionVerts);
            vkDandelionFloatCount = vkTempSpriteFloatCount;
            return vkDandelionVerts;
        }
        if (sd.dandelionVisibility <= 0.001f || !sd.dandelionEnabled
            || sd.dandelions == null || sd.dandelions.length == 0) {
            vkDandelionFloatCount = 0;
            return vkDandelionVerts;
        }

        int required = sd.dandelions.length * 30;
        if (vkDandelionVerts.length < required) {
            vkDandelionVerts = new float[required];
        }

        float[] out = vkDandelionVerts;
        int cursor = 0;
        for (Dandelion d : sd.dandelions) {
            float sway = (float) Math.sin(d.swayPhase + sd.animNowMs * 0.001f * d.swaySpeed) * 6.0f;
            cursor = appendSpriteQuadVertices(out, cursor, d.x, d.y + sway, d.size,
                    0.9f * sd.dandelionVisibility, true, d.rotationDeg);
            if (cursor > out.length) {
                break;
            }
        }
        vkDandelionFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    int getDandelionVertexCount() {
        return vkDandelionFloatCount / 5;
    }

    float[] buildFireflySpriteVertices(SceneData sd) {
        if (sd.legacyParticles) {
            vkFireflyVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_FIREFLY,
                    false, true, true, vkFireflyVerts);
            vkFireflyFloatCount = vkTempSpriteFloatCount;
            return vkFireflyVerts;
        }
        if (sd.fireflyVisibility <= 0.001f || !sd.fireflyEnabled
            || sd.fireflies == null || sd.fireflies.length == 0) {
            vkFireflyFloatCount = 0;
            return vkFireflyVerts;
        }

        int required = sd.fireflies.length * 30;
        if (vkFireflyVerts.length < required) {
            vkFireflyVerts = new float[required];
        }

        float[] out = vkFireflyVerts;
        int cursor = 0;
        float time = sd.animNowMs * 0.001f;
        for (Firefly f : sd.fireflies) {
            float flicker = 0.5f + 0.5f * (float) Math.sin(f.phase + time * f.flickerSpeed);
            float alpha = (0.2f + 0.8f * flicker) * sd.fireflyVisibility;
            float size = f.size * (0.8f + 0.4f * flicker);
            cursor = appendSpriteQuadVertices(out, cursor, f.x, f.y, size, alpha, false, 0.0f);
            if (cursor > out.length) {
                break;
            }
        }
        vkFireflyFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    int getFireflyVertexCount() {
        return vkFireflyFloatCount / 5;
    }

    float[] buildFireflyFlareSpriteVertices(SceneData sd) {
        if (!sd.legacyParticles) {
            vkFireflyFlareFloatCount = 0;
            return vkFireflyFlareVerts;
        }
        vkFireflyFlareVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_FIREFLY,
                true, true, true, vkFireflyFlareVerts);
        vkFireflyFlareFloatCount = vkTempSpriteFloatCount;
        return vkFireflyFlareVerts;
    }

    int getFireflyFlareVertexCount() {
        return vkFireflyFlareFloatCount / 5;
    }

    private float[] buildLegacySpriteVertices(SceneData sd, int legacyTargetType,
            boolean fireflyFlarePass, boolean includeFirefly, boolean updateState,
            float[] reusableBuffer) {
        if (!sd.legacyParticles || sd.legacyType != legacyTargetType
                || sd.legacyNormal == null || sd.legacyExtras == null) {
            vkTempSpriteFloatCount = 0;
            return reusableBuffer;
        }

        int required = (LEGACY_MAX_NORMAL + LEGACY_MAX_EXTRAS) * 30;
        float[] out = reusableBuffer;
        if (out.length < required) {
            out = new float[required];
        }

        int cursor = 0;
        long animNowMs = sd.legacyNow;

        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            LegacyParticle p = sd.legacyNormal[i];
            if (p == null || !p.active) continue;
            cursor = updateAndAppendLegacyParticle(out, cursor, p, sd,
                    legacyTargetType, i, false, animNowMs,
                    fireflyFlarePass, includeFirefly, updateState);
            if (cursor > out.length) {
                break;
            }
        }

        if (cursor <= out.length) {
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                LegacyParticle p = sd.legacyExtras[i];
                if (p == null || !p.active) continue;
                cursor = updateAndAppendLegacyParticle(out, cursor, p, sd,
                        legacyTargetType, i + 100, true, animNowMs,
                        fireflyFlarePass, includeFirefly, updateState);
                if (cursor > out.length) {
                    break;
                }
            }
        }

        vkTempSpriteFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    private int updateAndAppendLegacyParticle(float[] out, int cursor, LegacyParticle p,
            SceneData sd, int legacyType, int index, boolean isExtras, long animNowMs,
            boolean fireflyFlarePass, boolean includeFirefly, boolean updateState) {
        if (updateState) {
            long delta = animNowMs - p.startTime;
            if (delta < 0L) return cursor;

            boolean outOfBounds = isLegacyParticleOutOfBounds(p, legacyType);
            if (outOfBounds) {
                LegacyParticle np = legacyOps.createLegacyParticle(legacyType);
                np.active = true;
                if (isExtras) {
                    sd.legacyExtras[index - 100] = np;
                } else {
                    sd.legacyNormal[index] = np;
                }
                p = np;
                delta = animNowMs - p.startTime;
                if (delta < 0L) return cursor;
            }

            if ((p.stayEndTime - animNowMs) <= 0L) {
                if (legacyType == LEGACY_TYPE_DANDELION) {
                    legacyOps.flyLegacyDandelion(p, false);
                } else {
                    legacyOps.flyLegacyFirefly(p, false);
                }
                p.startTime = animNowMs;
            }
        }

        if (legacyType == LEGACY_TYPE_FIREFLY) {
            if (!includeFirefly) return cursor;

            if (updateState) {
                long interval = p.flareEndTime - p.silentEndTime;
                if (animNowMs >= p.flareEndTime && interval > 0L) {
                    p.silentEndTime = animNowMs
                            + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE)
                            * LEGACY_MAX_INTERVAL);
                } else if (animNowMs >= p.silentEndTime && interval < 0L) {
                    p.flareEndTime = animNowMs + LEGACY_MAX_FLARE;
                }
            }

            boolean flareActive = animNowMs < p.flareEndTime;
            if (fireflyFlarePass != flareActive) return cursor;

            float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + index * 1234L) * 0.002);
            float alpha = 0.2f + 0.8f * flicker;
            float size = (isExtras ? 48.0f : 72.0f) * (0.8f + 0.4f * flicker);
            return appendSpriteQuadVertices(out, cursor, p.originX, p.originY, size, alpha, false, 0.0f);
        }

        float size = isExtras ? 64.0f : 96.0f;
        return appendSpriteQuadVertices(out, cursor, p.originX, p.originY, size, 0.9f, true, p.angle);
    }

    private boolean isLegacyParticleOutOfBounds(LegacyParticle p, int legacyType) {
        if (legacyType == LEGACY_TYPE_DANDELION) {
            return p.originX < 0.0f || p.originX > width * 2.0f || p.originY < 0.0f || p.originY > height;
        }
        return p.originX < 0.0f || p.originX > width * 2.0f || p.originY < 0.0f;
    }

    private int appendBladeVertices(SceneData sd, Blade blade, float brightness,
            float xOffset, float nightDesat, float[] out, int cursor) {
        float scale = blade.scale * sd.grassWidthScale;
        float angle = blade.angle;
        float xpos = blade.xPos + xOffset;
        int size = blade.size;

        float h = blade.h;
        float s = blade.s;
        float v = mix(0.0f, blade.b, brightness);
        if (sd.useGrassTint) {
            h = sd.grassTintH;
            s = sd.grassTintS;
            v = clamp(v * sd.grassTintV, 0.0f, 1.0f);
        }
        if (sd.nightDesaturateGrass && nightDesat > 0.0f) {
            s = mix(s, 0.0f, clamp(nightDesat, 0.0f, 1.0f));
        }

        int color = hsbToRgb(h, s, v);
        float r = Color.red(color) / 255.0f;
        float g = Color.green(color) / 255.0f;
        float b = Color.blue(color) / 255.0f;

        float currentAngle = (float) (Math.PI * 0.5);
        float bottomX = xpos;
        float bottomY = blade.yPos;
        float d = angle * blade.hardness * sd.grassHardnessScale;

        float si = size * scale;
        cursor = putVertex(out, cursor, bottomX - si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 0.0f, 0.0f);
        cursor = putVertex(out, cursor, bottomX + si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 1.0f, 0.0f);

        for (; size > 0; size--) {
            float lengthX = blade.lengthX * sd.grassHeightScale;
            float lengthY = blade.lengthY * sd.grassHeightScale;
            float topX = bottomX - (float) Math.cos(currentAngle) * lengthX;
            float topY = bottomY - (float) Math.sin(currentAngle) * lengthY;
            si = size * scale;
            float spi = si - scale;
            cursor = putVertex(out, cursor, topX - spi, topY, r, g, b, 1.0f, 0.0f, 0.0f);
            cursor = putVertex(out, cursor, topX + spi, topY, r, g, b, 1.0f, 1.0f, 0.0f);
            bottomX = topX;
            bottomY = topY;
            currentAngle += d;
        }
        return cursor;
    }

    private int putVertex(float[] out, int cursor,
            float x, float y, float r, float g, float b, float a, float s, float t) {
        if (cursor + 8 > out.length) return out.length + 1;
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = r;
        out[cursor++] = g;
        out[cursor++] = b;
        out[cursor++] = a;
        out[cursor++] = s;
        out[cursor++] = t;
        return cursor;
    }

    private int appendSpriteQuadVertices(float[] out, int cursor,
            float cx, float cy, float size, float alpha, boolean flipV, float rotationDeg) {
        if (cursor + 30 > out.length) return out.length + 1;

        float half = size * 0.5f;
        float rad = (float) Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x0 = (-half * cos) - (-half * sin) + cx;
        float y0 = (-half * sin) + (-half * cos) + cy;
        float x1 = (-half * cos) - (half * sin) + cx;
        float y1 = (-half * sin) + (half * cos) + cy;
        float x2 = (half * cos) - (half * sin) + cx;
        float y2 = (half * sin) + (half * cos) + cy;
        float x3 = (half * cos) - (-half * sin) + cx;
        float y3 = (half * sin) + (-half * cos) + cy;

        float v0 = flipV ? 0.0f : 1.0f;
        float v1 = flipV ? 1.0f : 0.0f;

        cursor = putSpriteVertex(out, cursor, x0, y0, 0.0f, v0, alpha);
        cursor = putSpriteVertex(out, cursor, x1, y1, 0.0f, v1, alpha);
        cursor = putSpriteVertex(out, cursor, x2, y2, 1.0f, v1, alpha);
        cursor = putSpriteVertex(out, cursor, x0, y0, 0.0f, v0, alpha);
        cursor = putSpriteVertex(out, cursor, x2, y2, 1.0f, v1, alpha);
        cursor = putSpriteVertex(out, cursor, x3, y3, 1.0f, v0, alpha);
        return cursor;
    }

    private int putSpriteVertex(float[] out, int cursor, float x, float y, float u, float v, float a) {
        if (cursor + 5 > out.length) return out.length + 1;
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        out[cursor++] = a;
        return cursor;
    }

    private static int hsbToRgb(float h, float s, float b) {
        float red = 0.0f;
        float green = 0.0f;
        float blue = 0.0f;

        float hf = (h - (int) h) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = b * (1.0f - s);
        float qv = b * (1.0f - s * f);
        float tv = b * (1.0f - s * (1.0f - f));

        switch (ihf) {
            case 0:
                red = b;
                green = tv;
                blue = pv;
                break;
            case 1:
                red = qv;
                green = b;
                blue = pv;
                break;
            case 2:
                red = pv;
                green = b;
                blue = tv;
                break;
            case 3:
                red = pv;
                green = qv;
                blue = b;
                break;
            case 4:
                red = tv;
                green = pv;
                blue = b;
                break;
            case 5:
                red = b;
                green = pv;
                blue = qv;
                break;
            default:
                break;
        }

        return Color.argb(255, (int) (red * 255), (int) (green * 255), (int) (blue * 255));
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static float mix(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    private static float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }
}
