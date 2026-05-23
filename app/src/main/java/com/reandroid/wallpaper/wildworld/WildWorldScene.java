package com.reandroid.wallpaper.wildworld;

import android.os.SystemClock;
import java.util.Random;

/**
 * 野生世界壁纸场景逻辑层（纯 Java，无 GL 依赖）。
 * 负责动画状态管理、角色（翼龙/恐龙）更新、火球特效、昼夜切换、触摸交互等纯逻辑。
 */
final class WildWorldScene {

    // ---- 常量 ----

    private static final float MIN_DT = 0.2f;
    static final long DINO_DT = 1000;
    static final long PTERO_DT = 2400;
    private static final long GEN_TIME = 4000;
    private static final float GEN_RANDOM = 0.4f;

    static final int UP = 0;
    static final int DOWN = 1;

    static final float FIREBALL_DISTANCE = 0.96f;
    private static final float VCN_DISTANCE = 0.95f;
    private static final float LAYER4_DISTANCE = 0.9f;
    private static final float LAYER3_DISTANCE = 0.8f;
    private static final float LAYER2_DISTANCE = 0.7f;
    private static final float LAYER1_DISTANCE = 0.6f;
    static final float PTEROSAUR_DISTANCE = 0.85f;
    private static final float DINOSAUR1_DISTANCE = 0.85f;
    private static final float DINOSAUR2_DISTANCE = 0.75f;
    static final int FIREBALL_COUNT = 6;

    private static final int DENSITY_240X320 = 1;
    private static final int DENSITY_240X400 = 2;
    private static final int DENSITY_320X480 = 3;
    private static final int DENSITY_480X800 = 4;

    // ---- 内部数据类 ----

    /**
     * 图层数据结构
     */
    static class Layer {
        float x;
        float y;
        float w;
        float h;
    }

    /**
     * 翼龙数据结构
     */
    static class Pterosaur {
        float x;
        float y;
        float w;
        float h;
        float scale;
        int duration;
        int alive;
        long time;
    }

    /**
     * 恐龙数据结构
     */
    static class Dinosaur {
        float x;
        float y;
        float w;
        float h;
        float stepY;
        float distance;
        int alive;
        long time;
    }

    /**
     * 火球数据结构
     */
    static class Fireball {
        float x;
        float y;
        float w;
        float h;
        float dir;
        float speed;
        float angle;
        long startTime;
        int steps;
    }

    // ---- 随机数生成器 ----

    final Random mRandom = new Random(System.currentTimeMillis());

    // ---- 场景状态（包级可见，供 GL 层直接读取）----

    // 图层数组（上下两层）
    final Layer[] mDay = new Layer[]{new Layer(), new Layer()};
    final Layer[] mNight = new Layer[]{new Layer(), new Layer()};
    final Layer[] mVcnLayer = new Layer[]{new Layer(), new Layer()};
    final Layer[] mLayer4 = new Layer[]{new Layer(), new Layer()};
    final Layer[] mLayer3 = new Layer[]{new Layer(), new Layer()};
    final Layer[] mLayer2 = new Layer[]{new Layer(), new Layer()};
    final Layer[] mLayer1 = new Layer[]{new Layer(), new Layer()};

    // 角色
    final Pterosaur mPterosaur = new Pterosaur();
    final Dinosaur[] mDinosaur = new Dinosaur[]{new Dinosaur(), new Dinosaur()};
    final Fireball[] mFireballs;

    // 屏幕尺寸
    int mScreenWidth;
    int mScreenHeight;

    // 动画参数
    int mBgSpeed;
    int mDensity;
    int mAnimation;
    int mDayNight;
    int mDayAndNightSpeed;

    // VCN 图层交互区域
    int mVcnMouseOffx;
    int mVcnMouseW;

    // 翼龙参数
    int mPterosaurW;
    int mPterosaurH;
    int mPterosaurSpeed;

    // 恐龙参数
    int mDinosaurSpeedX;
    int mDinosaurSpeedY;

    // 火球参数
    int mFireballBaseSpeed;
    int mFireballW;
    int mFireballH;
    int mFireballsShow;

