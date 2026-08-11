package com.reandroid.wallpaper.grass;

import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_INTERVAL_VARIANCE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_FLARE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_INTERVAL;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_DANDELION;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_FIREFLY;

final class GrassLegacyParticleRenderer {
    private static final int FIREFLY_ALPHA_BIN_COUNT = 8;
    private static final int BATCH_GROUP_DANDELION = 0;
    private static final int BATCH_GROUP_FIREFLY1_START = 1;
    private static final int BATCH_GROUP_FIREFLY2_START = BATCH_GROUP_FIREFLY1_START + FIREFLY_ALPHA_BIN_COUNT;
    private static final int BATCH_GROUP_COUNT = BATCH_GROUP_FIREFLY2_START + FIREFLY_ALPHA_BIN_COUNT;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int FLOATS_PER_QUAD = 6 * FLOATS_PER_VERTEX;

    private final GrassScene mScene;
    private final GrassSpriteRenderer mSpriteRenderer;

    private final float[][] mBatchVertices = new float[BATCH_GROUP_COUNT][];
    private final int[] mBatchFloatCounts = new int[BATCH_GROUP_COUNT];
    private float mDandelionAlpha = 1.0f; // 交叉淡入淡出：蒲公英 = 1 - transition
    private float mFireflyAlpha = 0.0f;   // 萤火虫 = transition

    GrassLegacyParticleRenderer(GrassScene scene, GrassSpriteRenderer spriteRenderer) {
        mScene = scene;
        mSpriteRenderer = spriteRenderer;
    }

    void draw(SceneData sd, int width, int height, int texDandelion, int texFirefly1, int texFirefly2) {
        clearBatchCounts();

        long animNowMs = sd.legacyNow;
        // 双套粒子按夜空权重交叉淡入淡出（蒲公英白天、萤火虫夜晚）
        mDandelionAlpha = 1.0f - sd.legacyTransition;
        mFireflyAlpha = sd.legacyTransition;

        drawParticleSet(sd.legacyNormal, LEGACY_TYPE_DANDELION, false, animNowMs, width, height);
        drawParticleSet(sd.legacyExtras, LEGACY_TYPE_DANDELION, true, animNowMs, width, height);
        drawParticleSet(sd.legacyNormalNight, LEGACY_TYPE_FIREFLY, false, animNowMs, width, height);
        drawParticleSet(sd.legacyExtrasNight, LEGACY_TYPE_FIREFLY, true, animNowMs, width, height);

        flushBatches(texDandelion, texFirefly1, texFirefly2);
    }

    private void clearBatchCounts() {
        for (int i = 0; i < mBatchFloatCounts.length; i++) {
            mBatchFloatCounts[i] = 0;
        }
    }

    private void drawParticleSet(LegacyParticle[] particles, int legacyType,
            boolean isExtras, long animNowMs, int width, int height) {
        if (particles == null) return;

        for (int i = 0; i < particles.length; i++) {
            LegacyParticle p = particles[i];
            if (p == null || !p.active) continue;
            long delta = animNowMs - p.startTime;
            if (delta < 0L) continue;
            if (isOutOfBounds(p, legacyType, width, height)) {
                LegacyParticle np = mScene.createLegacyParticle(legacyType);
                np.active = !isExtras; // extras 飞走后重置为空闲，等待下一次点击（原版 addTap 循环）
                particles[i] = np;
                p = np;
                delta = animNowMs - p.startTime;
                if (delta < 0L) continue;
            }
            if ((p.stayEndTime - animNowMs) <= 0L) {
                if (legacyType == LEGACY_TYPE_DANDELION) {
                    mScene.flyLegacyDandelion(p, false);
                } else {
                    mScene.flyLegacyFirefly(p, false);
                }
            }
            p.startTime = animNowMs;
            drawParticle(p, legacyType, i + (isExtras ? 100 : 0), isExtras, animNowMs);
        }
    }

    private static boolean isOutOfBounds(LegacyParticle p, int legacyType, int width, int height) {
        if (legacyType == LEGACY_TYPE_DANDELION) {
            return p.originX < 0 || p.originX > width * 2 || p.originY < 0 || p.originY > height;
        }
        return p.originX < 0 || p.originX > width * 2 || p.originY < 0;
    }

