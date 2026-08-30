package com.reandroid.wallpaper.cosmicflow;

import android.content.SharedPreferences;

/**
 * Cosmic Flow 壁纸场景逻辑层(纯 Java,无 GL 调用)。
 * <p>
 * 移植自 Sony Xperia 动态壁纸 Cosmic Flow(纯 Java GLES 2.0 原版):
 * 动画时间累积(触摸加成/无触摸减速)、视差偏移与噪声尺度、8 色主题。
 * 渲染端由 {@link CosmicGL} 完成,本类只维护状态。
 */
final class CosmicScene {

    static final String PREFS_THEME = "cosmicflow_theme";

    // 8 主题(RGB 0-255 对,原版 ColorInfo 打包值;默认 Silk)
    static final String[] THEME_IDS = {
            "silk", "turquoise", "emerald", "sapphire",
            "gold", "ruby", "amethyst", "amber"
    };
    private static final float[][] THEME_COLORS = {
            {104f / 255f, 107f / 255f, 108f / 255f, 1f, 1f, 1f},               // Silk
            {26f / 255f, 115f / 255f, 115f / 255f, 205f / 255f, 255f / 255f, 248f / 255f},  // Turquoise
            {20f / 255f, 101f / 255f, 50f / 255f, 214f / 255f, 255f / 255f, 177f / 255f},   // Emerald
            {37f / 255f, 89f / 255f, 179f / 255f, 161f / 255f, 247f / 255f, 255f / 255f},   // Sapphire
            {165f / 255f, 127f / 255f, 36f / 255f, 162f / 255f, 255f / 255f, 179f / 255f},  // Gold
            {116f / 255f, 15f / 255f, 48f / 255f, 255f / 255f, 172f / 255f, 221f / 255f},   // Ruby
            {118f / 255f, 6f / 255f, 135f / 255f, 255f / 255f, 255f / 255f, 228f / 255f},   // Amethyst
            {240f / 255f, 143f / 255f, 52f / 255f, 253f / 255f, 197f / 255f, 197f / 255f},  // Amber
    };

    // Config(原版 Config.java)
    private static final float BASE_ANIMATION_SPEED = 0.6f;
    private static final float MAX_DELTA_FRAME_TIME = 83.333336f;   // 12 FPS
    private static final float MIDDLE_DELTA_FRAME_TIME = 50.0f;     // 20 FPS
    private static final float MIN_DELTA_FRAME_TIME = 33.333332f;   // 30 FPS
    private static final float MIN_ANIMATION_SPEED_FACTOR = 0.5f;
    private static final float ON_TOUCH_EXTRA_SPEED = 0.078f;
    private static final float TOUCH_ACCELERATION_DURATION = 3000.0f;
    private static final float LOWER_FPS_TIME_INTERVAL = 3000.0f;
    private static final float LOWER_SPEED_TIME_INTERVAL = 6000.0f;
    private static final float LOWEST_FPS_TIME_INTERVAL = 9000.0f;
    private static final float[] OFFSET_STEPS = {0.0f, 0.125f, 0.25f};
    private static final float OFFSET_DISTANCE_BETWEEN_STEPS = 0.125f;

    // ---- 状态(对应原版 FlowRenderer 字段)----
    float animationTimeMs;          // mAnimationTimeMillis
    float xOffsetEff;               // 视差偏移,±0.25(原版 (f-0.5)*0.5)
    float noiseScale = 8.0f;        // 噪声尺度 8-10
    float timeTouchBonus;           // 触摸动画加成目标时间
    long lastTouchMs = -100000L;    // 上次触摸时间(uptime)
    float speedFactor = MIN_ANIMATION_SPEED_FACTOR;
    float frameTargetMs = MAX_DELTA_FRAME_TIME;   // 帧限目标
    boolean isTouched;
    float[] primaryColor = new float[3];
    float[] secondaryColor = new float[3];

    private SharedPreferences mPluginPrefs;

