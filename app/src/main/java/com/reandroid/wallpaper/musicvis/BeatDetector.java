package com.reandroid.wallpaper.musicvis;

import java.util.Random;

/**
 * 基于 FFT 低频通量的节拍检测。
 *
 * <p>每帧把当前通量与慢速滑动平均比较：明显高出（{@link #RATIO} 倍）才算一拍。平均只管
 * 慢速漂移，节拍帧本身不会立刻把阈值抬高，所以同一段鼓点不会被平均吃掉。
 *
 * <p>节奏控制：每次跳变后随机等 {@link #MIN_GAP_MS}..{@link #MAX_GAP_MS} 毫秒，避免
 * 固定 500ms 的机械感；同时用 1 秒滑动窗口硬限制最多 2 拍，画面不会来回乱晃。
 *
 * <p>纯逻辑，不依赖 Android；调用方负责每帧传入通量和时间戳。
 */
public final class BeatDetector {

    /** 判定阈值：当前通量高于平均值的倍数。 */
    private static final float RATIO = 1.35f;

    /** 强度归一化上限：达到平均值的这个倍数时记为 1。 */
    private static final float MAX_RATIO = 4.0f;

    /** 1 秒滑动窗口内最多允许的节拍数。 */
    private static final long WINDOW_MS = 1000L;

    /** 两次跳变之间的随机间隔范围。 */
    private static final long MIN_GAP_MS = 300L;

    private static final long MAX_GAP_MS = 700L;

    /** 滑动平均的跟随速度（每帧）。 */
    private static final float AVERAGE_ALPHA = 0.08f;

    /** 绝对噪声底；低于它一律不算节拍。 */
    private static final float MIN_ENERGY = 1f;

    private final Random mRandom;
    private float mAverage = -1f;
    private long mLastBeatMs = Long.MIN_VALUE;
    private long mSecondLastBeatMs = Long.MIN_VALUE;
    private long mNextAllowedMs = Long.MIN_VALUE;

    public BeatDetector() {
        this(new Random());
    }

    /** 测试可以注入固定返回值的 Random，让随机间隔可预期。 */
    BeatDetector(Random random) {
        mRandom = random;
    }

    /**
     * 输入一帧的通量。
     *
     * @return 0..1 的节拍强度；不是节拍时返回 {@code -1}
     */
    public float onEnergy(float energy, long nowMs) {
        if (mAverage < 0f) {
            mAverage = energy;
            return -1f;
        }
        boolean gapElapsed = mNextAllowedMs == Long.MIN_VALUE || nowMs >= mNextAllowedMs;
        boolean windowElapsed =
                mSecondLastBeatMs == Long.MIN_VALUE || nowMs - mSecondLastBeatMs >= WINDOW_MS;
        boolean beat =
                energy >= MIN_ENERGY && energy > mAverage * RATIO && gapElapsed && windowElapsed;
        float strength = -1f;
        if (beat) {
            long gap = MIN_GAP_MS + (long) (mRandom.nextFloat() * (MAX_GAP_MS - MIN_GAP_MS));
            mNextAllowedMs = nowMs + gap;
            mSecondLastBeatMs = mLastBeatMs;
            mLastBeatMs = nowMs;
            strength = Math.min(1f, (energy / mAverage - 1f) / (MAX_RATIO - 1f));
        }
        mAverage += (energy - mAverage) * AVERAGE_ALPHA;
        return strength;
    }
}
