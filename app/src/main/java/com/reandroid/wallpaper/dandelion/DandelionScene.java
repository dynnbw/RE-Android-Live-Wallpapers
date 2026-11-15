package com.reandroid.wallpaper.dandelion;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 蒲公英的场景逻辑：三株随风摆动的蒲公英、飘散的种子，以及背景的两层浮尘。
 *
 * <p>构图与动画节奏照参考实现的 480×800 设计稿：
 * 三根茎的根部在画面下方（A/B 在 850、C 在 800，即屏幕外），茎向上长；
 * 花头挂在茎顶并按 {@code sin(i + d + 相位) × 幅度} 摆动，摆动的支点在茎根。
 *
 * <p>{@code i} 是 10 秒一周的连续相位；{@code d} 是"阵风"相位 —— 每次划动/点击让
 * {@code d} 在 4 秒里整加 360°，因为正弦以 360° 为周期，风停时不会跳变。
 *
 * <p>花头枯荣与电池电量挂钩（电量满 → 第 0 帧最完整，电量空 → 第 10 帧只剩绒芯），
 * 这是参考实现本来的行为，读电量不需要权限。天气/来电相关的那两处（云带、来访光晕）不移植。
 */
public final class DandelionScene {

    /** 设计稿尺寸（参考实现的 480p 构图）。 */
    public static final float DESIGN_WIDTH = 480f;

    public static final float DESIGN_HEIGHT = 800f;

    /** 花头贴图：11 帧竖排。 */
    public static final float HEAD_WIDTH = 162f;

    public static final float HEAD_HEIGHT = 157f;

    public static final int HEAD_FRAMES = 11;

    static final float SWAY_PERIOD_MS = 10000f;
    static final float GUST_DURATION_MS = 4000f;
    static final float WIND_ACCELERATION = 20f;

    /** 花头命中半径（设计坐标），比绒球略小一圈，避免点到空处也算。 */
    static final float HEAD_HIT_RADIUS = 72f;

    static final String KEY_WIND = "dandelion_wind";
    static final String KEY_SPORES = "dandelion_spores";
    static final String KEY_BATTERY = "dandelion_battery";
    static final String KEY_COUNT = "dandelion_count";

    static final float BASE_SPAWN_PER_SECOND = 0.1f;
    static final float SWIPE_SPAWN_PER_SECOND = 1f;
    static final float TAP_SPAWN_PER_SECOND = 2f;
    static final float SPAWN_DECAY_MS = 1000f;

    static final int MAX_SPORES = 160;
    static final int MAX_DUST = 160;

    private static final int EMITTERS_PER_FLOWER = 7;
    private static final float EMITTER_ANGLE_BASE = 200f;
    private static final float EMITTER_ANGLE_STEP = 360f / EMITTERS_PER_FLOWER;
    private static final float EMITTER_ANGLE_SPREAD = 56f;
    private static final float EMITTER_VELOCITY = 100f;
    private static final float EMITTER_VELOCITY_SPREAD = 50f;
    private static final float EMITTER_AGE_MS = 1000f;
    private static final float EMITTER_AGE_SPREAD_MS = 2000f;
    private static final float EMITTER_SPORE_SIZE = 81f;

    /** 每个发射器在花头坐标系里的位置，和参考实现一致（第 4、5 个确实重合）。 */
    private static final float[] EMITTER_OFFSET_X = {0f, 20f, 40f, 20f, 20f, -30f, -30f};

    private static final float[] EMITTER_OFFSET_Y = {-40f, -30f, 0f, 30f, 30f, 30f, -40f};

    /** 每个发射器的三种孢子贴图（依次错开），权重 7:1:1；剩余 40 份权重是不生成的空粒子。 */
    private static final int[][] EMITTER_SPRITES = {
        {0, 1, 6}, {1, 2, 0}, {2, 3, 1}, {3, 4, 2}, {4, 5, 3}, {5, 6, 4}, {6, 0, 5},
    };

    private static final int[] EMITTER_SPRITE_WEIGHTS = {7, 1, 1};

