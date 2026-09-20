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
import com.reandroid.utils.MathUtils;

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

    private final float[] mVKSkyParams = new float[6];
    private final float[] mVKMoonParams = new float[16];

    private float[] mVKGrassVertices = new float[0];
    private int mVKGrassFloatCount;
    private boolean grassVertexArrayUpdated;
    private boolean hasGrassBuildCache;
    private int lastGrassAppearanceKey;

    private final float[] mVKSunVerts = new float[30];
    private int mVKSunFloatCount;

    private final float[] mVKMoonVerts = new float[30];
    private int mVKMoonFloatCount;

    private float[] mVKDandelionVerts = new float[0];
    private int mVKDandelionFloatCount;

    private float[] mVKFireflyVerts = new float[0];
    private int mVKFireflyFloatCount;

    private float[] mVKFireflyFlareVerts = new float[0];
    private int mVKFireflyFlareFloatCount;

    private int mVKTempSpriteFloatCount;

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
        float[] out = mVKSkyParams;
        out[0] = 0.0f;
        out[1] = 0.0f;
        out[2] = 0.0f;
        out[3] = 0.0f;
        out[4] = sd.nightInvert ? 1.0f : 0.0f;
        out[5] = MathUtils.clamp(sd.solarEclipseWeight, 0.0f, 1.0f);

        out[0] = sd.accurateWeights[0];
        out[1] = sd.accurateWeights[1];
        out[2] = sd.accurateWeights[2];
        out[3] = sd.accurateWeights[3];
        return out;
    }

    float[] buildMoonParams(SceneData sd) {
        float[] out = mVKMoonParams;
        for (int i = 0; i < out.length; i++) {
            out[i] = 0.0f;
        }
        if (!sd.moonEnabled || !sd.moonVisible) {
            return out;
        }

        out[0] = sd.moonPhaseAngle;
        out[12] = sd.moonRotationDeg;   // p3.x：整体旋转角
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
            mVKGrassFloatCount = 0;
            grassVertexArrayUpdated = false;
            hasGrassBuildCache = false;
            return mVKGrassVertices;
        }

        float eclipseImpact = MathUtils.clamp(sd.solarEclipseWeight, 0.0f, 1.0f);
        float grassBrightness = sd.newB;
        float nightDesat = 0.0f;
        if (sd.nightDesaturateGrass) {
            grassBrightness = MathUtils.mix(1.0f, 0.72f, eclipseImpact);
            float baseNightDesat = sd.accurateWeights[0];
            nightDesat = MathUtils.clamp(baseNightDesat + eclipseImpact * 0.85f, 0.0f, 1.0f);
        } else {
            grassBrightness *= MathUtils.mix(1.0f, 0.62f, eclipseImpact);
        }

        final int stride = 8;
        int required = Math.max(0, vertexCount * 2) * stride;
        if (mVKGrassVertices.length < required) {
            mVKGrassVertices = new float[required];
        }

        int appearanceKey = computeGrassAppearanceKey(sd, grassBrightness, nightDesat);
        if (!sd.grassGeometryDirty && hasGrassBuildCache && appearanceKey == lastGrassAppearanceKey) {
            grassVertexArrayUpdated = false;
            return mVKGrassVertices;
        }

        float[] out = mVKGrassVertices;
        int cursor = 0;
        for (Blade blade : sd.blades) {
            cursor = appendBladeVertices(sd, blade, grassBrightness, sd.xDraw, nightDesat, out, cursor);
            if (cursor > out.length) {
                break;
            }
        }

        mVKGrassFloatCount = Math.max(0, Math.min(cursor, out.length));
        grassVertexArrayUpdated = true;
        hasGrassBuildCache = true;
        lastGrassAppearanceKey = appearanceKey;
        return out;
    }

    boolean wasGrassVertexArrayUpdated() {
        return grassVertexArrayUpdated;
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
        return mVKGrassFloatCount / 8;
    }

    float[] buildSunSpriteVertices(SceneData sd) {
        if (!sd.sunEnabled || !sd.hasSunData) {
            mVKSunFloatCount = 0;
            return mVKSunVerts;
        }
        appendSpriteQuadVertices(mVKSunVerts, 0, sd.sunX, sd.sunY, sd.sunSize, sd.sunAlpha, false, 0.0f);
        mVKSunFloatCount = 30;
        return mVKSunVerts;
    }

    int getSunVertexCount() {
        return mVKSunFloatCount / FLOATS_PER_SPRITE_VERTEX;
    }

    float[] buildMoonSpriteVertices(SceneData sd) {
        if (!sd.moonEnabled || !sd.moonVisible) {
            mVKMoonFloatCount = 0;
            return mVKMoonVerts;
        }
        float alpha = MathUtils.clamp(sd.moonAlpha * sd.moonBrightness, 0.0f, 1.0f);
        if (alpha <= 0.001f) {
            mVKMoonFloatCount = 0;
            return mVKMoonVerts;
        }
        appendSpriteQuadVertices(mVKMoonVerts, 0, sd.moonX, sd.moonY, sd.moonSize, alpha, false, 0.0f);
        mVKMoonFloatCount = 30;
        return mVKMoonVerts;
    }

    int getMoonVertexCount() {
        return mVKMoonFloatCount / FLOATS_PER_SPRITE_VERTEX;
    }

    float[] buildDandelionSpriteVertices(SceneData sd) {
        if (sd.legacyDandelionEnabled) {
            mVKDandelionVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_DANDELION,
                    false, false, true, mVKDandelionVerts);
            mVKDandelionFloatCount = mVKTempSpriteFloatCount;
            return mVKDandelionVerts;
        }
        if (sd.dandelionVisibility <= 0.001f || !sd.dandelionEnabled
            || sd.dandelions == null || sd.dandelions.length == 0) {
            mVKDandelionFloatCount = 0;
            return mVKDandelionVerts;
        }

        int required = sd.dandelions.length * 30;
        if (mVKDandelionVerts.length < required) {
            mVKDandelionVerts = new float[required];
        }

        float[] out = mVKDandelionVerts;
        int cursor = 0;
        for (Dandelion d : sd.dandelions) {
            float sway = (float) Math.sin(d.swayPhase + sd.animNowMs * 0.001f * d.swaySpeed) * 6.0f;
            cursor = appendSpriteQuadVertices(out, cursor, d.x, d.y + sway, d.size,
                    0.9f * sd.dandelionVisibility, true, d.rotationDeg);
            if (cursor > out.length) {
                break;
            }
        }
        mVKDandelionFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    int getDandelionVertexCount() {
        return mVKDandelionFloatCount / FLOATS_PER_SPRITE_VERTEX;
    }

    float[] buildFireflySpriteVertices(SceneData sd) {
        if (sd.legacyFireflyEnabled) {
            mVKFireflyVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_FIREFLY,
                    false, true, true, mVKFireflyVerts);
            mVKFireflyFloatCount = mVKTempSpriteFloatCount;
            return mVKFireflyVerts;
        }
        if (sd.fireflyVisibility <= 0.001f || !sd.fireflyEnabled
            || sd.fireflies == null || sd.fireflies.length == 0) {
            mVKFireflyFloatCount = 0;
            return mVKFireflyVerts;
        }

        int required = sd.fireflies.length * 30;
        if (mVKFireflyVerts.length < required) {
            mVKFireflyVerts = new float[required];
        }

        float[] out = mVKFireflyVerts;
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
        mVKFireflyFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    int getFireflyVertexCount() {
        return mVKFireflyFloatCount / FLOATS_PER_SPRITE_VERTEX;
    }

    float[] buildFireflyFlareSpriteVertices(SceneData sd) {
        if (!sd.legacyFireflyEnabled) {
            mVKFireflyFlareFloatCount = 0;
            return mVKFireflyFlareVerts;
        }
        /*
         * updateState 传 false —— 状态（重生/越界/重新起飞/flare 计时）已经由同一帧的
         * buildFireflySpriteVertices 更新过了。
         *
         * 原来这里也传 true，而 VK 每帧两个都调，于是萤火虫状态每帧被推进两次：
         * flare 计时走双倍、越界重生判断也做两遍。GLES 那边只更新一次，两个渲染器
         * 因此表现不一致 —— 以"一次"为准。
         */
        mVKFireflyFlareVerts = buildLegacySpriteVertices(sd, LEGACY_TYPE_FIREFLY,
                true, true, false, mVKFireflyFlareVerts);
        mVKFireflyFlareFloatCount = mVKTempSpriteFloatCount;
        return mVKFireflyFlareVerts;
    }

    int getFireflyFlareVertexCount() {
        return mVKFireflyFlareFloatCount / FLOATS_PER_SPRITE_VERTEX;
    }

    private float[] buildLegacySpriteVertices(SceneData sd, int legacyTargetType,
            boolean fireflyFlarePass, boolean includeFirefly, boolean updateState,
            float[] reusableBuffer) {
        // 双套粒子：按目标类型选对应粒子集（蒲公英白天 / 萤火虫夜晚），
        // alpha 乘交叉淡入系数（1-transition / transition）
        LegacyParticle[] normalSet = legacyTargetType == LEGACY_TYPE_DANDELION
                ? sd.legacyNormal : sd.legacyNormalNight;
        LegacyParticle[] extrasSet = legacyTargetType == LEGACY_TYPE_DANDELION
                ? sd.legacyExtras : sd.legacyExtrasNight;
        boolean enabled = legacyTargetType == LEGACY_TYPE_DANDELION
                ? sd.legacyDandelionEnabled : sd.legacyFireflyEnabled;
        if (!enabled || normalSet == null || extrasSet == null) {
            mVKTempSpriteFloatCount = 0;
            return reusableBuffer;
        }
        float alphaMultiplier = legacyTargetType == LEGACY_TYPE_DANDELION
                ? sd.legacyDandelionVisibility : sd.legacyFireflyVisibility;

        int required = (LEGACY_MAX_NORMAL + LEGACY_MAX_EXTRAS) * 30;
        float[] out = reusableBuffer;
        if (out.length < required) {
            out = new float[required];
        }

        int cursor = 0;
        long animNowMs = sd.legacyNow;

        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            LegacyParticle p = normalSet[i];
            if (p == null || !p.active) continue;
            cursor = updateAndAppendLegacyParticle(out, cursor, p, sd,
                    legacyTargetType, i, false, animNowMs,
                    fireflyFlarePass, includeFirefly, updateState,
                    alphaMultiplier, normalSet, extrasSet);
            if (cursor > out.length) {
                break;
            }
        }

        if (cursor <= out.length) {
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                LegacyParticle p = extrasSet[i];
                if (p == null || !p.active) continue;
                cursor = updateAndAppendLegacyParticle(out, cursor, p, sd,
                        legacyTargetType, i + 100, true, animNowMs,
                        fireflyFlarePass, includeFirefly, updateState,
                        alphaMultiplier, normalSet, extrasSet);
                if (cursor > out.length) {
                    break;
                }
            }
        }

        mVKTempSpriteFloatCount = Math.max(0, Math.min(cursor, out.length));
        return out;
    }

    private int updateAndAppendLegacyParticle(float[] out, int cursor, LegacyParticle p,
            SceneData sd, int legacyType, int index, boolean isExtras, long animNowMs,
            boolean fireflyFlarePass, boolean includeFirefly, boolean updateState,
            float alphaMultiplier, LegacyParticle[] normalSet, LegacyParticle[] extrasSet) {
        if (updateState) {
            long delta = animNowMs - p.startTime;
            if (delta < 0L) return cursor;

            boolean outOfBounds = isLegacyParticleOutOfBounds(p, legacyType);
            if (outOfBounds) {
                LegacyParticle np = legacyOps.createLegacyParticle(legacyType);
                np.active = !isExtras; // extras 飞走后重置为空闲，等待下一次点击（原版 addTap 循环）
                if (isExtras) {
                    extrasSet[index - 100] = np;
                } else {
                    normalSet[index] = np;
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
            float alpha = (0.2f + 0.8f * flicker) * alphaMultiplier;
            float size = (isExtras ? 48.0f : 72.0f) * (0.8f + 0.4f * flicker);
            return appendSpriteQuadVertices(out, cursor, p.originX, p.originY, size, alpha, false, 0.0f);
        }

        float size = isExtras ? 64.0f : 96.0f;
        return appendSpriteQuadVertices(out, cursor, p.originX, p.originY, size,
                0.9f * alphaMultiplier, true, p.angle);
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
        float v = MathUtils.mix(0.0f, blade.b, brightness);
        if (sd.useGrassTint) {
            h = sd.grassTintH;
            s = sd.grassTintS;
            v = MathUtils.clamp(v * sd.grassTintV, 0.0f, 1.0f);
        }
        if (sd.nightDesaturateGrass && nightDesat > 0.0f) {
            s = MathUtils.mix(s, 0.0f, MathUtils.clamp(nightDesat, 0.0f, 1.0f));
        }

        int color = MathUtils.hsbToRgb(h, s, v);
        float r = Color.red(color) / 255.0f;
        float g = Color.green(color) / 255.0f;
        float b = Color.blue(color) / 255.0f;

        float currentAngle = (float) (Math.PI * 0.5);
        float bottomX = xpos;
        float bottomY = blade.yPos;
        float d = angle * blade.hardness * sd.grassHardnessScale;
        float stepCos = (float) Math.cos(d);
        float stepSin = (float) Math.sin(d);
        float currentCos = 0.0f;
        float currentSin = 1.0f;

        float si = size * scale;
        cursor = putVertex(out, cursor, bottomX - si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 0.0f, 0.0f);
        cursor = putVertex(out, cursor, bottomX + si, bottomY + HALF_TESSELATION, r, g, b, 1.0f, 1.0f, 0.0f);

        for (; size > 0; size--) {
            float lengthX = blade.lengthX * sd.grassHeightScale;
            float lengthY = blade.lengthY * sd.grassHeightScale;
            float topX = bottomX - currentCos * lengthX;
            float topY = bottomY - currentSin * lengthY;
            si = size * scale;
            float spi = si - scale;
            cursor = putVertex(out, cursor, topX - spi, topY, r, g, b, 1.0f, 0.0f, 0.0f);
            cursor = putVertex(out, cursor, topX + spi, topY, r, g, b, 1.0f, 1.0f, 0.0f);
            bottomX = topX;
            bottomY = topY;
            float nextCos = currentCos * stepCos - currentSin * stepSin;
            float nextSin = currentSin * stepCos + currentCos * stepSin;
            currentCos = nextCos;
            currentSin = nextSin;
        }
        return cursor;
    }

    private int computeGrassAppearanceKey(SceneData sd, float brightness, float nightDesat) {
        int key = 17;
        key = 31 * key + (sd.grassEnabled ? 1 : 0);
        key = 31 * key + (sd.useGrassTint ? 1 : 0);
        key = 31 * key + (sd.nightDesaturateGrass ? 1 : 0);
        key = 31 * key + Math.round(sd.xDraw * 2.0f);
        key = 31 * key + Math.round(sd.grassHeightScale * 1000.0f);
        key = 31 * key + Math.round(sd.grassWidthScale * 1000.0f);
        key = 31 * key + Math.round(sd.grassHardnessScale * 1000.0f);
        key = 31 * key + Math.round(brightness * 512.0f);
        key = 31 * key + Math.round(nightDesat * 512.0f);
        key = 31 * key + Math.round(sd.grassTintH * 512.0f);
        key = 31 * key + Math.round(sd.grassTintS * 512.0f);
        key = 31 * key + Math.round(sd.grassTintV * 512.0f);
        return key;
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
        if (cursor + 6 * FLOATS_PER_SPRITE_VERTEX > out.length) return out.length + 1;

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

    /** 精灵顶点格式：x, y, u, v, a。GLES 与 Vulkan 共用同一套。 */
    static final int FLOATS_PER_SPRITE_VERTEX = 5;

    private int putSpriteVertex(float[] out, int cursor, float x, float y, float u, float v, float a) {
        if (cursor + FLOATS_PER_SPRITE_VERTEX > out.length) return out.length + 1;
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        out[cursor++] = a;
        return cursor;
    }

    // ---- night stars（GLES 与 Vulkan 共用同一份批次构建）----

    private static final int STAR_FLOATS_PER_QUAD = 6 * FLOATS_PER_SPRITE_VERTEX;

    /** 4 tint groups; counts are in VERTICES (matches getXxxVertexCount() semantics). */
    static final class StarBatches {
        float[] white = new float[0];
        int whiteCount = 0;
        float[] warm = new float[0];
        int warmCount = 0;
        float[] cool = new float[0];
        int coolCount = 0;
        float[] yellow = new float[0];
        int yellowCount = 0;
    }

    /**
     * Build per-tint star quad batches for the Vulkan path.
     * Matches the GL path: same NightStarsLayer logic, same tint grouping,
     * same starVisibility scaling — each vertex carries its own alpha.
     * The given StarBatches is reused across frames to avoid per-frame allocation.
     */
    StarBatches buildStarBatches(NightStarsLayer layer, SceneData sd, int width, int height,
            StarBatches batches) {
        final StarBatches b = batches != null ? batches : new StarBatches();
        b.whiteCount = 0;
        b.warmCount = 0;
        b.coolCount = 0;
        b.yellowCount = 0;
        if (layer == null || sd == null || sd.starVisibility <= 0.001f) {
            return b;
        }
        layer.draw(sd.animNowMs, width, height, new NightStarsLayer.SpriteDrawer() {
            @Override
            public void draw(int tintType, float cx, float cy, float size, float alpha, float shift) {
                float finalAlpha = alpha * sd.starVisibility;
                if (finalAlpha <= 0.001f) {
                    return;
                }
                int group = starTextureGroupForTint(tintType, shift);
                float[] arr;
                int floatCursor;
                if (group == 1) {
                    arr = b.warm; floatCursor = b.warmCount * FLOATS_PER_SPRITE_VERTEX;
                } else if (group == 2) {
                    arr = b.cool; floatCursor = b.coolCount * FLOATS_PER_SPRITE_VERTEX;
                } else if (group == 3) {
                    arr = b.yellow; floatCursor = b.yellowCount * FLOATS_PER_SPRITE_VERTEX;
                } else {
                    arr = b.white; floatCursor = b.whiteCount * FLOATS_PER_SPRITE_VERTEX;
                }
                // appendStarQuad may grow the array; keep the returned reference
                arr = appendStarQuad(arr, floatCursor, cx, cy, size, finalAlpha);
                int newCount = floatCursor / FLOATS_PER_SPRITE_VERTEX + 6;
                if (group == 1) {
                    b.warm = arr; b.warmCount = newCount;
                } else if (group == 2) {
                    b.cool = arr; b.coolCount = newCount;
                } else if (group == 3) {
                    b.yellow = arr; b.yellowCount = newCount;
                } else {
                    b.white = arr; b.whiteCount = newCount;
                }
            }
        });
        return b;
    }

    /** Same tint-grouping as GrassStarRenderer.textureGroupForTint (0=white 1=warm 2=cool 3=yellow). */
    private int starTextureGroupForTint(int tintType, float shift) {
        switch (tintType) {
            case NightStarsLayer.STAR_TINT_RED:
                return shift > 0.6f ? 1 : 3;
            case NightStarsLayer.STAR_TINT_BLUE:
                return shift > 0.45f ? 2 : 0;
            case NightStarsLayer.STAR_TINT_YELLOW:
                return shift > 0.35f ? 3 : 0;
            case NightStarsLayer.STAR_TINT_WHITE:
            default:
                return 0;
        }
    }

    /** Appends one star quad; returns the (possibly grown) array. */
    private float[] appendStarQuad(float[] arr, int cursor, float cx, float cy, float size, float alpha) {
        int required = cursor + STAR_FLOATS_PER_QUAD;
        if (arr.length < required) {
            int newSize = arr.length == 0 ? 4096 : arr.length;
            while (newSize < required) {
                newSize *= 2;
            }
            float[] expanded = new float[newSize];
            if (cursor > 0) {
                System.arraycopy(arr, 0, expanded, 0, cursor);
            }
            arr = expanded;
        }

        float half = size * 0.5f;
        float x0 = cx - half, y0 = cy - half;
        float x1 = cx - half, y1 = cy + half;
        float x2 = cx + half, y2 = cy + half;
        float x3 = cx + half, y3 = cy - half;

        cursor = putSpriteVertex(arr, cursor, x0, y0, 0.0f, 1.0f, alpha);
        cursor = putSpriteVertex(arr, cursor, x1, y1, 0.0f, 0.0f, alpha);
        cursor = putSpriteVertex(arr, cursor, x2, y2, 1.0f, 0.0f, alpha);
        cursor = putSpriteVertex(arr, cursor, x0, y0, 0.0f, 1.0f, alpha);
        cursor = putSpriteVertex(arr, cursor, x2, y2, 1.0f, 0.0f, alpha);
        cursor = putSpriteVertex(arr, cursor, x3, y3, 1.0f, 1.0f, alpha);
        return arr;
    }
}
