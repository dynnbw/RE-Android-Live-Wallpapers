package com.reandroid.wallpaper.grass;

import com.reandroid.settings.WallpaperSettings;

import java.util.Random;

/**
 * Night sky star field for Grass wallpaper.
 * Kept independent from GrassScene so rendering logic can evolve without touching scene state.
 */
class NightStarsLayer {

    static final int STAR_TINT_WHITE = 0;
    static final int STAR_TINT_RED = 1;
    static final int STAR_TINT_BLUE = 2;
    static final int STAR_TINT_YELLOW = 3;

    interface SpriteDrawer {
        void draw(int tintType, float cx, float cy, float size, float alpha, float shift);
    }

    private static final int DEFAULT_STAR_COUNT = 2048;

    private Star[] mStars = new Star[0];
    private int mLastWidth = -1;
    private int mLastHeight = -1;
    private int mLastConfiguredCount = -1;

    void draw(long animNowMs, int width, int height, SpriteDrawer drawer) {
        if (width <= 0 || height <= 0 || drawer == null) {
            return;
        }
        int configuredCount = WallpaperSettings.getGrassStarCount(DEFAULT_STAR_COUNT);
        ensureStars(width, height, configuredCount);
        float t = animNowMs * 0.001f;
        for (Star s : mStars) {
            float x = s.xNorm * width;
            float y = s.yNorm * height;
            float twinkle = 0.65f + 0.35f * (float) Math.sin((t * s.twinkleSpeed) + s.twinklePhase);
            float alpha = clamp(s.baseAlpha * twinkle, 0.03f, 0.82f);
            float shift = 0.5f + 0.5f * (float) Math.sin((t * s.shiftSpeed) + s.shiftPhase);
            drawer.draw(s.tintType, x, y, s.sizePx, alpha, shift);
        }
    }

    private void ensureStars(int width, int height, int configuredCount) {
        int count = clamp(configuredCount, 0, 10000);
        if (width == mLastWidth && height == mLastHeight
                && count == mLastConfiguredCount && mStars.length > 0) {
            return;
        }
        mLastWidth = width;
        mLastHeight = height;
        mLastConfiguredCount = count;
        mStars = new Star[count];

        long seed = (((long) width) << 32) ^ (height * 1103515245L + 12345L);
        Random random = new Random(seed);

        for (int i = 0; i < count; i++) {
            Star s = new Star();
            s.xNorm = random.nextFloat();
            // Full-height coverage to avoid missing stars near bottom on non-9:16 screens,
            // while keeping a slight upper-sky density bias.
            float y = random.nextFloat();
            s.yNorm = clamp((float) Math.pow(y, 1.15f), 0.0f, 1.0f);
            s.sizePx = 1.2f + random.nextFloat() * 2.2f;
            s.baseAlpha = 0.22f + random.nextFloat() * 0.58f;
            s.twinklePhase = random.nextFloat() * 6.2831855f;
            s.twinkleSpeed = 0.7f + random.nextFloat() * 2.2f;
            s.shiftPhase = random.nextFloat() * 6.2831855f;
            s.shiftSpeed = 0.12f + random.nextFloat() * 0.38f;
            s.tintType = pickTint(random);
            mStars[i] = s;
        }
    }

    private static int pickTint(Random random) {
        float r = random.nextFloat();
        if (r < 0.86f) {
            return STAR_TINT_WHITE;
        }
        if (r < 0.91f) {
            return STAR_TINT_RED;
        }
        if (r < 0.95f) {
            return STAR_TINT_BLUE;
        }
        return STAR_TINT_YELLOW;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class Star {
        float xNorm;
        float yNorm;
        float sizePx;
        float baseAlpha;
        float twinklePhase;
        float twinkleSpeed;
        float shiftPhase;
        float shiftSpeed;
        int tintType;
    }
}