    /** 点击/划动时每一株飞出去的 4 颗种子，角度照参考实现（0 = 正上方，顺时针）。 */
    private static final float[][] BURST_ANGLES = {
        {30f, 190f, 230f, -180f},
        {-120f, 10f, 120f, 30f},
        {0f, -50f, -160f, -180f},
    };

    private static final float BURST_SPEED = 50f;
    private static final long BURST_LIFE_MS = 2000L;

    /** 背景浮尘：一片 400×500 的矩形里每秒 3 颗，寿命 6 秒 ± 4 秒。 */
    private static final float DUST_AREA_X = 40f;

    private static final float DUST_AREA_Y = 40f;
    private static final float DUST_AREA_WIDTH = 400f;
    private static final float DUST_AREA_HEIGHT = 500f;
    private static final float DUST_SPAWN_PER_SECOND = 3f;
    private static final float DUST_VELOCITY = 20f;
    private static final float DUST_VELOCITY_SPREAD = 20f;
    private static final float DUST_AGE_MS = 6000f;
    private static final float DUST_AGE_SPREAD_MS = 4000f;
    private static final float DUST_FADE_MS = 2000f;
    private static final float DUST_SIZE_MS = 1000f;

    /** 一株蒲公英的构图常量。 */
    public static final class Flower {
        public final float rootX;
        public final float rootY;
        /** 摆动支点在茎局部坐标里的 y，等于茎高（支点在根上）。 */
        public final float pivotY;

        public final float stemWidth;
        public final float stemHeight;
        public final float headX;
        public final float headY;
        /** 花头自身的倾角，绕花头局部原点旋转。 */
        public final float headRotation;

        public final float swayAmplitude;
        public final float swayPhase;
        /** 整株的缩放：参考实现的三株是 1，多出来的背景小株用 0.35–0.65。 */
        public final float scale;

        /** 用哪根茎的贴图：0=A 1=B 2=C。 */
        public final int stem;

        Flower(
                float rootX,
                float rootY,
                float pivotY,
                float stemWidth,
                float stemHeight,
                float headX,
                float headY,
                float headRotation,
                float swayAmplitude,
                float swayPhase,
                float scale,
                int stem) {
            this.rootX = rootX;
            this.rootY = rootY;
            this.pivotY = pivotY;
            this.stemWidth = stemWidth;
            this.stemHeight = stemHeight;
            this.headX = headX;
            this.headY = headY;
            this.headRotation = headRotation;
            this.swayAmplitude = swayAmplitude;
            this.swayPhase = swayPhase;
            this.scale = scale;
            this.stem = stem;
        }

        /** 拿 A/B 两种高的茎做一株小一号的：根部换个位置，比例和倾角照抄。 */
        static Flower smaller(Flower base, float rootX, float rootY, float scale, float phase) {
            return new Flower(
                    rootX,
                    rootY,
                    base.pivotY,
                    base.stemWidth,
                    base.stemHeight,
                    base.headX,
                    base.headY,
                    base.headRotation,
                    base.swayAmplitude,
                    phase,
                    scale,
                    base.stem);
        }
    }

    /** 绘制顺序：参考实现先画 B、再 A、最后 C；多出来的小株排在它们后面（更远）。 */
    static final int[] DRAW_ORDER = {1, 0, 2};

    /** 参考实现的三株按 1/2/3 株挑着画时保留谁。 */
    private static final int[][] FLOWERS_BY_COUNT = {
        {0, 1, 2},
        {1},
        {1, 2},
        {0, 1, 2},
    };

    /** 只有这三株吐种子；多出来的只是背景。 */
    static final int EMITTING_FLOWERS = 3;

    static final int MAX_FLOWERS = 10;

    // A / B / C：根部、茎尺寸、花头偏移与倾角、摆幅与相位
    private static final Flower[] FLOWERS;

