package com.reandroid.wallpaper.forest;

import java.util.Random;

/**
 * Forest 壁纸场景逻辑层。
 * 茎摇摆物理、触摸区域检测、粒子生成与生命周期。
 */
final class ForestScene {

    // ---- constants from original Config ----
    static final float SCREEN_W = 240.0f;
    static final float SCREEN_H = 320.0f;
    static final float STEM_WIDTH = 36.0f;
    static final float STEM_HEIGHT = 320.0f;
    static final int MAX_STEM = 6;
    static final int MAX_PARTICLES = 200;
    static final int FRAME_A = 320;
    static final int PARTICLE_FRAME = 30;
    static final float PARTICLE_GAP_X = 10.0f;
    static final float PARTICLE_GAP_Y = 10.0f;
    static final float PARTICLE_LANDSCAPE_Y = -30.0f;
    static final float PARTICLE_MOVE_Y = 3.0f;
    static final float PARTICLE_STRIDE_Y = 0.8f;
    static final float PARTICLE_RANDOM = 0.15f;
    static final float PARTICLE_RANDOM_LIMIT = 0.25f;
    static final float LANDSCAPE_Y = 40.0f;

    static final float[] STEM_DEFAULT_X = {
        15.0f, 80.0f, 140.0f, 190.0f, 245.0f, 310.0f,
        22.4f, 90.0f, 179.0f, 251.0f, 326.0f, 412.0f
    };
    static final int[] STEM_FRAME = {0, 70, 35, 150, 100, 175};

    static final float[] PARTICLE_OUT_WEIGHT = {
        0.013f,0.012f,0.01f,0.01f,0.012f,0.013f,0.01f,0.009f,0.01f,0.012f,
        0.012f,0.01f,0.01f,0.013f,0.012f,0.009f,0.01f,0.01f,0.013f,0.01f,
        0.013f,0.01f,0.009f,0.01f,0.012f,0.01f,0.01f,0.013f,0.01f,0.013f,
        0.009f,0.01f,0.01f,0.012f,0.009f,0.013f,0.012f,0.012f,0.01f,0.01f,
        0.01f,0.012f,0.01f,0.01f,0.01f,0.012f,0.009f,0.01f,0.012f,0.013f
    };
    static final float[] PARTICLE_SCALE = {
        1.0f,0.5f,0.7f,0.5f,1.0f,0.7f,0.7f,0.5f,1.0f,0.5f,
        1.0f,0.7f,0.7f,1.0f,0.5f,0.7f,0.5f,0.7f,0.7f,1.0f,
        1.0f,0.5f,0.7f,1.0f,0.5f,0.7f,1.0f,0.5f,0.7f,1.0f,
        1.0f,0.5f,0.7f,1.0f,0.5f,0.7f,1.0f,0.5f,0.7f,0.5f,
        0.7f,0.5f,0.7f,0.7f,0.5f,0.5f,1.0f,0.5f,1.0f,0.5f
    };
    static final float[] PARTICLE_START_Y = {
        80f,100f,128f,164f,181.2f,112f,128.8f,168f,180f,189.6f,
        88f,100f,128f,140f,169.6f,116f,140f,144f,168f,182f,
        96f,117.2f,140f,212f,224f,104f,116f,132f,164f,168f,
        108f,132f,138f,154f,188f,94f,128f,152f,164f,208f,
        112f,140f,148.8f,176.8f,192f,108f,116f,128f,160f,184f
    };

    // ---- data types ----
    static final class Stem {
        int texIndex; float angle; int dir; int accelFrame; float x;
        Stem(int tex, float a, int d, int frame, float px) {
            texIndex = tex; angle = a; dir = d; accelFrame = frame; x = px;
        }
    }

    static final class Particle {
        int active; float scale; float x, y; float moveX, moveY;
        float strideX, strideY; float outTime; float outWeight;
        int frame; float scaleWeight;
    }

    // ---- state ----
    private final Random mRandom = new Random();

    int mScreenWidth, mScreenHeight;
    boolean mLandscape;
    float mLandscapeY;
    float mXOffset;
    int mGap = 120;

