package com.reandroid.wallpaper.fall;

/**
 * 多指落点判定：每根手指各自记录上一次触发的位置与时间，互不干扰。
 *
 * <p>原来只有一份 last 坐标，第二根手指一放下就把第一根的起点冲掉——这正是
 * "多指画圈时水波不跟手"的原因。这里按 pointerId 分开记。
 *
 * <p>纯 Java（不碰 MotionEvent），单测直接跑。
 */
final class FallTouchTracker {

    /** 轻点（没有位移）的扰动强度：落叶 0.3 &lt; 阈值 0.35 &lt; 轻点 0.5 &lt; 快划 1.0。 */
    static final float TAP_POWER = 0.5f;

    /** 滑动每跨过这么多像素才落一次，避免逐帧刷屏。 */
    static final float TRIGGER_DISTANCE_PX = 42f;

    /** 达到这个速度就算满功率。 */
    static final float FULL_SPEED_PX_S = 2600f;

    private static final int MAX_POINTERS = 32;

    private final float[] mX = new float[MAX_POINTERS];
    private final float[] mY = new float[MAX_POINTERS];
    private final long[] mTimeMs = new long[MAX_POINTERS];
    private final boolean[] mActive = new boolean[MAX_POINTERS];

    /** 按下：记录起点并返回本次的落点强度。 */
    float down(int pointerId, float x, float y, long timeMs) {
        remember(pointerId, x, y, timeMs);
        return TAP_POWER;
    }

    /**
     * 移动：离上次触发点不足 {@link #TRIGGER_DISTANCE_PX} 时返回 {@code -1}（这次不落点）；
     * 否则记录新位置并返回 0..1 的强度。
     *
     * <p>没记录过的手指（事件流中途接上）按"刚按下"处理。
     */
    float move(int pointerId, float x, float y, long timeMs) {
        int i = index(pointerId);
        if (i < 0 || !mActive[i]) {
            return down(pointerId, x, y, timeMs);
        }
        float dx = x - mX[i];
        float dy = y - mY[i];
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance < TRIGGER_DISTANCE_PX) {
            return -1f;
        }
        long dtMs = timeMs - mTimeMs[i];
        float power = 0f;
        if (dtMs > 0L) {
            float speed = distance / (dtMs * 0.001f);
            power = Math.min(1f, speed / FULL_SPEED_PX_S);
        }
        remember(pointerId, x, y, timeMs);
        return power;
    }

    /** 抬起：只清这一根手指，别的手指不受影响。 */
    void up(int pointerId) {
        int i = index(pointerId);
        if (i >= 0) {
            mActive[i] = false;
        }
    }

    /** 全部抬起/取消。 */
    void reset() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            mActive[i] = false;
        }
    }

    private void remember(int pointerId, float x, float y, long timeMs) {
        int i = index(pointerId);
        if (i < 0) {
            return;
        }
        mActive[i] = true;
        mX[i] = x;
        mY[i] = y;
        mTimeMs[i] = timeMs;
    }

    private static int index(int pointerId) {
        return pointerId >= 0 && pointerId < MAX_POINTERS ? pointerId : -1;
    }
}