    static {
        Flower a = new Flower(50f, 850f, 551f, 45f, 551f, -60f, -100f, 70f, 5f, 0f, 1f, 0);
        Flower b = new Flower(120f, 850f, 693f, 142f, 693f, -20f, -80f, 0f, 3.5f, 60f, 1f, 1);
        Flower c = new Flower(320f, 800f, 215f, 51f, 215f, -40f, -60f, -40f, 2.5f, 30f, 1f, 2);
        // 4 株起往画面里补的：个头和主花差得不多（0.8–0.92），根同样埋在屏幕下方，
        // 花头的位置避开三株主花
        FLOWERS = new Flower[] {
            a,
            b,
            c,
            Flower.smaller(b, 364f, 869f, 0.92f, 200f),
            Flower.smaller(a, 276f, 874f, 0.88f, 300f),
            Flower.smaller(a, 5f, 1005f, 0.90f, 80f),
            Flower.smaller(a, 97f, 1098f, 0.85f, 140f),
            Flower.smaller(a, 407f, 1158f, 0.85f, 250f),
            Flower.smaller(b, 201f, 1246f, 0.80f, 20f),
            Flower.smaller(a, 448f, 861f, 0.80f, 170f),
        };
    }

    private final Random mRandom;
    private final List<DandelionParticle> mSpores = new ArrayList<>();
    private final List<DandelionParticle> mDust = new ArrayList<>();
    private final float[] mSpawnRate = new float[EMITTING_FLOWERS];
    private final float[] mSpawnDecay = new float[EMITTING_FLOWERS];
    private final float[] mSpawnCarry = new float[EMITTING_FLOWERS];
    private final float[] mHeadCenter = new float[2];
    private float mDustCarry;

    private int mViewWidth;
    private int mViewHeight;
    private float mScale = 1f;
    private float mOffsetX;
    private float mOffsetY;

    private long mLastUpdateMs = -1L;
    private boolean mWarmed;

    /** 设置项：风力 / 种子密度是百分比（100 = 参考实现的幅度），花头枯荣默认开启。 */
    private float mWindScale = 1f;

    private float mSporeScale = 1f;
    private boolean mBatteryHead = true;
    private int mFlowerCount = FLOWERS.length;

    private float mIDegrees;
    private float mGustBase;
    private long mGustStartMs;
    private boolean mGustActive;
    private float mGustDegrees;

    private int mBatteryLevel = 100;

    public DandelionScene(Random random) {
        mRandom = random;
        for (int i = 0; i < mSpawnRate.length; i++) {
            mSpawnRate[i] = BASE_SPAWN_PER_SECOND;
        }
    }

    public static Flower flower(int index) {
        return FLOWERS[index];
    }

    public List<DandelionParticle> spores() {
        return mSpores;
    }

    public List<DandelionParticle> dust() {
        return mDust;
    }

    public float scale() {
        return mScale;
    }

    public float offsetX() {
        return mOffsetX;
    }

    public float offsetY() {
        return mOffsetY;
    }

    /**
     * 设计稿按短边等比缩放并铺满：竖屏多出来的高度留给天空（设计稿底边贴着屏幕底边），
     * 横屏多出来的宽度自然落在两侧。
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mViewWidth = width;
        mViewHeight = height;
        mScale = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
        mOffsetX = (width - DESIGN_WIDTH * mScale) * 0.5f;
        mOffsetY = height - DESIGN_HEIGHT * mScale;
    }

    /** 设置项由 {@code PluginPrefsInjector} 按方法名反射注入。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        applyPrefs(
                prefs.getInt(KEY_WIND, 100) / 100f,
                prefs.getInt(KEY_SPORES, 100) / 100f,
                !"0".equals(prefs.getString(KEY_BATTERY, "1")),
                parseInt(prefs.getString(KEY_COUNT, String.valueOf(EMITTING_FLOWERS))));
    }

    void applyPrefs(float windScale, float sporeScale, boolean batteryHead, int flowerCount) {
        mWindScale = Math.max(0f, windScale);
        mSporeScale = Math.max(0f, sporeScale);
        mBatteryHead = batteryHead;
        mFlowerCount = Math.max(1, Math.min(MAX_FLOWERS, flowerCount));
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return MAX_FLOWERS;
        }
    }

    /** 当前设置下这一株画不画。1/2/3 株时按参考实现的取舍，4 株起才补背景小株。 */
    public boolean isFlowerVisible(int index) {
        if (index >= FLOWERS.length) {
            return false;
        }
        if (index < EMITTING_FLOWERS) {
            for (int visible : FLOWERS_BY_COUNT[Math.min(mFlowerCount, EMITTING_FLOWERS)]) {
                if (visible == index) {
                    return true;
                }
            }
            return false;
        }
        return index < mFlowerCount;
    }

