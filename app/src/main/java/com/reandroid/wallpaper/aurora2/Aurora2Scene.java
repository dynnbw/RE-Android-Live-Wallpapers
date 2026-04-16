package com.reandroid.wallpaper.aurora2;

import android.opengl.Matrix;
import android.view.MotionEvent;

import java.util.Calendar;
import java.util.Random;

final class Aurora2Scene {
    static final int TEXTURE_BG_01 = 0;
    static final int TEXTURE_BG_02 = 1;
    static final int TEXTURE_BG_03 = 2;
    static final int TEXTURE_BG_04 = 3;
    static final int TEXTURE_TREE = 4;
    static final int TEXTURE_TOP = 5;
    static final int TEXTURE_SHINING_STAR = 6;
    static final int TEXTURE_AURORA_01 = 7;
    static final int TEXTURE_AURORA_02 = 8;
    static final int TEXTURE_AURORA_03 = 9;
    static final int TEXTURE_AURORA_04 = 10;
    static final int TEXTURE_AURORA_01_WSVGA = 11;
    static final int TEXTURE_AURORA_03_WSVGA = 12;
    static final int TEXTURE_SHINE_00 = 13;
    static final int TEXTURE_SHOOTING_START = 14;
    static final int SHOOTING_FRAME_COUNT = 52;
    static final int TEXTURE_COUNT = TEXTURE_SHOOTING_START + SHOOTING_FRAME_COUNT;

    static final String[] TEXTURE_NAMES = buildTextureNames();

    private static final int STAR_GROUP_1_COUNT = 50;
    private static final int STAR_GROUP_2_COUNT = 30;
    private static final float LANDSCAPE_WIDTH = 1280.0f;
    private static final float LANDSCAPE_HEIGHT = 800.0f;
    private static final float PORTRAIT_WIDTH = 800.0f;
    private static final float PORTRAIT_HEIGHT = 1280.0f;

    private static final float[] SHOOTING_X = new float[] {120.0f, 280.0f, 350.0f, 490.0f, 560.0f, 620.0f, 760.0f, 830.0f, 890.0f, 950.0f};
    private static final float[] SHOOTING_Y = new float[] {200.0f, 450.0f, 300.0f, 590.0f, 400.0f, 760.0f, 380.0f, 460.0f, 660.0f, 520.0f};