    CosmicScene() {
        setTheme(0);
    }

    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
        readPrefs();
    }

    void readPrefs() {
        if (mPluginPrefs == null) return;
        String theme = mPluginPrefs.getString(PREFS_THEME, THEME_IDS[0]);
        int idx = 0;
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(theme)) {
                idx = i;
                break;
            }
        }
        setTheme(idx);
    }

    private void setTheme(int index) {
        float[] c = THEME_COLORS[index];
        primaryColor[0] = c[0];
        primaryColor[1] = c[1];
        primaryColor[2] = c[2];
        secondaryColor[0] = c[3];
        secondaryColor[1] = c[4];
        secondaryColor[2] = c[5];
    }

    // ==================== 每帧 ====================

    /**
     * 推进动画时间与原版 updateSpeed(速度因子 + 帧限目标按触摸年龄)。
     * @param dt    帧间隔(秒,引擎提供)
     * @param nowMs uptimeMillis
     */
    void update(float dt, long nowMs) {
        // 触摸加成缓动:朝 timeTouchBonus 以 0.078 速率逼近
        if (timeTouchBonus > animationTimeMs) {
            animationTimeMs += ON_TOUCH_EXTRA_SPEED * (timeTouchBonus - animationTimeMs);
        }
        // 动画时间:dt 钳 83.3ms × 速度因子
        float dtEff = Math.min(dt, MAX_DELTA_FRAME_TIME / 1000.0f) * speedFactor;
        animationTimeMs += dtEff * 1000.0f;
        animationTimeMs %= Float.MAX_VALUE;
        updateSpeed(nowMs);
    }

    /** 原版 updateSpeed():按触摸年龄设定速度因子与帧限目标 */
    private void updateSpeed(long nowMs) {
        float timeSinceTouch = nowMs - lastTouchMs;
        if (timeSinceTouch <= LOWER_FPS_TIME_INTERVAL) {
            speedFactor = 1.0f;
            frameTargetMs = MIN_DELTA_FRAME_TIME;
        } else if (timeSinceTouch <= LOWER_SPEED_TIME_INTERVAL) {
            speedFactor = 1.0f;
            frameTargetMs = MIDDLE_DELTA_FRAME_TIME;
        } else if (timeSinceTouch <= LOWEST_FPS_TIME_INTERVAL) {
            // 6000-9000ms:速度因子从 1.0 余弦缓降到 0.5
            float p = (timeSinceTouch - LOWER_SPEED_TIME_INTERVAL) / 4000.0f;
            speedFactor = MIN_ANIMATION_SPEED_FACTOR
                    + 0.5f * ((float) Math.cos(Math.PI * p) + 1.0f) / 2.0f;
            frameTargetMs = MIDDLE_DELTA_FRAME_TIME;
        } else {
            speedFactor = MIN_ANIMATION_SPEED_FACTOR;
            frameTargetMs = MAX_DELTA_FRAME_TIME;
        }
    }

    // ==================== 视差 / 触摸 ====================

    /** 壁纸滚动偏移(0..1)→ 原版 xOffset ±0.25,并更新噪声尺度 8-10 */
    void setScroll(float xOffset, long nowMs) {
        xOffsetEff = (xOffset - 0.5f) * 0.5f;
        // 噪声尺度:取与 |xOffset| 最近的步距 {0, 0.125, 0.25}
        float diff = Float.MAX_VALUE;
        for (float step : OFFSET_STEPS) {
            float d = Math.abs(step - Math.abs(xOffsetEff));
            if (d < diff) diff = d;
        }
        noiseScale = Math.min(Math.max(8.0f, (diff / OFFSET_DISTANCE_BETWEEN_STEPS) * 2.0f + 8.0f), 10.0f);
        // 滚动时若未触摸 → 加速动画(原版 onOffsetChanged 行为)
        if (!isTouched) {
            accelerateOnTouch(nowMs);
        }
    }

    /** 点击/滚动 → 3 秒动画加成(原版 accelerateOnTouch) */
    void onTap(long nowMs) {
        accelerateOnTouch(nowMs);
    }

    private void accelerateOnTouch(long nowMs) {
        timeTouchBonus = animationTimeMs + TOUCH_ACCELERATION_DURATION;
        frameTargetMs = MIN_DELTA_FRAME_TIME;
        if (nowMs > 0) lastTouchMs = nowMs;
        speedFactor = 1.0f;
    }

    void setTouched(boolean touched) {
        isTouched = touched;
    }
}