    public void setBatteryLevel(int level) {
        int clamped = Math.max(0, Math.min(100, level));
        if (clamped == mBatteryLevel) {
            return;
        }
        mBatteryLevel = clamped;
        // 参考实现：电量个位走到 4 时让种子爆一次
        if (mBatteryHead && clamped % 10 == 4) {
            puffAll();
        }
    }

    /** 花头当前该显示第几帧：满电 0（最完整），空电 10（只剩绒芯）。 */
    public int headFrame() {
        if (!mBatteryHead) {
            return 0;
        }
        int frame = 10 - Math.round(mBatteryLevel / 10f);
        return Math.max(0, Math.min(HEAD_FRAMES - 1, frame));
    }

    public float batteryLevel() {
        return mBatteryLevel;
    }

    public float swayDegrees(int index) {
        Flower f = FLOWERS[index];
        return (float) Math.sin(Math.toRadians(mIDegrees + mGustDegrees + f.swayPhase))
                * f.swayAmplitude
                * mWindScale;
    }

    /** 花头中心的设计坐标（摆过之后），用于点击判定与测试。 */
    public void headCenter(int index, float[] out) {
        Flower f = FLOWERS[index];
        double inner = Math.toRadians(f.headRotation);
        float localX = (f.headX + HEAD_WIDTH * 0.5f) * f.scale;
        float localY = (f.headY + HEAD_HEIGHT * 0.5f) * f.scale;
        float rotatedX = (float) (localX * Math.cos(inner) - localY * Math.sin(inner));
        float rotatedY = (float) (localX * Math.sin(inner) + localY * Math.cos(inner));
        float offsetY = rotatedY - f.pivotY * f.scale;
        double sway = Math.toRadians(swayDegrees(index));
        out[0] = f.rootX + (float) (rotatedX * Math.cos(sway) - offsetY * Math.sin(sway));
        out[1] = f.rootY + (float) (rotatedX * Math.sin(sway) + offsetY * Math.cos(sway));
    }

    /** 第一层浮尘：整片 480×800 的点点贴图做极慢旋转。 */
    public float dustLayerOffsetX(int layer) {
        double phase = Math.toRadians(mIDegrees + mGustDegrees + (layer == 0 ? 0f : 50f));
        return (float) Math.sin(phase) * (layer == 0 ? 100f : 80f);
    }

    public float dustLayerOffsetY(int layer) {
        if (layer == 0) {
            return (float) Math.sin(Math.toRadians(mIDegrees * 3f)) * 20f - 20f;
        }
        return (float) Math.sin(Math.toRadians(mIDegrees * 2f)) * 10f - 10f;
    }

    public float dustLayerRotation(int layer, long nowMs) {
        float seconds = nowMs / 1000f;
        return layer == 0 ? seconds * 6f : 270f - seconds * 13.5f;
    }

    /** 点中哪一株（屏幕坐标）；点空返回 -1。 */
    public int flowerAt(float screenX, float screenY) {
        if (mScale <= 0f) {
            return -1;
        }
        float x = (screenX - mOffsetX) / mScale;
        float y = (screenY - mOffsetY) / mScale;
        for (int i = 0; i < FLOWERS.length; i++) {
            if (!isFlowerVisible(i)) {
                continue;
            }
            headCenter(i, mHeadCenter);
            float dx = x - mHeadCenter[0];
            float dy = y - mHeadCenter[1];
            float radius = HEAD_HIT_RADIUS * FLOWERS[i].scale;
            if (dx * dx + dy * dy <= radius * radius) {
                return i;
            }
        }
        return -1;
    }

    /** 吹一下：飞出去一小撮种子，同时让连续发射器在 1 秒里加快。 */
    public void puff(int index) {
        if (index < EMITTING_FLOWERS) {
            startPuff(index, TAP_SPAWN_PER_SECOND);
        }
        spawnBurst(index);
    }

    public void puffAll() {
        for (int i = 0; i < mSpawnRate.length; i++) {
            if (!isFlowerVisible(i)) {
                continue;
            }
            startPuff(i, SWIPE_SPAWN_PER_SECOND);
            spawnBurst(i);
        }
    }