    private static final float[] SHINING_STAR_X = new float[] {80.0f, 95.0f, 120.0f, 180.0f, 200.0f, 220.0f, 240.0f, 310.0f, 330.0f, 350.0f, 360.0f, 390.0f, 400.0f, 460.0f, 510.0f, 550.0f, 620.0f, 650.0f, 680.0f, 750.0f, 880.0f, 920.0f, 990.0f, 1010.0f, 1030.0f, 1060.0f, 1110.0f, 1180.0f, 1200.0f, 1230.0f, 1260.0f, 1300.0f, 1310.0f, 1340.0f, 1460.0f, 1480.0f, 1500.0f, 1520.0f, 1550.0f, 1580.0f, 1610.0f, 1650.0f, 1700.0f, 1740.0f, 1780.0f, 1820.0f, 1840.0f, 1880.0f, 1890.0f, 1900.0f};
    private static final float[] SHINING_STAR_Y = new float[] {620.0f, 330.0f, 160.0f, 490.0f, 480.0f, 360.0f, 680.0f, 480.0f, 200.0f, 530.0f, 380.0f, 700.0f, 480.0f, 160.0f, 620.0f, 460.0f, 280.0f, 510.0f, 340.0f, 160.0f, 580.0f, 260.0f, 490.0f, 300.0f, 720.0f, 470.0f, 280.0f, 360.0f, 140.0f, 460.0f, 260.0f, 620.0f, 420.0f, 180.0f, 690.0f, 510.0f, 400.0f, 270.0f, 680.0f, 340.0f, 520.0f, 780.0f, 460.0f, 190.0f, 560.0f, 410.0f, 600.0f, 580.0f, 490.0f, 530.0f};
    private static final float[] SHINING_STAR_S = new float[] {1.2f, 0.4f, 1.5f, 0.9f, 0.6f, 1.3f, 0.8f, 1.5f, 1.0f, 0.7f, 1.2f, 0.5f, 1.0f, 1.4f, 0.9f, 1.3f, 0.6f, 0.9f, 1.0f, 0.5f, 0.8f, 1.3f, 0.5f, 1.2f, 1.5f, 0.4f, 1.5f, 0.7f, 1.0f, 0.5f, 0.8f, 1.3f, 0.4f, 1.0f, 0.7f, 1.3f, 0.5f, 0.9f, 1.2f, 0.3f, 0.8f, 1.3f, 1.0f, 1.4f, 0.8f, 1.5f, 0.7f, 1.0f, 0.5f, 1.3f};
    private static final float[] SHINING_STAR_A = new float[] {1.0f, 0.1f, 0.6f, 0.2f, 0.8f, 0.3f, 0.7f, 0.1f, 1.0f, 0.5f, 0.2f, 0.6f, 1.0f, 0.1f, 0.8f, 0.3f, 1.0f, 0.4f, 0.2f, 1.0f, 0.2f, 0.8f, 0.3f, 0.7f, 0.1f, 1.0f, 0.5f, 0.8f, 0.2f, 1.0f, 0.2f, 0.6f, 0.9f, 0.1f, 0.8f, 0.6f, 0.2f, 0.9f, 0.3f, 1.0f, 0.3f, 0.8f, 0.5f, 0.2f, 0.9f, 0.2f, 0.6f, 0.3f, 0.7f, 0.1f};
    private static final float[] SHINING_STAR_OP = new float[] {0.009f, 0.01f, 0.03f, 0.07f, 0.01f, 0.004f, 0.09f, 0.01f, 0.03f, 0.06f, 0.08f, 0.02f, 0.05f, 0.01f, 0.07f, 0.03f, 0.006f, 0.03f, 0.01f, 0.05f, 0.01f, 0.007f, 0.05f, 0.02f, 0.007f, 0.02f, 0.05f, 0.03f, 0.008f, 0.01f, 0.05f, 0.02f, 0.009f, 0.05f, 0.07f, 0.008f, 0.06f, 0.03f, 0.02f, 0.05f, 0.01f, 0.05f, 0.009f, 0.06f, 0.007f, 0.02f, 0.008f, 0.08f, 0.05f, 0.01f};

