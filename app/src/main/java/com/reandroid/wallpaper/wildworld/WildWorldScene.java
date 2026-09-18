package com.reandroid.wallpaper.wildworld;

import android.os.SystemClock;
import java.util.Random;

/**
 * 野生世界壁纸场景逻辑层（纯 Java，无 GL 依赖）。
 * 负责动画状态管理、角色（翼龙/恐龙）更新、火球特效、昼夜切换、触摸交互等纯逻辑。
 */
final class WildWorldScene {

    // ---- 常量 ----

    /** 单帧 dt 上限（秒）。原来叫 MIN_DT，但它做的是 clamping 到最大值。 */
    private static final float MAX_DT = 0.2f;
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
    /**
     * 最远的一层背景。名字 `VCN` 来自原版 MediaTek RenderScript
     * （`wildworld.rs` 里的 `VCN_DISTANCE` / `VCNLAYER_*`），**不是笔误，别改**：
     * 它画的是 `ww_layer5.png`，即资产那边的「第 5 层」。
     * 五层的距离 0.95 / 0.9 / 0.8 / 0.7 / 0.6 递减 = 由远及近，绘制顺序也是由远及近。
     */
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

    /**
     * 背景图层与各自的视差距离（离屏幕越"远"的层走得越慢），下标一一对应。
     * 顺序就是绘制顺序。
     */
    private final Layer[][] mBgLayers;
    private static final float[] BG_LAYER_DISTANCES = {
            VCN_DISTANCE, LAYER4_DISTANCE, LAYER3_DISTANCE, LAYER2_DISTANCE, LAYER1_DISTANCE
    };

    WildWorldScene() {
        mFireballs = new Fireball[FIREBALL_COUNT];
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            mFireballs[i] = new Fireball();
            mFireballs[i].steps = 0;
        }
        mBgLayers = new Layer[][]{mVcnLayer, mLayer4, mLayer3, mLayer2, mLayer1};
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

        applyMetrics();
    }

    /**
     * 画面基准尺寸。原实现按四档分辨率（240x320 / 240x400 / 320x480 / 480x800）
     * 各写一整套参数（共约 200 行，只有数字不同），但那是 2010 年代机型的档位：
     * 现代设备 min(w,h) 必然 > 320，**全部落 480x800 那一档**，其余三档是死代码。
     * 这里只保留 480x800 作为唯一基准，参数值一字未改。
     */
    private static final float BASE_WIDTH = 480.0f;
    private static final float BASE_HEIGHT = 800.0f;

    private void applyMetrics() {
        mScaleX = mScreenWidth / BASE_WIDTH;
        mScaleY = mScreenHeight / BASE_HEIGHT;

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
        if (mDT > MAX_DT) mDT = MAX_DT;
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
        // 五层原本是同一段代码抄五遍，只有「哪个数组 + 哪个距离」不同。
        // 注意回卷只动 [UP] 那一半：[DOWN] 从不重置，这是原行为，不动它。
        for (int i = 0; i < mBgLayers.length; i++) {
            Layer[] pair = mBgLayers[i];
            float step = mBgSpeed * mDT * (1 - BG_LAYER_DISTANCES[i]);
            pair[UP].x += step;
            pair[DOWN].x += step;
            if (pair[UP].x + mXOffset >= mScreenWidth) pair[UP].x = -mXOffset;
        }
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
