package com.reandroid.wallpaper.oceanwaves;

import android.content.SharedPreferences;

/**
 * 海浪的时间与滚动：整个画面都是同一个水面着色器画出来的，场景这边只负责喂时间。
 *
 * <p>参考实现把时间按 40π 秒回绕再交给着色器（{@code t3 = fract(t/(40π))·40π}）——
 * 波浪相位是 {@code 5t}，跳过 40π 正好是 200π = 2π 的整数倍，所以回绕处看不出接缝，
 * 又能避免时间越跑越大之后浮点精度掉光。
 */
public final class OceanWavesScene {

    /** 参考实现的构图宽度，水面常数（岛的圆心、贴图周期）都按它写。 */
    public static final float DESIGN_WIDTH = 480f;

    static final double TIME_WRAP_SECONDS = 125.66370614359172953850573533118;

    static final String KEY_SPEED = "oceanwaves_speed";

    static final String KEY_ZOOM = "oceanwaves_zoom";

    private static final float MIN_SPEED = 0.1f;
    private static final float MAX_SPEED = 3f;

    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 2.5f;

    private float mSpeed = 1f;
    private float mZoom = 1f;
    private long mLastUpdateMs = -1L;
    private double mTime;

    private int mViewWidth = 1;
    private int mViewHeight = 1;
    private float mPanX;

    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        mViewWidth = width;
        mViewHeight = height;
    }

    /** 桌面滚动换到设计单位，海床跟着横向平移。 */
    public void setOffset(int xPixels) {
        mPanX = xPixels * DESIGN_WIDTH / mViewWidth;
    }

    /** 设置项由 {@code PluginPrefsInjector} 按方法名反射注入。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        setSpeed(prefs.getInt(KEY_SPEED, 100) / 100f);
        setZoom(prefs.getInt(KEY_ZOOM, 100) / 100f);
    }

    void setSpeed(float speed) {
        mSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    void setZoom(float zoom) {
        mZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /** 海床贴图的放大倍数：1 是一屏宽看到三分之一的贴图，2 就是两倍近。 */
    public float zoom() {
        return mZoom;
    }

    public void update(long nowMs) {
        if (mLastUpdateMs < 0L) {
            mLastUpdateMs = nowMs;
            return;
        }
        float dt = (nowMs - mLastUpdateMs) / 1000f;
        mLastUpdateMs = nowMs;
        mTime += dt * mSpeed;
        if (mTime >= TIME_WRAP_SECONDS) {
            mTime %= TIME_WRAP_SECONDS;
        }
    }

    public float time() {
        return (float) mTime;
    }

    public float panX() {
        return mPanX;
    }

    /** 屏幕像素 / 设计单位。 */
    public float scale() {
        return mViewWidth / DESIGN_WIDTH;
    }

    /** 设计单位下的画面高度（比 800 高或矮都跟着屏幕走）。 */
    public float viewHeightDesign() {
        return mViewHeight / scale();
    }
}