    /** 参考实现的点击动画：每株管 4 个方向各飞出去一颗，2 秒里飘走 100 设计单位并淡出。 */
    private void spawnBurst(int index) {
        float[] center = new float[2];
        headCenter(index, center);
        float[] angles = BURST_ANGLES[index];
        for (float angle : angles) {
            if (mSpores.size() >= MAX_SPORES) {
                return;
            }
            float radians = (float) Math.toRadians(angle);
            mSpores.add(new DandelionParticle(
                    mRandom.nextInt(7),
                    center[0],
                    center[1],
                    (float) Math.sin(radians) * BURST_SPEED,
                    -(float) Math.cos(radians) * BURST_SPEED,
                    EMITTER_SPORE_SIZE,
                    Math.max(0L, mLastUpdateMs),
                    BURST_LIFE_MS,
                    0f,
                    (float) BURST_LIFE_MS,
                    0f,
                    0f));
        }
    }

    /** 喷射速率 1 秒内线性回到常态，和参考实现的动画时长一致。 */
    private void startPuff(int index, float rate) {
        mSpawnRate[index] = rate;
        mSpawnDecay[index] = (rate - BASE_SPAWN_PER_SECOND) * 1000f / SPAWN_DECAY_MS;
    }

    /** 阵风：4 秒里把摆动相位整加一圈，风停时衔接无缝。 */
    public void gust() {
        mGustBase = mGustDegrees;
        mGustStartMs = mLastUpdateMs;
        mGustActive = true;
    }

    public void update(long nowMs) {
        // 拿到画面尺寸后的第一帧先空跑一段，否则开局要等十几秒才看得到种子
        if (!mWarmed && mViewWidth > 0) {
            mWarmed = true;
            warmUp(nowMs);
        }
        if (mLastUpdateMs < 0L) {
            mLastUpdateMs = nowMs;
            return;
        }
        float dt = Math.min(0.05f, (nowMs - mLastUpdateMs) / 1000f);
        mLastUpdateMs = nowMs;
        if (dt <= 0f) {
            return;
        }

        mIDegrees = (nowMs % (long) SWAY_PERIOD_MS) / SWAY_PERIOD_MS * 360f;
        if (mGustActive) {
            float progress = (nowMs - mGustStartMs) / GUST_DURATION_MS;
            if (progress >= 1f) {
                mGustActive = false;
                mGustBase = (mGustBase + 360f) % 360f;
                mGustDegrees = mGustBase;
            } else {
                mGustDegrees = mGustBase + 360f * progress * progress;
            }
        } else {
            mGustDegrees = mGustBase;
        }

        advanceParticles(dt);
        spawnSpores(dt, nowMs);
        spawnDust(dt, nowMs);
    }

    /**
     * 首帧之前先空跑一段，否则开局要等十几秒才看得到种子。
     * 只在拿到画面尺寸后做一次。
     */
    private void warmUp(long nowMs) {
        long stepMs = 33L;
        long start = Math.max(0L, nowMs - 6000L);
        mLastUpdateMs = start;
        for (long t = start + stepMs; t <= nowMs; t += stepMs) {
            update(t);
        }
    }

    private void advanceParticles(float dt) {
        float wind = (float) Math.sin(Math.toRadians(mIDegrees + mGustDegrees + 90f))
                * WIND_ACCELERATION;
        advance(mSpores, dt, wind);
        advance(mDust, dt, wind);
    }

    private void advance(List<DandelionParticle> list, float dt, float wind) {
        for (int i = list.size() - 1; i >= 0; i--) {
            DandelionParticle p = list.get(i);
            p.velocityX += wind * dt;
            p.x += p.velocityX * dt;
            p.y += p.velocityY * dt;
        }
    }