    // 太阳/月亮点击区域
    int mSunLeft, mSunRight, mSunTop, mSunBottom;
    int mMoonLeft, mMoonRight, mMoonTop, mMoonBottom;

    // 缩放比例
    float mScaleX = 1.0f;
    float mScaleY = 1.0f;

    // 时间相关
    long mOldTime;
    long mCurTime;
    long mGenTime;
    float mDT;
    float mXOffset;

    // 壁纸偏移
    float mXOffsetPixels = 0.0f;

    // 触摸状态
    boolean mTouchPending = false;
    float mTouchX = -1.0f;
    float mTouchY = -1.0f;

    // ---- 构造方法 ----

    WildWorldScene() {
        mFireballs = new Fireball[FIREBALL_COUNT];
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            mFireballs[i] = new Fireball();
            mFireballs[i].steps = 0;
        }
    }

    // ---- 初始化 ----

    /**
     * 初始化动画状态参数
     * @param width  当前宽度
     * @param height 当前高度
     */
    void initState(int width, int height) {
        mScreenWidth = Math.min(width, height);
        mScreenHeight = Math.max(width, height);
        mDensity = 0;
        mAnimation = 1;
        mDayNight = 1;
        mGenTime = 0;
        mOldTime = 0;
        mFireballsShow = 0;
        mPterosaur.alive = 0;
        mPterosaur.duration = 0;
        mDinosaur[UP].alive = 0;
        mDinosaur[DOWN].alive = 0;

        for (int i = 0; i < FIREBALL_COUNT; i++) {
            mFireballs[i] = new Fireball();
            mFireballs[i].steps = 0;
        }

        setDensity(getDensity());
    }

    /**
     * 获取屏幕密度类型
     */
    private int getDensity() {
        if (mScreenWidth <= 240 && mScreenHeight <= 320) {
            return DENSITY_240X320;
        }
        if (mScreenWidth <= 240 && mScreenHeight <= 400) {
            return DENSITY_240X400;
        }
        if (mScreenWidth > 320 || mScreenHeight > 480) {
            return (mScreenWidth > 480 || mScreenHeight > 800) ? DENSITY_480X800 : DENSITY_480X800;
        }
        return DENSITY_320X480;
    }

    /**
     * 根据密度类型设置动画参数
     */
    private void setDensity(int den) {
        mDensity = den == 0 ? DENSITY_480X800 : den;

        float baseW;
        float baseH;
        if (mDensity == DENSITY_240X320) {
            baseW = 240.0f;
            baseH = 320.0f;
        } else if (mDensity == DENSITY_240X400) {
            baseW = 240.0f;
            baseH = 400.0f;
        } else if (mDensity == DENSITY_320X480) {
            baseW = 320.0f;
            baseH = 480.0f;
        } else {
            baseW = 480.0f;
            baseH = 800.0f;
        }

        mScaleX = mScreenWidth / baseW;
        mScaleY = mScreenHeight / baseH;

        if (mDensity == DENSITY_240X320) {
            mBgSpeed = 24;
            mDayAndNightSpeed = 16;
            mFireballBaseSpeed = 80;
            mFireballW = Math.round(32 * mScaleX);
            mFireballH = Math.round(40 * mScaleY);

            initLayer(mDay, 0, 0, mScreenWidth, 222, 222, 14);
            initLayer(mNight, 0, -222 - 15, mScreenWidth, 222, -15, 15);

            initLayer(mVcnLayer, 0, 121, mScreenWidth, 79, 121 + 79, 7);
            mVcnMouseOffx = Math.round(48 * mScaleX);
            mVcnMouseW = Math.round(80 * mScaleX);

            initLayer(mLayer4, 0, 197, mScreenWidth, 12, 197 + 12, 6);
            initLayer(mLayer3, 0, 206, mScreenWidth, 9, 206 + 9, 19);
            initLayer(mLayer2, 0, 213, mScreenWidth, 17, 213 + 17, 40);
            initLayer(mLayer1, 0, 227, mScreenWidth, 38, 227 + 38, 55);

            mPterosaur.x = -100 * mScaleX;
            mPterosaur.y = (72 * mScaleY) * 0.5f;
            mPterosaurW = Math.round(100 * mScaleX);
            mPterosaurH = Math.round(72 * mScaleY);
            mPterosaur.scale = 1.0f;
            mPterosaurSpeed = 48;

            mDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            mDinosaur[UP].x = mScreenWidth;
            mDinosaur[UP].y = (94 + 178 * (1 - 0.8f)) * mScaleY;
            mDinosaur[UP].w = 178 * 0.8f * mScaleX;
            mDinosaur[UP].h = 149 * 0.8f * mScaleY;
            mDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            mDinosaur[DOWN].x = mScreenWidth * 1.5f;
            mDinosaur[DOWN].y = (102 + 178 * (1 - 0.9f)) * mScaleY;
            mDinosaur[DOWN].w = 178 * 0.9f * mScaleX;
            mDinosaur[DOWN].h = 149 * 0.9f * mScaleY;
            mDinosaurSpeedX = 26;
            mDinosaurSpeedY = 6;

            mSunLeft = Math.round(160 * mScaleX);
            mSunRight = Math.round(240 * mScaleX);
            mSunTop = Math.round(40 * mScaleY);
            mSunBottom = Math.round(160 * mScaleY);

            mMoonLeft = Math.round(20 * mScaleX);
            mMoonRight = Math.round(120 * mScaleX);
            mMoonTop = Math.round(60 * mScaleY);
            mMoonBottom = Math.round(160 * mScaleY);
        } else if (mDensity == DENSITY_240X400) {
            mBgSpeed = 24;
            mDayAndNightSpeed = 16;
            mFireballBaseSpeed = 80;
            mFireballW = Math.round(32 * mScaleX);
            mFireballH = Math.round(40 * mScaleY);

            initLayer(mDay, 0, 0, mScreenWidth, 288, 288, 18);
            initLayer(mNight, 0, -288 - 18, mScreenWidth, 288, -18, 18);

            initLayer(mVcnLayer, 0, 177, mScreenWidth, 81, 177 + 81, 14);
            mVcnMouseOffx = Math.round(48 * mScaleX);
            mVcnMouseW = Math.round(80 * mScaleX);

            initLayer(mLayer4, 0, 260, mScreenWidth, 13, 260 + 13, 15);
            initLayer(mLayer3, 0, 270, mScreenWidth, 10, 270 + 10, 26);
            initLayer(mLayer2, 0, 282, mScreenWidth, 16, 282 + 16, 49);
            initLayer(mLayer1, 0, 296, mScreenWidth, 39, 296 + 39, 65);

            mPterosaur.x = -134 * mScaleX;
            mPterosaur.y = (96 * mScaleY) * 0.5f;
            mPterosaurW = Math.round(134 * mScaleX);
            mPterosaurH = Math.round(96 * mScaleY);
            mPterosaur.scale = 1.0f;
            mPterosaurSpeed = 48;

            mDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            mDinosaur[UP].x = mScreenWidth;
            mDinosaur[UP].y = (138 + 213 * (1 - 0.8f)) * mScaleY;
            mDinosaur[UP].w = 213 * 0.8f * mScaleX;
            mDinosaur[UP].h = 178 * 0.8f * mScaleY;
            mDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            mDinosaur[DOWN].x = mScreenWidth * 1.5f;
            mDinosaur[DOWN].y = (145 + 213 * (1 - 0.9f)) * mScaleY;
            mDinosaur[DOWN].w = 213 * 0.9f * mScaleX;
            mDinosaur[DOWN].h = 178 * 0.9f * mScaleY;
            mDinosaurSpeedX = 28;
            mDinosaurSpeedY = 6;

            mSunLeft = Math.round(160 * mScaleX);
            mSunRight = Math.round(240 * mScaleX);
            mSunTop = Math.round(40 * mScaleY);
            mSunBottom = Math.round(160 * mScaleY);

            mMoonLeft = Math.round(20 * mScaleX);
            mMoonRight = Math.round(120 * mScaleX);
            mMoonTop = Math.round(60 * mScaleY);
            mMoonBottom = Math.round(160 * mScaleY);
        } else if (mDensity == DENSITY_320X480) {
            mBgSpeed = 30;
            mDayAndNightSpeed = 20;
            mFireballBaseSpeed = 90;
            mFireballW = Math.round(40 * mScaleX);
            mFireballH = Math.round(60 * mScaleY);

            initLayer(mDay, 0, 0, mScreenWidth, 339, 339, 21);
            initLayer(mNight, 0, -335 - 25, mScreenWidth, 335, -25, 25);

            initLayer(mVcnLayer, 0, 206, mScreenWidth, 104, 206 + 104, 11);
            mVcnMouseOffx = Math.round(78 * mScaleX);
            mVcnMouseW = Math.round(100 * mScaleX);

            initLayer(mLayer4, 0, 304, mScreenWidth, 15, 304 + 15, 9);
            initLayer(mLayer3, 0, 315, mScreenWidth, 11, 315 + 11, 27);
            initLayer(mLayer2, 0, 329, mScreenWidth, 19, 329 + 19, 57);
            initLayer(mLayer1, 0, 355, mScreenWidth, 48, 355 + 48, 76);

            mPterosaur.x = -179 * mScaleX;
            mPterosaur.y = (128 * mScaleY) * 0.5f;
            mPterosaurW = Math.round(179 * mScaleX);
            mPterosaurH = Math.round(128 * mScaleY);
            mPterosaur.scale = 1.0f;
            mPterosaurSpeed = 64;

            mDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            mDinosaur[UP].x = mScreenWidth;
            mDinosaur[UP].y = (125 + 284 * (1 - 0.8f)) * mScaleY;
            mDinosaur[UP].w = 284 * 0.8f * mScaleX;
            mDinosaur[UP].h = 238 * 0.8f * mScaleY;
            mDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            mDinosaur[DOWN].x = mScreenWidth * 1.5f;
            mDinosaur[DOWN].y = (145 + 284 * (1 - 0.9f)) * mScaleY;
            mDinosaur[DOWN].w = 284 * 0.9f * mScaleX;
            mDinosaur[DOWN].h = 238 * 0.9f * mScaleY;
            mDinosaurSpeedX = 32;
            mDinosaurSpeedY = 8;

            mSunLeft = Math.round(220 * mScaleX);
            mSunRight = Math.round(320 * mScaleX);
            mSunTop = Math.round(40 * mScaleY);
            mSunBottom = Math.round(160 * mScaleY);

            mMoonLeft = Math.round(30 * mScaleX);
            mMoonRight = Math.round(130 * mScaleX);
            mMoonTop = Math.round(80 * mScaleY);
            mMoonBottom = Math.round(180 * mScaleY);
        } else {
            mBgSpeed = 36;
            mDayAndNightSpeed = 24;
            mFireballBaseSpeed = 100;
            mFireballW = Math.round(60 * mScaleX);
            mFireballH = Math.round(100 * mScaleY);

            initLayer(mDay, 0, 0, mScreenWidth, 565, 565, 35);
            initLayer(mNight, 0, -560 - 40, mScreenWidth, 560, -40, 40);

            initLayer(mVcnLayer, 0, 333, mScreenWidth, 175, 333 + 175, 20);
            mVcnMouseOffx = Math.round(124 * mScaleX);
            mVcnMouseW = Math.round(150 * mScaleX);

            initLayer(mLayer4, 0, 506, mScreenWidth, 25, 506 + 25, 15);
            initLayer(mLayer3, 0, 525, mScreenWidth, 18, 525 + 18, 45);
            initLayer(mLayer2, 0, 550, mScreenWidth, 32, 550 + 32, 95);
            initLayer(mLayer1, 0, 595, mScreenWidth, 80, 595 + 80, 128);

            mPterosaur.x = -270 * mScaleX;
            mPterosaur.y = (215 * mScaleY) * 0.5f;
            mPterosaurW = Math.round(270 * mScaleX);
            mPterosaurH = Math.round(215 * mScaleY);
            mPterosaur.scale = 1.0f;
            mPterosaurSpeed = 64;

            mDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            mDinosaur[UP].x = mScreenWidth;
            mDinosaur[UP].y = (225 + 426 * (1 - 0.8f)) * mScaleY;
            mDinosaur[UP].w = 426 * 0.8f * mScaleX;
            mDinosaur[UP].h = 390 * 0.8f * mScaleY;
            mDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            mDinosaur[DOWN].x = mScreenWidth * 1.5f;
            mDinosaur[DOWN].y = (256 + 426 * (1 - 0.9f)) * mScaleY;
            mDinosaur[DOWN].w = 426 * 0.9f * mScaleX;
            mDinosaur[DOWN].h = 390 * 0.9f * mScaleY;
            mDinosaurSpeedX = 32;
            mDinosaurSpeedY = 8;

            mSunLeft = Math.round(320 * mScaleX);
            mSunRight = Math.round(480 * mScaleX);
            mSunTop = Math.round(60 * mScaleY);
            mSunBottom = Math.round(240 * mScaleY);

            mMoonLeft = Math.round(40 * mScaleX);
            mMoonRight = Math.round(200 * mScaleX);
            mMoonTop = Math.round(100 * mScaleY);
            mMoonBottom = Math.round(300 * mScaleY);
        }
    }

    /**
     * 初始化图层参数
     */
    private void initLayer(Layer[] layers, float x, float yUp, float w, float hUp, float yDown, float hDown) {
        layers[UP].x = x * mScaleX;
        layers[UP].y = yUp * mScaleY;
        layers[UP].w = w;
        layers[UP].h = hUp * mScaleY;
        layers[DOWN].x = x * mScaleX;
        layers[DOWN].y = yDown * mScaleY;
        layers[DOWN].w = w;
        layers[DOWN].h = hDown * mScaleY;
    }

    // ---- 每帧更新入口 ----

    /**
     * 每帧更新：计算时间步长、处理触摸、更新所有动画状态。
     * GL 层应在绘制前调用此方法。
     * @param nowMs 当前系统时间（毫秒）
     */
    void updateFrame(long nowMs) {
        mXOffset = mXOffsetPixels - mScreenWidth / 2.0f;

        mCurTime = nowMs;
        if (mOldTime == 0L) {
            mOldTime = mCurTime;
        }
        mDT = (float)(mCurTime - mOldTime) / 1000.0f;
        if (mDT > MIN_DT) mDT = MIN_DT;
        mOldTime = mCurTime;

        // 处理待执行的触摸事件
        if (mTouchPending) {
            mTouchPending = false;
            onTouchCommand();
        }

        update();
    }

    // ---- 动画更新 ----

    /**
     * 更新所有动画元素状态
     */
    private void update() {
        float dayNightStep = mDayAndNightSpeed * (mDT / 0.025f);
        if (mDayNight > 0) {
            if (mAnimation == 0) {
                mNight[UP].y -= dayNightStep;
                mNight[DOWN].y -= dayNightStep;
                if (mNight[DOWN].y + mNight[DOWN].h <= 0) {
                    mAnimation = 1;
                }
            }
        } else {
            if (mAnimation == 1) {
                mNight[UP].y += dayNightStep;
                mNight[DOWN].y += dayNightStep;
                if (mNight[UP].y >= 0) {
                    mAnimation = 0;
                    mNight[UP].y = 0;
                }
            }
        }

        updateLayers();
        updatePterosaur();
        updateDinosaur(UP);
        updateDinosaur(DOWN);
        updateFireballs();

        // 随机生成角色
        if (mCurTime - mGenTime > GEN_TIME) {
            mGenTime = mCurTime;
            float random = randf(GEN_RANDOM);

            if (mPterosaur.alive == 0 && random > 0.04f && random < 0.14f) {
                mPterosaur.time = uptimeMillis();
                mPterosaur.alive = 1;
                mPterosaur.x = -mScreenWidth;
                mPterosaur.y = 32 + mScreenHeight * randf(0.2f);
            }

            if (random < 0.1f) {
                if (mDinosaur[UP].alive == 0) {
                    mDinosaur[UP].time = uptimeMillis();
                    mDinosaur[UP].alive = 1;
                    mDinosaur[UP].x = mScreenWidth * 3;
                    mDinosaur[UP].stepY = 0;
                }
            } else if (random < 0.18f) {
                if (mDinosaur[DOWN].alive == 0) {
                    mDinosaur[UP].time = uptimeMillis();
                    mDinosaur[DOWN].alive = 1;
                    mDinosaur[DOWN].x = mScreenWidth * 3;
                    mDinosaur[DOWN].stepY = 0;
                }
            }
        }
    }

    /**
     * 更新背景图层滚动位置
     */
    private void updateLayers() {
        float vcnStep = mBgSpeed * mDT * (1 - VCN_DISTANCE);
        mVcnLayer[UP].x += vcnStep;
        mVcnLayer[DOWN].x += vcnStep;
        if (mVcnLayer[UP].x + mXOffset >= mScreenWidth) mVcnLayer[UP].x = -mXOffset;

        float l4Step = mBgSpeed * mDT * (1 - LAYER4_DISTANCE);
        mLayer4[UP].x += l4Step;
        mLayer4[DOWN].x += l4Step;
        if (mLayer4[UP].x + mXOffset >= mScreenWidth) mLayer4[UP].x = -mXOffset;

        float l3Step = mBgSpeed * mDT * (1 - LAYER3_DISTANCE);
        mLayer3[UP].x += l3Step;
        mLayer3[DOWN].x += l3Step;
        if (mLayer3[UP].x + mXOffset >= mScreenWidth) mLayer3[UP].x = -mXOffset;

        float l2Step = mBgSpeed * mDT * (1 - LAYER2_DISTANCE);
        mLayer2[UP].x += l2Step;
        mLayer2[DOWN].x += l2Step;
        if (mLayer2[UP].x + mXOffset >= mScreenWidth) mLayer2[UP].x = -mXOffset;

        float l1Step = mBgSpeed * mDT * (1 - LAYER1_DISTANCE);
        mLayer1[UP].x += l1Step;
        mLayer1[DOWN].x += l1Step;
        if (mLayer1[UP].x + mXOffset >= mScreenWidth) mLayer1[UP].x = -mXOffset;
    }

    /**
     * 更新翼龙动画状态
     */
    private void updatePterosaur() {
        if (mPterosaur.alive != 0) {
            if (mCurTime - mPterosaur.time > PTERO_DT) {
                mPterosaur.time = mCurTime;
                if (mPterosaur.duration != 0) {
                    if (mPterosaur.scale < PTEROSAUR_DISTANCE) {
                        mPterosaur.scale = PTEROSAUR_DISTANCE;
                    } else if (mPterosaur.scale == PTEROSAUR_DISTANCE) {
                        mPterosaur.scale = PTEROSAUR_DISTANCE + 0.06f;
                    } else {
                        mPterosaur.scale = PTEROSAUR_DISTANCE;
                        mPterosaur.duration = 0;
                    }
                } else {
                    if (mPterosaur.scale > PTEROSAUR_DISTANCE) {
                        mPterosaur.scale = PTEROSAUR_DISTANCE;
                    } else if (mPterosaur.scale == PTEROSAUR_DISTANCE) {
                        mPterosaur.scale = PTEROSAUR_DISTANCE - 0.06f;
                    } else {
                        mPterosaur.scale = PTEROSAUR_DISTANCE;
                        mPterosaur.duration = 1;
                    }
                }
                mPterosaur.w = mPterosaurW * mPterosaur.scale;
                mPterosaur.h = mPterosaurH * mPterosaur.scale;
            }
            mPterosaur.x += mPterosaurSpeed * mDT * mPterosaur.scale;

            // 翼龙移出屏幕后标记为死亡
            if (mPterosaur.x + mXOffset > mScreenWidth) {
                mPterosaur.alive = 0;
            }
        }
    }

    /**
     * 更新恐龙动画状态
     * @param ud 方向（UP/DOWN）
     */
    private void updateDinosaur(int ud) {
        Dinosaur d = mDinosaur[ud];
        if (d.alive != 0) {
            if (mCurTime - d.time > DINO_DT) {
                d.time = mCurTime;
                d.stepY = 0;
            } else {
                d.stepY += mDinosaurSpeedY * (1.7f - d.distance) * mDT;
            }
            d.x -= mDinosaurSpeedX * (1.7f - d.distance) * mDT;

            // 恐龙移出屏幕后标记为死亡
            if (d.x + d.w + mXOffset < 0) {
                d.alive = 0;
            }
        }
    }

    /**
     * 更新火球动画状态
     */
    private void updateFireballs() {
        if (mFireballsShow != 0) {
            int count = 0;
            for (int i = 0; i < FIREBALL_COUNT; i++) {
                Fireball f = mFireballs[i];
                if (f.steps > 0) {
                    if (mCurTime > f.startTime) {
                        f.x += mBgSpeed * mDT * (1 - FIREBALL_DISTANCE)
                                + f.dir * f.speed * FIREBALL_DISTANCE * mDT;
                        f.y -= f.speed * FIREBALL_DISTANCE * mDT;
                        f.steps -= 1;
                        f.angle = 120.0f;
                    } else {
                        f.x += mBgSpeed * mDT * (1 - FIREBALL_DISTANCE);
                    }
                    if (f.steps > 0) {
                        count++;
                    }
                }
            }
            if (count == 0) {
                mFireballsShow = 0;
            }
        }
    }

    // ---- 触摸处理 ----

    /**
     * 处理触摸命令（昼夜切换 / 生成火球）
     */
    private void onTouchCommand() {
        float x = mTouchX;
        float y = mTouchY;

        if (x > mSunLeft && x < mSunRight && y > mSunTop && y < mSunBottom) {
            if (mDayNight > 0 && mAnimation == 1) {
                mDayNight = 0;
            }
        } else if (x > mMoonLeft && x < mMoonRight && y > mMoonTop && y < mMoonBottom) {
            if (mDayNight == 0 && mAnimation == 0) {
                mDayNight = 1;
            }
        } else {
            float xPos = mVcnLayer[UP].x + mXOffset;
            while (xPos < 0) {
                xPos += mScreenWidth;
            }
            float vcn1MouseLeft = xPos + mVcnMouseOffx - mVcnMouseW / 2.0f;
            float vcn1MouseRight = vcn1MouseLeft + mVcnMouseW;
            float vcn2MouseLeft = vcn1MouseLeft - mScreenWidth;
            float vcn2MouseRight = vcn2MouseLeft + mVcnMouseW;

            if (x > vcn1MouseLeft && x < vcn1MouseRight && y > mVcnLayer[UP].y
                    && y < mVcnLayer[UP].y + mVcnLayer[UP].h) {
                createFireballs(vcn1MouseLeft + mVcnMouseW / 2.0f - mXOffset);
            } else if (x > vcn2MouseLeft && x < vcn2MouseRight && y > mVcnLayer[UP].y
                    && y < mVcnLayer[UP].y + mVcnLayer[UP].h) {
                createFireballs(vcn2MouseLeft + mVcnMouseW / 2.0f - mXOffset);
            }
        }
    }

    /**
     * 生成火球特效
     * @param xpos 火球生成的X坐标
     */
    private void createFireballs(float xpos) {
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            Fireball f = mFireballs[i];
            if (f.steps <= 0) {
                float random = randf(0.3f);
                float scale = 0.6f + random;
                f.w = mFireballW * scale;
                f.h = mFireballH * scale;
                f.x = xpos - f.w / 2.0f;
                f.y = mVcnLayer[UP].y + 8;
                f.speed = mFireballBaseSpeed + mFireballBaseSpeed * random;
                f.dir = 0.3f - randf(0.6f);
                f.steps = (int) (30 + random * 40);
                f.startTime = uptimeMillis() + (long)(randf(0.4f) * 10000.0f);
            }
        }
        mFireballsShow = 1;
    }

    // ---- 工具方法 ----

    /**
     * 生成指定范围的随机浮点数
     */
    private float randf(float range) {
        return mRandom.nextFloat() * range;
    }

    /**
     * 获取系统运行时间（毫秒）
     */
    private long uptimeMillis() {
        return SystemClock.uptimeMillis();
    }
}