    private static final float[] SHINING_STAR_X2 = new float[] {65.0f, 130.0f, 200.0f, 310.0f, 380.0f, 80.0f, 160.0f, 220.0f, 280.0f, 320.0f, 420.0f, 460.0f, 560.0f, 590.0f, 620.0f, 480.0f, 210.0f, 530.0f, 1000.0f, 1010.0f, 680.0f, 720.0f, 760.0f, 850.0f, 910.0f, 990.0f, 1050.0f, 1130.0f, 1280.0f, 1350.0f, 1100.0f, 1230.0f, 1340.0f, 1450.0f, 1500.0f, 1260.0f, 1790.0f, 1410.0f, 1580.0f, 1790.0f, 1390.0f, 1420.0f, 1490.0f, 1520.0f, 1590.0f, 1680.0f, 1750.0f, 1800.0f, 1860.0f, 1900.0f};
    private static final float[] SHINING_STAR_Y2 = new float[] {360.0f, 280.0f, 580.0f, 680.0f, 310.0f, 80.0f, 480.0f, 210.0f, 240.0f, 190.0f, 280.0f, 660.0f, 490.0f, 300.0f, 420.0f, 380.0f, 200.0f, 480.0f, 150.0f, 620.0f, 500.0f, 280.0f, 360.0f, 540.0f, 130.0f, 560.0f, 480.0f, 700.0f, 340.0f, 160.0f, 260.0f, 320.0f, 520.0f, 300.0f, 620.0f, 120.0f, 480.0f, 270.0f, 680.0f, 340.0f, 580.0f, 260.0f, 490.0f, 300.0f, 720.0f, 100.0f, 280.0f, 360.0f, 140.0f, 460.0f};
    private static final float[] SHINING_STAR_S2 = new float[] {0.3f, 1.1f, 0.5f, 0.3f, 0.7f, 1.2f, 0.8f, 0.3f, 0.8f, 1.0f, 0.6f, 0.9f, 0.2f, 0.6f, 0.7f, 0.8f, 0.3f, 0.9f, 1.2f, 0.5f, 1.0f, 0.5f, 0.8f, 0.3f, 0.7f, 0.2f, 0.8f, 0.5f, 0.2f, 1.0f, 0.3f, 0.7f, 0.4f, 0.9f, 0.8f, 0.3f, 0.8f, 0.2f, 0.6f, 0.9f, 1.2f, 0.3f, 0.9f, 0.5f, 0.7f, 0.3f, 0.5f, 0.7f, 1.2f, 0.7f};
    private static final float[] SHINING_STAR_A2 = new float[] {0.2f, 0.8f, 0.3f, 0.7f, 0.8f, 1.0f, 0.1f, 0.6f, 0.2f, 0.8f, 0.3f, 1.0f, 0.4f, 0.2f, 1.0f, 0.2f, 0.8f, 0.3f, 0.7f, 0.8f, 1.0f, 1.0f, 0.4f, 0.8f, 0.3f, 0.3f, 0.7f, 0.5f, 1.0f, 0.5f, 0.2f, 0.6f, 1.0f, 0.1f, 0.8f, 0.3f, 1.0f, 0.4f, 0.2f, 1.0f, 0.2f, 0.8f, 0.3f, 0.7f, 0.8f, 1.0f, 1.0f, 0.4f, 0.8f, 0.3f};
    private static final float[] SHINING_STAR_OP2 = new float[] {0.03f, 0.08f, 0.05f, 0.01f, 0.09f, 0.02f, 0.07f, 0.03f, 0.008f, 0.01f, 0.05f, 0.009f, 0.08f, 0.04f, 0.01f, 0.01f, 0.007f, 0.05f, 0.02f, 0.07f, 0.01f, 0.05f, 0.03f, 0.006f, 0.07f, 0.04f, 0.09f, 0.01f, 0.03f, 0.06f, 0.008f, 0.02f, 0.05f, 0.01f, 0.07f, 0.03f, 0.06f, 0.03f, 0.01f, 0.05f, 0.01f, 0.007f, 0.05f, 0.02f, 0.07f, 0.02f, 0.05f, 0.03f, 0.08f, 0.01f};

    static final class Sprite {
        int textureId = -1;
        float x;
        float y;
        float z;
        float width;
        float height;
        float alpha = 1.0f;
    }

    static final class AuroraMeshState {
        int textureId = -1;
        float x;
        float y;
        float z;
        float width;
        float height;
        float alpha = 1.0f;
        float rippleFrame;
    }

    static final class SceneData {
        final float[] projectionMatrix = new float[16];
        final Sprite background = new Sprite();
        final Sprite shine = new Sprite();
        final Sprite tree = new Sprite();
        final Sprite[] stars1 = createSprites(STAR_GROUP_1_COUNT);
        final Sprite[] stars2 = createSprites(STAR_GROUP_2_COUNT);
        final Sprite shootingStar = new Sprite();
        final Sprite fadeBackground = new Sprite();
        final AuroraMeshState aurora = new AuroraMeshState();
        final AuroraMeshState fadeAurora = new AuroraMeshState();
        boolean fadeActive;
        float fadeAlpha;

        private static Sprite[] createSprites(int count) {
            Sprite[] sprites = new Sprite[count];
            for (int i = 0; i < count; i++) {
                sprites[i] = new Sprite();
            }
            return sprites;
        }
    }

    private final SceneData mSceneData = new SceneData();
    private final Random mRandom = new Random();
    private final float[] mShiningMove = new float[STAR_GROUP_1_COUNT];
    private final float[] mStarAlpha1 = SHINING_STAR_A.clone();
    private final float[] mStarAlpha2 = SHINING_STAR_A2.clone();

    private int mWidth;
    private int mHeight;
    private boolean mPreview;
    private boolean mVerticalMode;
    private int mResolution;
    private int mThemeId;
    private int mCurrentMinuteBucket = -1;
    private int mFrame;
    private int mFadeFrame;
    private int mShootingCount;
    private int mShootingId;
    private int mShootingRandX;
    private int mShootingRandY;
    private long mShootingDelayMs;
    private boolean mCheckShootTime = true;
    private float mRatio = 1.0f;