    private void drawParticle(LegacyParticle p, int legacyType, int index,
            boolean isExtras, long animNowMs) {
        if (legacyType == LEGACY_TYPE_FIREFLY) {
            long interval = p.flareEndTime - p.silentEndTime;
            if (animNowMs >= p.flareEndTime && interval > 0L) {
                p.silentEndTime = animNowMs
                        + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE)
                        * LEGACY_MAX_INTERVAL);
            } else if (animNowMs >= p.silentEndTime && interval < 0L) {
                p.flareEndTime = animNowMs + LEGACY_MAX_FLARE;
            }
            int tex = (animNowMs < p.flareEndTime) ? 1 : 0; // 1=flare, 0=normal
            float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + index * 1234) * 0.002);
            float alpha = 0.2f + 0.8f * flicker;
            float size = (isExtras ? 48.0f : 72.0f) * (0.8f + 0.4f * flicker);
            int alphaBin = alphaBinForLegacy(alpha);
            int group = (tex == 0)
                    ? (BATCH_GROUP_FIREFLY1_START + alphaBin)
                    : (BATCH_GROUP_FIREFLY2_START + alphaBin);
            appendSpriteQuad(group, p.originX, p.originY, size, false, 0.0f);
        } else {
            float size = isExtras ? 64.0f : 96.0f;
            appendSpriteQuad(BATCH_GROUP_DANDELION, p.originX, p.originY, size, true, p.angle);
        }
    }

    private void flushBatches(int texDandelion, int texFirefly1, int texFirefly2) {
        if (texDandelion != 0 && mBatchFloatCounts[BATCH_GROUP_DANDELION] > 0
                && mDandelionAlpha > 0.01f) {
            mSpriteRenderer.drawBatch(texDandelion,
                    mBatchVertices[BATCH_GROUP_DANDELION],
                    mBatchFloatCounts[BATCH_GROUP_DANDELION], 0.9f * mDandelionAlpha);
        }
        if (texFirefly1 != 0 && mFireflyAlpha > 0.01f) {
            for (int bin = 0; bin < FIREFLY_ALPHA_BIN_COUNT; bin++) {
                int group = BATCH_GROUP_FIREFLY1_START + bin;
                int floatCount = mBatchFloatCounts[group];
                if (floatCount <= 0) continue;
                mSpriteRenderer.drawBatch(texFirefly1, mBatchVertices[group],
                        floatCount, alphaForLegacyBin(bin) * mFireflyAlpha);
            }
        }
        if (texFirefly2 != 0 && mFireflyAlpha > 0.01f) {
            for (int bin = 0; bin < FIREFLY_ALPHA_BIN_COUNT; bin++) {
                int group = BATCH_GROUP_FIREFLY2_START + bin;
                int floatCount = mBatchFloatCounts[group];
                if (floatCount <= 0) continue;
                mSpriteRenderer.drawBatch(texFirefly2, mBatchVertices[group],
                        floatCount, alphaForLegacyBin(bin) * mFireflyAlpha);
            }
        }
    }

    private static int alphaBinForLegacy(float alpha) {
        int idx = (int) (alpha * (FIREFLY_ALPHA_BIN_COUNT - 1) + 0.5f);
        if (idx < 0) return 0;
        if (idx >= FIREFLY_ALPHA_BIN_COUNT) return FIREFLY_ALPHA_BIN_COUNT - 1;
        return idx;
    }

    private static float alphaForLegacyBin(int alphaBin) {
        if (FIREFLY_ALPHA_BIN_COUNT <= 1) return 1.0f;
        return alphaBin / (float) (FIREFLY_ALPHA_BIN_COUNT - 1);
    }

    private void appendSpriteQuad(int group, float cx, float cy, float size, boolean flipV, float rotationDeg) {
        ensureGroupCapacity(group, FLOATS_PER_QUAD);
        float[] out = mBatchVertices[group];
        int cursor = mBatchFloatCounts[group];

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

        cursor = putVertex(out, cursor, x0, y0, 0.0f, v0);
        cursor = putVertex(out, cursor, x1, y1, 0.0f, v1);
        cursor = putVertex(out, cursor, x2, y2, 1.0f, v1);
        cursor = putVertex(out, cursor, x0, y0, 0.0f, v0);
        cursor = putVertex(out, cursor, x2, y2, 1.0f, v1);
        cursor = putVertex(out, cursor, x3, y3, 1.0f, v0);

        mBatchFloatCounts[group] = cursor;
    }

    private static int putVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        return cursor;
    }

    private void ensureGroupCapacity(int group, int appendFloats) {
        int required = mBatchFloatCounts[group] + appendFloats;
        float[] current = mBatchVertices[group];
        if (current != null && current.length >= required) return;
        int newSize = current == null ? 2048 : current.length;
        while (newSize < required) newSize *= 2;
        float[] expanded = new float[newSize];
        if (current != null && mBatchFloatCounts[group] > 0) {
            System.arraycopy(current, 0, expanded, 0, mBatchFloatCounts[group]);
        }
        mBatchVertices[group] = expanded;
    }
}
