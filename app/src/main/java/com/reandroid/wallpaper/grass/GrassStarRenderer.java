package com.reandroid.wallpaper.grass;

import android.opengl.GLES20;

final class GrassStarRenderer {

    private static final int STAR_TEXTURE_GROUP_COUNT = 4;
    private static final int STAR_ALPHA_BIN_COUNT = 8;
    private static final int STAR_BATCH_GROUP_COUNT = STAR_TEXTURE_GROUP_COUNT * STAR_ALPHA_BIN_COUNT;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int FLOATS_PER_STAR = 6 * FLOATS_PER_VERTEX;

    interface SolidColorTextureFactory {
        int create(byte r, byte g, byte b, byte a);
    }

    interface RenderOps {
        void useBackgroundProgram();

        void setAlphaBlend();
    }

    private final NightStarsLayer nightStarsLayer = new NightStarsLayer();

    private int width;
    private int height;
    private int bgMatrixHandle = -1;

    private int texStarWhite;
    private int texStarWarm;
    private int texStarCool;
    private int texStarYellow;

    private final float[][] starBatchVertices = new float[STAR_BATCH_GROUP_COUNT][];
    private final int[] starBatchFloatCounts = new int[STAR_BATCH_GROUP_COUNT];

    void setViewport(int width, int height) {
        this.width = width;
        this.height = height;
    }

    void setBackgroundMatrixHandle(int bgMatrixHandle) {
        this.bgMatrixHandle = bgMatrixHandle;
    }

    void loadTextures(SolidColorTextureFactory solidColorFactory) {
        texStarWhite = solidColorFactory.create((byte) 255, (byte) 255, (byte) 255, (byte) 255);
        texStarWarm = solidColorFactory.create((byte) 255, (byte) 168, (byte) 152, (byte) 255);
        texStarCool = solidColorFactory.create((byte) 158, (byte) 202, (byte) 255, (byte) 255);
        texStarYellow = solidColorFactory.create((byte) 255, (byte) 238, (byte) 170, (byte) 255);
    }

    void releaseTextures() {
        int[] tex = new int[]{texStarWhite, texStarWarm, texStarCool, texStarYellow};
        GLES20.glDeleteTextures(tex.length, tex, 0);
        texStarWhite = 0;
        texStarWarm = 0;
        texStarCool = 0;
        texStarYellow = 0;
    }

    void drawNightStars(SceneData sd, GrassSpriteRenderer spriteRenderer, RenderOps renderOps) {
        if (sd.starVisibility <= 0.001f || texStarWhite == 0) {
            return;
        }

        renderOps.useBackgroundProgram();
        renderOps.setAlphaBlend();
        GLES20.glUniformMatrix4fv(bgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        clearBatchCounters();
        nightStarsLayer.draw(sd.animNowMs, width, height, new NightStarsLayer.SpriteDrawer() {
            @Override
            public void draw(int tintType, float cx, float cy, float size, float alpha, float shift) {
                float finalAlpha = alpha * sd.starVisibility;
                if (finalAlpha <= 0.001f) {
                    return;
                }
                int textureGroup = textureGroupForTint(tintType, shift);
                int alphaBin = alphaBinFor(finalAlpha);
                int group = (textureGroup * STAR_ALPHA_BIN_COUNT) + alphaBin;
                appendStarQuad(group, cx, cy, size);
            }
        });

        for (int textureGroup = 0; textureGroup < STAR_TEXTURE_GROUP_COUNT; textureGroup++) {
            int texture = textureForGroup(textureGroup);
            for (int alphaBin = 0; alphaBin < STAR_ALPHA_BIN_COUNT; alphaBin++) {
                int group = (textureGroup * STAR_ALPHA_BIN_COUNT) + alphaBin;
                int floatCount = starBatchFloatCounts[group];
                if (floatCount <= 0) {
                    continue;
                }
                spriteRenderer.drawBatch(texture, starBatchVertices[group], floatCount, alphaForBin(alphaBin));
            }
        }
    }

    private int textureGroupForTint(int tintType, float shift) {
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

    private int textureForGroup(int group) {
        switch (group) {
            case 1:
                return texStarWarm;
            case 2:
                return texStarCool;
            case 3:
                return texStarYellow;
            case 0:
            default:
                return texStarWhite;
        }
    }

    private void clearBatchCounters() {
        for (int i = 0; i < starBatchFloatCounts.length; i++) {
            starBatchFloatCounts[i] = 0;
        }
    }

    private int alphaBinFor(float alpha) {
        int idx = (int) (alpha * (STAR_ALPHA_BIN_COUNT - 1) + 0.5f);
        if (idx < 0) {
            return 0;
        }
        if (idx >= STAR_ALPHA_BIN_COUNT) {
            return STAR_ALPHA_BIN_COUNT - 1;
        }
        return idx;
    }

    private float alphaForBin(int alphaBin) {
        if (STAR_ALPHA_BIN_COUNT <= 1) {
            return 1.0f;
        }
        return alphaBin / (float) (STAR_ALPHA_BIN_COUNT - 1);
    }

    private void appendStarQuad(int group, float cx, float cy, float size) {
        ensureGroupCapacity(group, FLOATS_PER_STAR);
        float[] out = starBatchVertices[group];
        int cursor = starBatchFloatCounts[group];

        float half = size * 0.5f;
        float x0 = cx - half;
        float y0 = cy - half;
        float x1 = cx - half;
        float y1 = cy + half;
        float x2 = cx + half;
        float y2 = cy + half;
        float x3 = cx + half;
        float y3 = cy - half;

        cursor = putVertex(out, cursor, x0, y0, 0.0f, 1.0f);
        cursor = putVertex(out, cursor, x1, y1, 0.0f, 0.0f);
        cursor = putVertex(out, cursor, x2, y2, 1.0f, 0.0f);

        cursor = putVertex(out, cursor, x0, y0, 0.0f, 1.0f);
        cursor = putVertex(out, cursor, x2, y2, 1.0f, 0.0f);
        cursor = putVertex(out, cursor, x3, y3, 1.0f, 1.0f);

        starBatchFloatCounts[group] = cursor;
    }

    private int putVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        return cursor;
    }

    private void ensureGroupCapacity(int group, int appendFloatCount) {
        int required = starBatchFloatCounts[group] + appendFloatCount;
        float[] current = starBatchVertices[group];
        if (current != null && current.length >= required) {
            return;
        }

        int newSize = current == null ? 4096 : current.length;
        while (newSize < required) {
            newSize *= 2;
        }

        float[] expanded = new float[newSize];
        if (current != null && starBatchFloatCounts[group] > 0) {
            System.arraycopy(current, 0, expanded, 0, starBatchFloatCounts[group]);
        }
        starBatchVertices[group] = expanded;
    }
}