    private void spawnSpores(float dt, long nowMs) {
        for (int i = 0; i < mSpawnRate.length; i++) {
            if (!isFlowerVisible(i)) {
                continue;
            }
            // 每株 7 个发射器各按 spawn.persecond 吐粒子；参考实现的权重里
            // 还有 40/49 是不生成任何东西的空粒子，这里直接跳过
            float visibleRate = mSpawnRate[i] * EMITTERS_PER_FLOWER * 9f / 49f * mSporeScale;
            mSpawnCarry[i] += visibleRate * dt;
            while (mSpawnCarry[i] >= 1f) {
                mSpawnCarry[i] -= 1f;
                if (mSpores.size() < MAX_SPORES) {
                    spawnSpore(i, nowMs);
                }
            }
            if (mSpawnRate[i] > BASE_SPAWN_PER_SECOND) {
                mSpawnRate[i] =
                        Math.max(BASE_SPAWN_PER_SECOND, mSpawnRate[i] - mSpawnDecay[i] * dt);
            }
        }
        removeDead(mSpores, nowMs);
    }

    private void spawnSpore(int flowerIndex, long nowMs) {
        int emitter = mRandom.nextInt(EMITTERS_PER_FLOWER);
        int sprite = pickSprite(emitter);
        float angle = EMITTER_ANGLE_BASE
                + EMITTER_ANGLE_STEP * (emitter + 1)
                + (mRandom.nextFloat() - 0.5f) * EMITTER_ANGLE_SPREAD;
        float speed =
                EMITTER_VELOCITY + (mRandom.nextFloat() - 0.5f) * 2f * EMITTER_VELOCITY_SPREAD;
        // 发射器跟着花头走：参考实现里是用两条正弦拟合花头位置的，这里直接用真实中心，
        // 改了风力之后也不会脱节
        headCenter(flowerIndex, mHeadCenter);
        float x = mHeadCenter[0] + EMITTER_OFFSET_X[emitter];
        float y = mHeadCenter[1] + EMITTER_OFFSET_Y[emitter];
        long life = (long) (EMITTER_AGE_MS + mRandom.nextFloat() * EMITTER_AGE_SPREAD_MS);
        mSpores.add(new DandelionParticle(
                sprite,
                x,
                y,
                (float) Math.cos(Math.toRadians(angle)) * speed,
                (float) Math.sin(Math.toRadians(angle)) * speed,
                EMITTER_SPORE_SIZE,
                nowMs,
                life,
                0f,
                1000f,
                0f,
                0f));
    }

    private int pickSprite(int emitter) {
        int[] sprites = EMITTER_SPRITES[emitter];
        int total = 0;
        for (int w : EMITTER_SPRITE_WEIGHTS) {
            total += w;
        }
        int roll = mRandom.nextInt(total);
        for (int i = 0; i < sprites.length; i++) {
            roll -= EMITTER_SPRITE_WEIGHTS[i];
            if (roll < 0) {
                return sprites[i];
            }
        }
        return sprites[0];
    }

    private void spawnDust(float dt, long nowMs) {
        mDustCarry += DUST_SPAWN_PER_SECOND * dt;
        while (mDustCarry >= 1f) {
            mDustCarry -= 1f;
            if (mDust.size() < MAX_DUST) {
                spawnDustParticle(nowMs);
            }
        }
        removeDead(mDust, nowMs);
    }

    private void removeDead(List<DandelionParticle> list, long nowMs) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).isDead(nowMs)) {
                list.remove(i);
            }
        }
    }

    private void spawnDustParticle(long nowMs) {
        boolean small = mRandom.nextFloat() < 2f / 3f;
        float angle = mRandom.nextFloat() * 360f;
        float speed = DUST_VELOCITY + (mRandom.nextFloat() - 0.5f) * 2f * DUST_VELOCITY_SPREAD;
        long life = (long) (DUST_AGE_MS + mRandom.nextFloat() * DUST_AGE_SPREAD_MS);
        mDust.add(new DandelionParticle(
                small ? 0 : 1,
                DUST_AREA_X + mRandom.nextFloat() * DUST_AREA_WIDTH,
                DUST_AREA_Y + mRandom.nextFloat() * DUST_AREA_HEIGHT,
                (float) Math.cos(Math.toRadians(angle)) * speed,
                (float) Math.sin(Math.toRadians(angle)) * speed,
                small ? 10f : 25f,
                nowMs,
                life,
                DUST_FADE_MS,
                DUST_FADE_MS,
                DUST_SIZE_MS,
                DUST_SIZE_MS));
    }
}