    final Stem[] mStems = new Stem[MAX_STEM];
    final Particle[] mParticles = new Particle[MAX_PARTICLES];
    float[][] mParticlePos;
    int mParticleCount;
    int mParticleStartYCount;

    int mAccel;
    int mAccelFrame;

    // overlay alpha pulsing
    float mOverlayOutTime;
    float mOverlayWeight = 0.01f;

    // touch
    static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2;
    int mActionMode = ACTION_UP;
    int mCurArea, mPreArea;
    float mTouchX, mTouchY;

    ForestScene() {
        for (int i = 0; i < MAX_STEM; i++) {
            mStems[i] = new Stem(0, 0, 0, STEM_FRAME[i], 0);
        }
        for (int i = 0; i < MAX_PARTICLES; i++) {
            mParticles[i] = new Particle();
        }
    }

    void init(int width, int height) {
        mScreenWidth = width;
        mScreenHeight = height;

        if (width < height) {
            mLandscape = false;
            mLandscapeY = 0;
            for (int i = 0; i < MAX_STEM; i++) mStems[i].x = STEM_DEFAULT_X[i];
            mGap = 120;
        } else {
            mLandscape = true;
            mLandscapeY = -LANDSCAPE_Y;
            for (int i = 0; i < MAX_STEM; i++) mStems[i].x = STEM_DEFAULT_X[i + 6];
            mGap = 160;
        }

        initParticles();
        initStems();
    }

    // ---- per-frame update ----

    void setOffset(float xOffset, boolean isPreview) {
        // Original preview: draw(0.5f) → mXOffset = (0.5-0.5)*mGap = 0
        // Original wallpaper: xOffset=0.5-raw → mXOffset = (xOffset-0.5)*mGap = -raw*mGap
        mXOffset = isPreview ? 0.0f : -xOffset * mGap;
    }

    void onTouchEvent(int action, float x, float y) {
        mTouchX = x;
        mTouchY = y;
        switch (action) {
            case ACTION_DOWN:
                mActionMode = ACTION_DOWN;
                mCurArea = checkPressPoint(x, y);
                mPreArea = mCurArea;
                if (mCurArea != 0) createParticle();
                break;
            case ACTION_MOVE:
                mActionMode = ACTION_MOVE;
                mCurArea = checkPressPoint(x, y);
                mAccel = 1;
                mAccelFrame = 0;
                if (mCurArea != 0 && mCurArea != mPreArea) createParticle();
                mPreArea = mCurArea;
                break;
            case ACTION_UP:
                mActionMode = ACTION_UP;
                mCurArea = 0;
                mPreArea = 0;
                break;
        }
    }

    /** Called by GL each frame. Returns true if stems changed position. */
    boolean update() {
        updateOverlay();
        updateStems();
        updateParticles();
        return true;
    }

    private void updateOverlay() {
        mOverlayOutTime += mOverlayWeight;
        if (mOverlayOutTime < 0.0f || mOverlayOutTime > 1.0f) {
            mOverlayWeight *= -1.0f;
        }
    }

    private void updateStems() {
        if (mAccel == 0) {
            mAccelFrame = 0;
        } else {
            mAccelFrame++;
            if (mAccelFrame == 220) {
                mAccel = 0;
                mAccelFrame = 0;
            }
        }

        for (int i = 0; i < MAX_STEM; i++) {
            Stem s = mStems[i];
            if (mAccel != 0) {
                if (s.dir != 0) s.accelFrame += 3;
                else s.accelFrame -= 3;
            }
            float angle = calcAngle(s.accelFrame);
            if (s.dir != 0) {
                if (s.accelFrame >= FRAME_A) {
                    s.dir = 0;
                    angle = 8.0f;
                } else {
                    s.accelFrame++;
                }
            } else {
                if (s.accelFrame <= 0) {
                    s.dir = 1;
                    angle = 0.0f;
                } else {
                    s.accelFrame--;
                }
            }
            s.angle = -4.0f + angle;
        }
    }