    private float mGapX;
    private float mGapY;
    private float mXBias;
    private float mYBias;
    private float mXDrawOffset;
    private float mYDrawOffset;
    private float mBgW;
    private float mBgH;
    private float mBgX;
    private float mBgY;
    private float mShineW;
    private float mShineH;
    private float mShineX;
    private float mShineY;
    private float mShootingStarW;
    private float mShootingStarH;
    private float mShootingStarX;
    private float mShootingStarY;
    private float mAuroraW;
    private float mAuroraH;
    private float mAuroraX;
    private float mAuroraY;
    private float mTreeW;
    private float mTreeH;
    private float mTreeX;
    private float mTreeY;
    private float mStarW;
    private float mStarH;
    private static final float MOVE_VAL = 0.13f;

    Aurora2Scene(int width, int height, boolean preview) {
        mPreview = preview;
        for (int i = 0; i < STAR_GROUP_1_COUNT; i++) {
            mShiningMove[i] = MOVE_VAL;
        }
        mShootingRandX = mRandom.nextInt(SHOOTING_X.length);
        mShootingRandY = mRandom.nextInt(SHOOTING_Y.length);
        resize(width, height);
    }

    void setPreview(boolean preview) {
        mPreview = preview;
    }

    void resize(int width, int height) {
        mWidth = Math.max(1, width);
        mHeight = Math.max(1, height);
        mVerticalMode = mWidth < mHeight;
        if (mVerticalMode) {
            mRatio = ((float) mHeight) / 1280.0f;
        } else {
            mRatio = ((float) mWidth) / 1280.0f;
        }
        mResolution = (mRatio > 0.74f && mRatio < 0.81f) ? 1 : 0;
        applyLayout(mVerticalMode);
        setParallax(0.0f, 0.0f);
        float logicalW = mVerticalMode ? PORTRAIT_WIDTH : LANDSCAPE_WIDTH;
        float logicalH = mVerticalMode ? PORTRAIT_HEIGHT : LANDSCAPE_HEIGHT;
        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0.0f, logicalW, 0.0f, logicalH, -1000.0f, 1000.0f);
        updateThemeFromClock(true);
    }

    void setOffset(float xOffset, float yOffset) {
        setParallax(0.0f, 0.0f);
    }

    void onTouchEvent(MotionEvent event) {
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    void update(long timeMs) {
        mFrame = (mFrame + 1) % 240;
        mXDrawOffset = mXBias;
        mYDrawOffset = mYBias;
        updateThemeFromClock(false);
        updateShine();
        updateAurora(mSceneData.aurora, currentAuroraTexture(), 0.6f);
        updateBackground();
        updateStars1();
        updateStars2();
        updateShootingStar(timeMs);
        updateTree();
        updateFade();
    }

    private void updateThemeFromClock(boolean immediate) {
        int desiredTheme = mPreview ? 3 : Calendar.getInstance().get(Calendar.MINUTE) / 15;
        if (desiredTheme < 0) {
            desiredTheme = 0;
        } else if (desiredTheme > 3) {
            desiredTheme = 3;
        }
        if (immediate || mCurrentMinuteBucket == -1) {
            mThemeId = desiredTheme;
            mCurrentMinuteBucket = desiredTheme;
            return;
        }
        if (desiredTheme == mCurrentMinuteBucket) {
            return;
        }
        mCurrentMinuteBucket = desiredTheme;
        mSceneData.fadeActive = true;
        mSceneData.fadeAlpha = 0.96f;
        mFadeFrame = 0;
        mSceneData.fadeBackground.textureId = currentBackgroundTexture();
        mSceneData.fadeBackground.width = mBgW;
        mSceneData.fadeBackground.height = mBgH;
        mSceneData.fadeBackground.x = mBgX + (mXDrawOffset * 0.5f);
        mSceneData.fadeBackground.y = mBgY + mYDrawOffset;
        mSceneData.fadeBackground.z = -60.0f;
        mSceneData.fadeBackground.alpha = 0.96f;
        mSceneData.fadeAurora.textureId = currentAuroraTexture();
        mSceneData.fadeAurora.width = mAuroraW;
        mSceneData.fadeAurora.height = effectiveAuroraHeight(currentAuroraTexture());
        mSceneData.fadeAurora.x = mAuroraX + mXDrawOffset;
        mSceneData.fadeAurora.y = auroraDrawY(currentAuroraTexture());
        mSceneData.fadeAurora.z = -90.0f;
        mSceneData.fadeAurora.alpha = 0.96f;
        mSceneData.fadeAurora.rippleFrame = mFrame;
        mThemeId = desiredTheme;
    }

    private void updateBackground() {
        Sprite bg = mSceneData.background;
        bg.textureId = currentBackgroundTexture();
        bg.width = mBgW;
        bg.height = mBgH;
        bg.x = mBgX + (mXDrawOffset * 0.5f);
        bg.y = mBgY + mYDrawOffset;
        bg.z = -60.0f;
        bg.alpha = 1.0f;
    }

    private void updateShine() {
        Sprite shine = mSceneData.shine;
        shine.textureId = TEXTURE_SHINE_00;
        shine.width = mShineW;
        shine.height = mShineH;
        shine.x = mShineX + mXDrawOffset;
        shine.y = mShineY;
        shine.z = -80.0f;
        shine.alpha = 0.7f;
    }

    private void updateTree() {
        Sprite tree = mSceneData.tree;
        tree.textureId = TEXTURE_TREE;
        tree.width = mTreeW;
        tree.height = mTreeH;
        tree.x = mTreeX + mXDrawOffset;
        tree.y = mTreeY + (mYDrawOffset / 2.0f);
        tree.z = -5.0f;
        tree.alpha = 1.0f;
    }

    private void updateAurora(AuroraMeshState aurora, int textureId, float alpha) {
        aurora.textureId = textureId;
        aurora.width = mAuroraW;
        aurora.height = effectiveAuroraHeight(textureId);
        aurora.x = mAuroraX + mXDrawOffset;
        aurora.y = auroraDrawY(textureId);
        aurora.z = -90.0f;
        aurora.alpha = alpha;
        aurora.rippleFrame = mFrame;
    }

    private float effectiveAuroraHeight(int textureId) {
        if (mVerticalMode && mResolution == 1 && (textureId == TEXTURE_AURORA_01_WSVGA || textureId == TEXTURE_AURORA_03_WSVGA)) {
            return 50.0f + mAuroraH;
        }
        return mAuroraH;
    }

    private float auroraDrawY(int textureId) {
        float y = mAuroraY + mYDrawOffset;
        if (mResolution == 1) {
            if (!mVerticalMode) {
                y -= 90.0f;
            } else if (textureId == TEXTURE_AURORA_01_WSVGA || textureId == TEXTURE_AURORA_03_WSVGA) {
                y -= 40.0f;
            }
        }
        return y;
    }

    private void updateStars1() {
        float splitY = mVerticalMode ? 640.0f : 400.0f;
        float extra = mVerticalMode ? 400.0f : 0.0f;
        float leftBand = 860.0f;
        float rightBand = 1060.0f;
        for (int i = 0; i < STAR_GROUP_1_COUNT; i++) {
            Sprite sprite = mSceneData.stars1[i];
            mStarAlpha1[i] += SHINING_STAR_OP[i];
            if (mStarAlpha1[i] <= 0.1f) {
                mStarAlpha1[i] = 0.1f;
            }
            if (mStarAlpha1[i] >= 1.0f) {
                mStarAlpha1[i] = 1.0f;
            }

            float yBase = SHINING_STAR_Y[i] + extra;
            float nextX;
            float nextY;
            if (SHINING_STAR_X[i] <= leftBand) {
                nextX = SHINING_STAR_X[i] - mShiningMove[i];
                nextY = yBase < splitY ? yBase - mShiningMove[i] : yBase + mShiningMove[i];
                if (nextX < 0.0f || nextY < 0.0f) {
                    nextX = SHINING_STAR_X[i];
                    nextY = SHINING_STAR_Y[i];
                    mShiningMove[i] = MOVE_VAL;
                }
            } else if (SHINING_STAR_X[i] >= rightBand) {
                nextX = SHINING_STAR_X[i] + mShiningMove[i];
                nextY = yBase < splitY ? yBase - mShiningMove[i] : yBase + mShiningMove[i];
                if (nextX > 1920.0f || nextY < 0.0f) {
                    nextX = SHINING_STAR_X[i] + extra;
                    nextY = SHINING_STAR_Y[i] + extra;
                    mShiningMove[i] = MOVE_VAL;
                }
            } else {
                nextX = SHINING_STAR_X[i];
                nextY = yBase < splitY ? yBase - mShiningMove[i] : yBase + mShiningMove[i];
                if (nextY < 0.0f || nextY > 800.0f) {
                    nextX = SHINING_STAR_X[i] + extra;
                    nextY = SHINING_STAR_Y[i] + extra;
                    mShiningMove[i] = MOVE_VAL;
                }
            }
            mShiningMove[i] += MOVE_VAL;

            sprite.textureId = TEXTURE_SHINING_STAR;
            sprite.width = mStarW * SHINING_STAR_S[i];
            sprite.height = mStarH * SHINING_STAR_S[i];
            sprite.x = nextX + (mXDrawOffset * 0.8f);
            sprite.y = nextY;
            sprite.z = -30.0f;
            sprite.alpha = clamp(mStarAlpha1[i], 0.1f, 1.0f);
        }
    }

    private void updateStars2() {
        for (int i = 0; i < STAR_GROUP_2_COUNT; i++) {
            Sprite sprite = mSceneData.stars2[i];
            mStarAlpha2[i] += SHINING_STAR_OP2[i];
            if (mStarAlpha2[i] <= 0.1f) {
                mStarAlpha2[i] = 0.1f;
            }
            if (mStarAlpha2[i] >= 0.8f) {
                mStarAlpha2[i] = 0.8f;
            }
            sprite.textureId = TEXTURE_SHINING_STAR;
            sprite.width = mStarW * SHINING_STAR_S2[i];
            sprite.height = mStarH * SHINING_STAR_S2[i];
            sprite.x = SHINING_STAR_X2[i] + (mXDrawOffset * 0.7f);
            sprite.y = SHINING_STAR_Y2[i] + (mVerticalMode ? 400.0f : 0.0f);
            sprite.z = -30.0f;
            sprite.alpha = clamp(mStarAlpha2[i], 0.1f, 0.8f);
        }
    }

    private void updateShootingStar(long timeMs) {
        Sprite shootingStar = mSceneData.shootingStar;
        if (mShootingCount < 90) {
            mShootingCount++;
            shootingStar.alpha = 0.0f;
            return;
        }
        if (mShootingId == 0) {
            mCheckShootTime = true;
        }
        if (mShootingId < SHOOTING_FRAME_COUNT - 1) {
            mShootingId++;
            shootingStar.alpha = 1.0f;
        } else {
            shootingStar.alpha = 0.0f;
            if (mCheckShootTime) {
                mShootingDelayMs = timeMs;
                mCheckShootTime = false;
            }
            if (timeMs - mShootingDelayMs > 10000L) {
                mShootingRandX = mRandom.nextInt(SHOOTING_X.length);
                mShootingRandY = mRandom.nextInt(SHOOTING_Y.length);
                mShootingId = 0;
                shootingStar.alpha = 1.0f;
            }
        }
        shootingStar.textureId = TEXTURE_SHOOTING_START + mShootingId;
        shootingStar.width = mShootingStarW;
        shootingStar.height = mShootingStarH;
        shootingStar.x = SHOOTING_X[mShootingRandX] + mShootingStarX + mXDrawOffset;
        shootingStar.y = SHOOTING_Y[mShootingRandY] + mShootingStarY;
        shootingStar.z = -20.0f;
    }

    private void updateFade() {
        if (!mSceneData.fadeActive) {
            return;
        }
        mSceneData.fadeAlpha -= 0.04173913f;
        mFadeFrame++;
        mSceneData.fadeBackground.alpha = Math.max(0.0f, mSceneData.fadeAlpha);
        mSceneData.fadeAurora.alpha = Math.max(0.0f, mSceneData.fadeAlpha);
        mSceneData.fadeBackground.x = mBgX + (mXDrawOffset * 0.5f);
        mSceneData.fadeBackground.y = mBgY + mYDrawOffset;
        mSceneData.fadeAurora.x = mAuroraX + mXDrawOffset;
        mSceneData.fadeAurora.y = auroraDrawY(mSceneData.fadeAurora.textureId);
        mSceneData.fadeAurora.rippleFrame = mFrame;
        if (mFadeFrame > 23) {
            mSceneData.fadeActive = false;
            mSceneData.fadeAlpha = 0.96f;
            mFadeFrame = 0;
        }
    }

    private void applyLayout(boolean vertical) {
        if (vertical) {
            mGapX = 1120.0f;
            mGapY = 128.0f;
            mBgW = 1920.0f;
            mBgH = 1408.0f;
            mBgX = 760.0f;
            mBgY = 704.0f;
            mShineW = 1920.0f;
            mShineH = 1408.0f;
            mShineX = 960.0f;
            mShineY = 704.0f;
            mShootingStarW = 280.0f;
            mShootingStarH = 145.0f;
            mShootingStarX = 140.0f;
            mShootingStarY = 72.5f;
            mAuroraW = 2020.0f;
            mAuroraH = 1408.0f;
            mAuroraX = -50.0f;
            mAuroraY = 0.0f;
            mTreeW = 1920.0f;
            mTreeH = 314.0f;
            mTreeX = 960.0f;
            mTreeY = 157.0f;
            mStarW = 32.0f;
            mStarH = 32.0f;
        } else {
            mGapX = 640.0f;
            mGapY = 608.0f;
            mBgW = 1920.0f;
            mBgH = 1408.0f;
            mBgX = 860.0f;
            mBgY = 704.0f;
            mShineW = 1920.0f;
            mShineH = 1408.0f;
            mShineX = 960.0f;
            mShineY = 704.0f;
            mShootingStarW = 280.0f;
            mShootingStarH = 145.0f;
            mShootingStarX = 140.0f;
            mShootingStarY = 72.5f;
            mAuroraW = 2020.0f;
            mAuroraH = 1408.0f;
            mAuroraX = -50.0f;
            mAuroraY = 0.0f;
            mTreeW = 1920.0f;
            mTreeH = 314.0f;
            mTreeX = 960.0f;
            mTreeY = 72.0f;
            mStarW = 32.0f;
            mStarH = 32.0f;
        }
    }

    private void setParallax(float xOffset, float yOffset) {
        mXBias = ((-xOffset) - 0.5f) * mGapX;
        mYBias = ((-yOffset) - 0.5f) * mGapY;
    }

    private int currentBackgroundTexture() {
        return TEXTURE_BG_01 + mThemeId;
    }

    private int currentAuroraTexture() {
        if (mVerticalMode && mResolution == 1) {
            if (mThemeId == 0) {
                return TEXTURE_AURORA_01_WSVGA;
            }
            if (mThemeId == 2) {
                return TEXTURE_AURORA_03_WSVGA;
            }
        }
        switch (mThemeId) {
            case 0:
                return TEXTURE_AURORA_01;
            case 1:
                return TEXTURE_AURORA_02;
            case 2:
                return TEXTURE_AURORA_03;
            default:
                return TEXTURE_AURORA_04;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String[] buildTextureNames() {
        String[] names = new String[TEXTURE_COUNT];
        names[TEXTURE_BG_01] = "aurora2_bg_01";
        names[TEXTURE_BG_02] = "aurora2_bg_02";
        names[TEXTURE_BG_03] = "aurora2_bg_03";
        names[TEXTURE_BG_04] = "aurora2_bg_04";
        names[TEXTURE_TREE] = "aurora2_tree";
        names[TEXTURE_TOP] = "aurora2_top";
        names[TEXTURE_SHINING_STAR] = "aurora2_shiningstar";
        names[TEXTURE_AURORA_01] = "aurora2_aurora_01";
        names[TEXTURE_AURORA_02] = "aurora2_aurora_02";
        names[TEXTURE_AURORA_03] = "aurora2_aurora_03";
        names[TEXTURE_AURORA_04] = "aurora2_aurora_04";
        names[TEXTURE_AURORA_01_WSVGA] = "aurora2_aurora_01_wsvga";
        names[TEXTURE_AURORA_03_WSVGA] = "aurora2_aurora_03_wsvga";
        names[TEXTURE_SHINE_00] = "aurora2_shine_00";
        for (int i = 0; i < SHOOTING_FRAME_COUNT; i++) {
            names[TEXTURE_SHOOTING_START + i] = String.format(java.util.Locale.US, "aurora2_shootingstar%02d", i + 1);
        }
        return names;
    }
}
