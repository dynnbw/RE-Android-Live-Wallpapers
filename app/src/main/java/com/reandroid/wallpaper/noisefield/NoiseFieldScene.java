package com.reandroid.wallpaper.noisefield;

import android.content.SharedPreferences;
import android.view.MotionEvent;

import com.reandroid.utils.MathUtils;

import java.util.Random;

final class NoiseFieldScene {
    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;
    private static final float BASE_FRAME_MS = 35f;

    private final Random mRandom = new Random();
    private final int[] p = new int[B + B + 2];
    private final float[][] g2 = new float[B + B + 2][2];

    private float[] mDotPositions;
    private float[] mDotSpeeds;
    private float[] mDotAlpha;
    private float[] mDotAlphaStart;
    private float[] mDotWander;
    private int[] mDotLife;
    private int[] mDotDeath;

    private int mDotCount = 83;
    private float mSpeedMultiplier = 1.0f;
    private float mTouchForce = 0.20f;
    private boolean mParamsDirty;

    private boolean mTouchDown = false;
    private float mTouchInfluence = 0f;
    private float mTouchX = 0f;
    private float mTouchY = 0f;
    private long mLastFrameTimeMs = 0L;
    private float mTouchFrameScale = 1f;

    private int mWidth;
    private int mHeight;

    NoiseFieldScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        allocateArrays();
    }

    void setPluginPrefs(SharedPreferences prefs) {
        mDotCount = MathUtils.clamp(prefs.getInt("noisefield_dot_count", 83), 30, 150);
        mSpeedMultiplier = prefs.getInt("noisefield_speed", 100) / 100.0f;
        mTouchForce = prefs.getInt("noisefield_touch_force", 20) / 100.0f;
        mParamsDirty = true;
    }

    boolean consumeParamsDirty() {
        boolean v = mParamsDirty;
        mParamsDirty = false;
        return v;
    }

    int getDotCount() { return mDotCount; }

    void allocateArrays() {
        int c = mDotCount;
        mDotPositions = new float[c * 3];
        mDotSpeeds = new float[c];
        mDotAlpha = new float[c];
        mDotAlphaStart = new float[c];
        mDotWander = new float[c];
        mDotLife = new int[c];
        mDotDeath = new int[c];
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    // --- Getters for GL ---

    float[] getDotPositions() {
        return mDotPositions;
    }

    float[] getDotSpeeds() {
        return mDotSpeeds;
    }

    float[] getDotAlpha() {
        return mDotAlpha;
    }

    boolean isTouchDown() {
        return mTouchDown;
    }

    float getTouchInfluence() {
        return mTouchInfluence;
    }

    float getTouchX() {
        return mTouchX;
    }

    float getTouchY() {
        return mTouchY;
    }

    float getTouchFrameScale() {
        return mTouchFrameScale;
    }

    // --- Lifecycle ---

    void start() {
        initNoise();
    }

    void positionParticles() {
        for (int i = 0; i < mDotCount; i++) {
            int idx = i * 3;
            mDotPositions[idx] = rand(-1.0f, 1.0f);
            mDotPositions[idx + 1] = rand(-1.0f, 1.0f);
            mDotPositions[idx + 2] = 0.0f;
            mDotSpeeds[i] = rand(0.0002f, 0.02f);
            mDotWander[i] = rand(0.50f, 1.5f);
            mDotDeath[i] = 0;
            mDotLife[i] = randInt(300, 800);
            mDotAlphaStart[i] = rand(0.01f, 1.0f);
            mDotAlpha[i] = mDotAlphaStart[i];
        }
    }

    void updateParticles() {
        for (int i = 0; i < mDotCount; i++) {
            int idx = i * 3;

            if (mDotLife[i] < 0 || mDotPositions[idx] < -1.2f || mDotPositions[idx] > 1.2f
                    || mDotPositions[idx + 1] < -1.7f || mDotPositions[idx + 1] > 1.7f) {
                mDotPositions[idx] = rand(-1.0f, 1.0f);
                mDotPositions[idx + 1] = rand(-1.0f, 1.0f);
                mDotSpeeds[i] = rand(0.0002f, 0.02f);
                mDotWander[i] = rand(0.50f, 1.5f);
                mDotDeath[i] = 0;
                mDotLife[i] = randInt(300, 800);
                mDotAlphaStart[i] = rand(0.01f, 1.0f);
                mDotAlpha[i] = mDotAlphaStart[i];
            }

            float touchDist = (float) Math.sqrt(Math.pow(mTouchX - mDotPositions[idx], 2)
                    + Math.pow(mTouchY - mDotPositions[idx + 1], 2));

            float noiseval = noisef2(mDotPositions[idx], mDotPositions[idx + 1]);
            if (mTouchDown || mTouchInfluence > 0.0f) {
                if (mTouchDown) {
                    mTouchInfluence = 1.0f;
                }
                float rads = (float) Math.atan2(mTouchX - mDotPositions[idx] + noiseval,
                        mTouchY - mDotPositions[idx + 1] + noiseval);
                float speed;
                if (touchDist != 0) {
                    speed = ((0.25f + (noiseval * mDotSpeeds[i] + 0.01f)) / touchDist * 0.3f);
                    speed = speed * mTouchInfluence;
                } else {
                    speed = 0.3f;
                }
                mDotPositions[idx] += Math.cos(rads) * speed * mTouchForce * mTouchFrameScale * mSpeedMultiplier;
                mDotPositions[idx + 1] += Math.sin(rads) * speed * mTouchForce * mTouchFrameScale * mSpeedMultiplier;
            }

            float angle = 360f * noiseval * mDotWander[i];
            float speed = noiseval * mDotSpeeds[i] + 0.01f;
            float rads = (float) (angle * Math.PI / 180.0);

            mDotPositions[idx] += Math.cos(rads) * speed * 0.33f * mTouchFrameScale * mSpeedMultiplier;
            mDotPositions[idx + 1] += Math.sin(rads) * speed * 0.33f * mTouchFrameScale * mSpeedMultiplier;

            mDotLife[i]--;
            mDotDeath[i]++;

            float dist = (float) Math.sqrt(mDotPositions[idx] * mDotPositions[idx]
                    + mDotPositions[idx + 1] * mDotPositions[idx + 1]);
            if (dist < 0.95f) {
                dist = 0;
                mDotAlphaStart[i] *= (1 - dist);
            } else {
                dist = dist - 0.95f;
                if (mDotAlphaStart[i] < 1.0f) {
                    mDotAlphaStart[i] += 0.01f;
                    mDotAlphaStart[i] *= (1 - dist);
                }
            }

            if (mDotDeath[i] < 101) {
                mDotAlpha[i] = (mDotAlphaStart[i]) * (mDotDeath[i]) / 100.0f;
            } else if (mDotLife[i] < 101) {
                mDotAlpha[i] = mDotAlpha[i] * mDotLife[i] / 100.0f;
            } else {
                mDotAlpha[i] = mDotAlphaStart[i];
            }
        }

        if (mTouchInfluence > 0) {
            mTouchInfluence -= 0.01f * mTouchFrameScale;
            if (mTouchInfluence < 0f) mTouchInfluence = 0f;
        }
    }

    void updateFrameScale(long timeMs) {
        if (mLastFrameTimeMs == 0L) {
            mLastFrameTimeMs = timeMs;
            mTouchFrameScale = 1f;
            return;
        }
        long delta = timeMs - mLastFrameTimeMs;
        if (delta < 1) delta = 1;
        float scale = delta / BASE_FRAME_MS;
        if (scale < 0.25f) scale = 0.25f;
        if (scale > 3.0f) scale = 3.0f;
        mTouchFrameScale = scale;
        mLastFrameTimeMs = timeMs;
    }

    void onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP || act == MotionEvent.ACTION_CANCEL) {
            if (mTouchDown) {
                mTouchDown = false;
            }
            return;
        } else if (act == MotionEvent.ACTION_DOWN
                || act == MotionEvent.ACTION_MOVE
                || act == MotionEvent.ACTION_POINTER_DOWN) {
            if (!mTouchDown) {
                mTouchDown = true;
            }
            if (ev.getPointerCount() > 0) {
                touch(ev.getX(0), ev.getY(0));
            }
        }
    }

    void onCommandTouch(int x, int y) {
        touch(x, y);
        mTouchDown = false;
        mTouchInfluence = 1.0f;
    }

    // --- Perlin noise ---

    void initNoise() {
        for (int i = 0; i < B; i++) {
            p[i] = i;
            g2[i][0] = rand(-1f, 1f);
            g2[i][1] = rand(-1f, 1f);
            normalize2(g2[i]);
        }

        for (int i = B - 1; i >= 0; i--) {
            int k = p[i];
            int j = mRandom.nextInt(B);
            p[i] = p[j];
            p[j] = k;
        }

        for (int i = 0; i < B + 2; i++) {
            p[B + i] = p[i];
            g2[B + i][0] = g2[i][0];
            g2[B + i][1] = g2[i][1];
        }
    }

    float noisef2(float x, float y) {
        int bx0, bx1, by0, by1, b00, b10, b01, b11;
        float rx0, rx1, ry0, ry1, sx, sy, a, b, t, u, v;
        float[] q;

        t = x + N;
        bx0 = ((int) t) & BM;
        bx1 = (bx0 + 1) & BM;
        rx0 = t - (int) t;
        rx1 = rx0 - 1.0f;

        t = y + N;
        by0 = ((int) t) & BM;
        by1 = (by0 + 1) & BM;
        ry0 = t - (int) t;
        ry1 = ry0 - 1.0f;

        int i = p[bx0];
        int j = p[bx1];

        b00 = p[i + by0];
        b10 = p[j + by0];
        b01 = p[i + by1];
        b11 = p[j + by1];

        sx = noiseSCurve(rx0);
        sy = noiseSCurve(ry0);

        q = g2[b00];
        u = rx0 * q[0] + ry0 * q[1];
        q = g2[b10];
        v = rx1 * q[0] + ry0 * q[1];
        a = MathUtils.mix(u, v, sx);

        q = g2[b01];
        u = rx0 * q[0] + ry1 * q[1];
        q = g2[b11];
        v = rx1 * q[0] + ry1 * q[1];
        b = MathUtils.mix(u, v, sx);

        return 1.5f * MathUtils.mix(a, b, sy);
    }

    private static float noiseSCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static void normalize2(float[] v) {
        float s = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        v[0] = v[0] / s;
        v[1] = v[1] / s;
    }

    // --- Touch ---

    private void touch(float x, float y) {
        boolean landscape = mWidth > mHeight;
        float wRatio;
        float hRatio;
        if (!landscape) {
            wRatio = 1.0f;
            hRatio = (float) mHeight / (float) mWidth;
        } else {
            hRatio = 1.0f;
            wRatio = (float) mWidth / (float) mHeight;
        }

        mTouchInfluence = 1.0f;
        mTouchX = x / mWidth * wRatio * 2f - wRatio;
        mTouchY = -(y / mHeight * hRatio * 2f - hRatio);
    }

    // --- Random ---

    float rand(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    int randInt(int min, int max) {
        return min + mRandom.nextInt(max - min + 1);
    }
}
