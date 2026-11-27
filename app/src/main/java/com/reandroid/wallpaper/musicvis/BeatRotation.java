package com.reandroid.wallpaper.musicvis;

/**
 * 节拍驱动的旋转状态机。
 *
 * <p>目标半区逐拍交替：第 1 拍在 {@code [0,180]} 里选，第 2 拍在 {@code [180,360]}，
 * 第 3 拍回到 {@code [0,180]}……节拍强度决定在半个圆里走多远，按 60° 量化：
 * 弱拍靠近半区起点，强拍靠近终点。
 *
 * <p>每次换目标用 {@link #TWEEN_MS} 毫秒沿最短路径过渡，观感仍然是"跳"，但不会单帧
 * 闪一下。纯逻辑，不依赖 Android。
 */
public final class BeatRotation {

    /** 角度量化步长。 */
    public static final int STEP_DEGREES = 60;

    /** 半个圆里的档数：0..180 共 3 档（4 个位置）。 */
    private static final int STEPS_PER_HALF = 3;

    private static final float HALF_DEGREES = 180f;

    /** 跳变过渡时长。 */
    private static final long TWEEN_MS = 200L;

    private boolean mHighHalf;
    private float mFrom;
    private float mTarget;
    private float mCurrent;
    private long mTweenStartMs = Long.MIN_VALUE;

    /**
     * 来了一拍。
     *
     * @param strength 0..1 的节拍强度（来自 {@link BeatDetector}）
     */
    public void onBeat(float strength, long nowMs) {
        float clamped = Math.max(0f, Math.min(1f, strength));
        int steps = Math.round(clamped * STEPS_PER_HALF);
        mFrom = mCurrent;
        mTarget = (mHighHalf ? HALF_DEGREES : 0f) + steps * STEP_DEGREES;
        mHighHalf = !mHighHalf;
        mTweenStartMs = nowMs;
    }

    /** 每帧推进过渡，返回当前角度。 */
    public float update(long nowMs) {
        if (mTweenStartMs == Long.MIN_VALUE) return mCurrent;
        float progress = (nowMs - mTweenStartMs) / (float) TWEEN_MS;
        if (progress >= 1f) {
            mCurrent = mTarget;
        } else if (progress > 0f) {
            mCurrent = mFrom + shortestDelta(mFrom, mTarget) * progress;
        }
        return mCurrent;
    }

    /** 两个角度之间的最短有向差，落在 {@code [-180, 180]}。 */
    static float shortestDelta(float from, float to) {
        float delta = (to - from) % 360f;
        if (delta > HALF_DEGREES) delta -= 360f;
        if (delta < -HALF_DEGREES) delta += 360f;
        return delta;
    }
}
