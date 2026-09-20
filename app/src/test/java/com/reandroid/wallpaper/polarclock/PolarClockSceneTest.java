package com.reandroid.wallpaper.polarclock;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * PolarClockScene 的约束测试。
 *
 * 钉住的是**环怎么摆**：半径从外往内递减、每个环的厚度随「可变线宽」开关变化、
 * 关掉秒环会影响分环半径（因为厚度基准回落到默认值）—— 这些都是"差几像素"的
 * 静默错误，肉眼看不出，只能靠断言。
 */
public class PolarClockSceneTest {

    private static final float EPS = 1e-5f;

    private static PolarClockScene scene(boolean showSeconds, boolean variableWidth) {
        PolarClockScene s = new PolarClockScene();
        s.showSeconds = showSeconds;
        s.variableLineWidth = variableWidth;
        return s;
    }

    private static void fill(PolarClockScene s) {
        PolarClockScene.DateFields d = s.dateFields();
        d.second = 7;
        d.minute = 23;
        d.hour = 9;
        d.monthDay = 14;
        d.maxMonthDay = 30;
        d.month = 5;
    }

    /** 普通情况五个环齐全，且 id 顺序固定。 */
    @Test
    public void allRingsPresentInFixedOrder() {
        PolarClockScene s = scene(true, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        assertEquals("环数", 5, s.ringCount());
        int[] expect = {PolarClockScene.RING_SECONDS, PolarClockScene.RING_MINUTES,
                PolarClockScene.RING_HOURS, PolarClockScene.RING_DAYS, PolarClockScene.RING_MONTHS};
        for (int i = 0; i < expect.length; i++) {
            assertEquals("第 " + i + " 个环的 id", expect[i], s.ringAt(i).id);
        }
    }

    /** 关掉秒环后少一个环，且剩下的仍按顺序。 */
    @Test
    public void secondsRingIsOptional() {
        PolarClockScene s = scene(false, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        assertEquals("关掉秒环后的环数", 4, s.ringCount());
        assertEquals("第一个环应是分环", PolarClockScene.RING_MINUTES, s.ringAt(0).id);
    }

    /** 半径从外往内递减：秒环在最外，月环在最内。 */
    @Test
    public void radiiDecreaseOutwardIn() {
        PolarClockScene s = scene(true, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        for (int i = 1; i < s.ringCount(); i++) {
            assertTrue("半径应逐环递减（第 " + i + " 环 " + s.ringAt(i).radius
                            + " 应小于 " + s.ringAt(i - 1).radius + "）",
                    s.ringAt(i).radius < s.ringAt(i - 1).radius);
        }
    }

    /** 开启可变线宽时各环厚度不同；关闭时全部等于默认厚度。 */
    @Test
    public void variableLineWidthChangesThickness() {
        PolarClockScene on = scene(true, true);
        fill(on);
        on.layout(1080, 2400, 0L);
        assertEquals("秒环厚度(可变)", PolarClockScene.SMALL_RING_THICKNESS, on.ringAt(0).thickness, EPS);
        assertEquals("分环厚度(可变)", PolarClockScene.MEDIUM_RING_THICKNESS, on.ringAt(1).thickness, EPS);
        assertEquals("时环厚度(可变)", PolarClockScene.LARGE_RING_THICKNESS, on.ringAt(2).thickness, EPS);

        PolarClockScene off = scene(true, false);
        fill(off);
        off.layout(1080, 2400, 0L);
        for (int i = 0; i < off.ringCount(); i++) {
            assertEquals("第 " + i + " 环厚度(固定)", PolarClockScene.DEFAULT_RING_THICKNESS,
                    off.ringAt(i).thickness, EPS);
        }
    }

    /**
     * 时环→日环之间用的是 LARGE_GAP，其余相邻环之间是 SMALL_GAP。
     *
     * 这一条专门防「两个间隙常量用反」：把半径差算出来，
     * 时→日的差应当明显大于其他相邻差。
     */
    @Test
    public void gapBeforeDaysRingIsLarger() {
        PolarClockScene s = scene(true, false);   // 固定厚度，排除厚度变化的干扰
        fill(s);
        s.layout(1080, 2400, 0L);
        float dropSeconds = s.ringAt(0).radius - s.ringAt(1).radius;
        float dropMinutes = s.ringAt(1).radius - s.ringAt(2).radius;
        float dropHours = s.ringAt(2).radius - s.ringAt(3).radius;   // 这一段用 LARGE_GAP
        float dropDays = s.ringAt(3).radius - s.ringAt(4).radius;
        float smallExpected = PolarClockScene.SMALL_GAP + PolarClockScene.DEFAULT_RING_THICKNESS;
        float largeExpected = PolarClockScene.LARGE_GAP + PolarClockScene.DEFAULT_RING_THICKNESS;
        assertEquals("秒→分 的间距", smallExpected, dropSeconds, EPS);
        assertEquals("分→时 的间距", smallExpected, dropMinutes, EPS);
        assertEquals("时→日 的间距(应更大)", largeExpected, dropHours, EPS);
        assertEquals("日→月 的间距", smallExpected, dropDays, EPS);
    }

    /** 五个角度都落在 [0,1]，且已知取值正确。 */
    @Test
    public void anglesAreInRangeWithKnownValues() {
        assertEquals("秒环 t=30000", 0.5f, PolarClockScene.secondsAngle(30000L), EPS);
        assertEquals("分环 30 分 0 秒", 0.5f, PolarClockScene.minutesAngle(30, 0), EPS);
        assertEquals("时环 12 时 0 分", 0.5f, PolarClockScene.hoursAngle(12, 0), EPS);
        assertEquals("日环 1 号（当月 1 号）", 0.0f, PolarClockScene.daysAngle(1, 31), EPS);
        assertEquals("日环 31 号（当月 31 号）", 1.0f, PolarClockScene.daysAngle(31, 31), EPS);
        assertEquals("月环 0 月", 0.0f, PolarClockScene.monthsAngle(0), EPS);
        assertEquals("月环 11 月", 1.0f, PolarClockScene.monthsAngle(11), EPS);

        PolarClockScene s = scene(true, true);
        fill(s);
        for (long t = 0; t < 600000L; t += 7919L) {
            s.layout(1080, 2400, t);
            for (int i = 0; i < s.ringCount(); i++) {
                float a = s.ringAt(i).angle;
                assertTrue("t=" + t + " 第 " + i + " 环角度越界 " + a, a >= 0f && a <= 1f);
            }
        }
    }

    /**
     * 当月的最大天数为 1 时，日环分母是 0 —— 原实现会得到 inf/NaN。
     * 钉住这个事实：不是因为它是好行为，而是因为它**就是**现有行为，
     * 改动它得是有意的。月份环同理（分母 11 是常量，不受影响）。
     */
    @Test
    public void daysAngleOnSingleDayMonthIsNotFinite() {
        float v = PolarClockScene.daysAngle(1, 1);
        assertTrue("(1-1)/(1-1) 应为 NaN 或 0/0 的结果，实际 " + v,
                Float.isNaN(v) || Float.isInfinite(v));
    }
}
