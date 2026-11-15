package com.reandroid.wallpaper.fall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 多指落点判定：每根手指各自计距、互不干扰。 */
public class FallTouchTrackerTest {

    /** 两根手指同时画圈，各自按自己的起点计算距离。 */
    @Test
    public void pointersAreIndependent() {
        FallTouchTracker tracker = new FallTouchTracker();
        tracker.down(0, 0f, 0f, 0L);
        tracker.down(1, 500f, 500f, 0L);

        // 0 号移动 60px：超过 42px 阈值
        assertTrue(tracker.move(0, 60f, 0f, 100L) >= 0f);
        // 1 号只移动 20px：还不到阈值
        assertEquals(-1f, tracker.move(1, 520f, 500f, 100L), 0f);
    }

    /** 不足阈值时不更新起点；触发后从新位置重新累计。 */
    @Test
    public void triggerOnlyAfterFullThreshold() {
        FallTouchTracker tracker = new FallTouchTracker();
        tracker.down(0, 0f, 0f, 0L);
        assertEquals(-1f, tracker.move(0, 30f, 0f, 10L), 0f);
        assertTrue(tracker.move(0, 60f, 0f, 20L) >= 0f);
        // 从 60 再走 30：不够 42，不触发
        assertEquals(-1f, tracker.move(0, 90f, 0f, 30L), 0f);
        // 距 60 已 50：触发
        assertTrue(tracker.move(0, 110f, 0f, 40L) >= 0f);
    }

    /** 速度越快功率越大；同一毫秒内移动按 0 功率处理，不除出 Infinity。 */
    @Test
    public void powerFollowsSpeed() {
        FallTouchTracker fast = new FallTouchTracker();
        fast.down(0, 0f, 0f, 0L);
        assertEquals(1f, fast.move(0, 100f, 0f, 10L), 0.001f);

        FallTouchTracker slow = new FallTouchTracker();
        slow.down(0, 0f, 0f, 0L);
        float slowPower = slow.move(0, 100f, 0f, 1000L);
        assertTrue(slowPower > 0f && slowPower < 1f);

        FallTouchTracker zeroDt = new FallTouchTracker();
        zeroDt.down(0, 0f, 0f, 500L);
        assertEquals(0f, zeroDt.move(0, 100f, 0f, 500L), 0f);
    }

    /** 抬起一根手指不影响另一根。 */
    @Test
    public void liftingOnePointerKeepsTheOther() {
        FallTouchTracker tracker = new FallTouchTracker();
        tracker.down(0, 0f, 0f, 0L);
        tracker.down(1, 0f, 0f, 0L);
        tracker.up(0);
        assertEquals(-1f, tracker.move(1, 20f, 0f, 10L), 0f);
        // 0 号已抬起，再移动按"刚按下"处理
        assertEquals(FallTouchTracker.TAP_POWER, tracker.move(0, 200f, 0f, 20L), 0f);
    }

    /** 越界的 pointerId 不崩。 */
    @Test
    public void outOfRangePointerIsSafe() {
        FallTouchTracker tracker = new FallTouchTracker();
        tracker.down(99, 0f, 0f, 0L);
        tracker.move(99, 100f, 0f, 10L);
        tracker.up(99);
        tracker.reset();
    }
}