    private void updateParticles() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            Particle p = mParticles[i];
            if (p.active == 1) {
                p.frame--;
                if (p.frame < 0) {
                    p.outTime -= p.outWeight;
                }
                p.moveX += p.strideX;
                p.moveY += p.strideY;
                if (p.scale > p.scaleWeight) {
                    p.scaleWeight += 0.07f;
                }
                if (p.outTime <= 0.0f) {
                    p.outTime = 0.8f;
                    p.active = 0;
                    p.moveX = 0;
                    p.moveY = 0;
                    p.scaleWeight = 0;
                }
            }
        }
    }

    // ---- touch ----

    private int checkPressPoint(float x, float y) {
        for (int i = 0; i < MAX_STEM; i++) {
            if (mStems[i].x <= x && x <= mStems[i].x + STEM_WIDTH) {
                return i + 1;
            }
        }
        return 0;
    }

    private void createParticle() {
        float offsetY = mLandscape ? PARTICLE_LANDSCAPE_Y : 0.0f;
        for (int i = 0; i < 5; i++) {
            if (mParticleCount > MAX_PARTICLES - 5) mParticleCount = 0;
            Particle p = mParticles[mParticleCount];
            p.active = 1;
            p.x = mTouchX;
            p.y = PARTICLE_START_Y[mParticleStartYCount] + offsetY;
            p.moveX = mParticlePos[mParticleCount][0];
            p.moveY = mParticlePos[mParticleCount][1];
            p.strideX = mParticlePos[mParticleCount][2];
            p.strideY = mParticlePos[mParticleCount][3];
            mParticleCount++;
            mParticleStartYCount++;
            if (mParticleStartYCount >= 50) mParticleStartYCount = 0;
        }
    }

    // ---- initialization ----

    private void initParticles() {
        mParticlePos = new float[MAX_PARTICLES][4];
        for (int i = 0; i < MAX_PARTICLES; i++) {
            mParticlePos[i] = new float[4];
            mParticlePos[i][0] = mRandom.nextFloat();
            mParticlePos[i][2] = mParticlePos[i][0] / PARTICLE_MOVE_Y;
            float r = mRandom.nextFloat();
            if (r < PARTICLE_RANDOM_LIMIT) r += PARTICLE_RANDOM;
            mParticlePos[i][1] = r;
            mParticlePos[i][3] = mParticlePos[i][1] * PARTICLE_STRIDE_Y;
        }

        int j = 0;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            Particle p = mParticles[i];
            p.active = 0;
            p.scale = PARTICLE_SCALE[j];
            p.x = 0; p.y = 0;
            p.moveX = 0; p.moveY = 0;
            p.strideX = 0; p.strideY = 0;
            p.outTime = PARTICLE_STRIDE_Y;
            p.outWeight = PARTICLE_OUT_WEIGHT[j];
            p.frame = PARTICLE_FRAME;
            p.scaleWeight = 0;
            j++;
            if (j >= 50) j = 0;
        }
        mParticleCount = 0;
        mParticleStartYCount = 0;
    }

    private void initStems() {
        // stems use texture indices 4,5,6,7 (same as original texIndex layout)
        mStems[0] = new Stem(4, 0, 1, STEM_FRAME[0], mStems[0].x);
        mStems[1] = new Stem(5, 0, 0, STEM_FRAME[1], mStems[1].x);
        mStems[2] = new Stem(6, 0, 0, STEM_FRAME[2], mStems[2].x);
        mStems[3] = new Stem(7, 0, 1, STEM_FRAME[3], mStems[3].x);
        mStems[4] = new Stem(4, 0, 1, STEM_FRAME[4], mStems[4].x);
        mStems[5] = new Stem(6, 0, 1, STEM_FRAME[5], mStems[5].x);
    }

    // ---- bezier curve (exact original) ----

    private static float calcAngle(int frame) {
        int frameH = FRAME_A / 10;
        float t = frame / (float) FRAME_A;
        float mum1 = 1.0f - t;
        float mum13 = mum1 * mum1 * mum1;
        float mu3 = t * t * t;
        // p1=(0,0), p2=(frameH,0), p3=(FRAME_A-frameH,8), p4=(FRAME_A,8)
        float y = 0 * mum13 + 3 * t * mum1 * mum1 * 0
                + 3 * t * t * mum1 * 8.0f + 8.0f * mu3;
        return y;
    }
}
