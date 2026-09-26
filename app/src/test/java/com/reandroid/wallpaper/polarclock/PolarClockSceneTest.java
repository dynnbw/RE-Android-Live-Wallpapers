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
        return scene(showSeconds, variableWidth, PolarClockScene.RING_THICKNESS_SCREEN);
    }

    private static PolarClockScene scene(boolean showSeconds, boolean variableWidth,
                                         String ringThickness) {
        PolarClockScene s = new PolarClockScene();
        s.showSeconds = showSeconds;
        s.variableLineWidth = variableWidth;
        s.ringThickness = ringThickness;
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

    // ─────────── 环厚档位 ───────────

    /** 参考屏的宽度与它当年的外圈半径。改这两个数就是改"仿的是哪块屏"。 */
    private static final int[] REF_WIDTHS = {480, 640, 720};
    private static final String[] REF_VALUES = {
            PolarClockScene.RING_THICKNESS_REF480,
            PolarClockScene.RING_THICKNESS_REF640,
            PolarClockScene.RING_THICKNESS_REF720};

    /** 随屏档 = 原版行为：环厚就是那些写死的像素，一个不少一个不多。 */
    @Test
    public void screenPresetKeepsTheOriginalPixels() {
        PolarClockScene s = scene(true, true, PolarClockScene.RING_THICKNESS_SCREEN);
        fill(s);
        s.layout(1080, 2400, 0L);
        assertEquals("外圈半径", 1080 * 0.5f - PolarClockScene.DEFAULT_RING_THICKNESS,
                s.ringAt(0).radius, EPS);
        assertEquals("秒环厚度", PolarClockScene.SMALL_RING_THICKNESS, s.ringAt(0).thickness, EPS);
        assertEquals("分环厚度", PolarClockScene.MEDIUM_RING_THICKNESS, s.ringAt(1).thickness, EPS);
        assertEquals("时环厚度", PolarClockScene.LARGE_RING_THICKNESS, s.ringAt(2).thickness, EPS);
        assertEquals("日环厚度", PolarClockScene.MEDIUM_RING_THICKNESS, s.ringAt(3).thickness, EPS);
        assertEquals("月环厚度", PolarClockScene.LARGE_RING_THICKNESS, s.ringAt(4).thickness, EPS);
    }

    /**
     * 核心不变式：三档在**任何**屏幕上都保持参考屏那年的「环厚 ÷ 外圈半径」。
     *
     * <p>这正是"复现当年那块屏的观感"的定义。它也顺带钉住了缩放口径：
     * 系数必须是 {@code 当前 min(宽,高) / 参考宽}，因为参考屏的外圈半径
     * （如 480 那台的 216 = 480/2 - 24）本身就带那个 24 的内缩，只有整体
     * 等比缩放才能让比例严格相等。
     */
    @Test
    public void referencePresetsPreserveTheReferenceProportion() {
        for (int i = 0; i < REF_WIDTHS.length; i++) {
            int refWidth = REF_WIDTHS[i];
            float refOuter = refWidth * 0.5f - PolarClockScene.DEFAULT_RING_THICKNESS;
            float expected = PolarClockScene.LARGE_RING_THICKNESS / refOuter;
            for (int width : new int[] {320, 480, 640, 720, 1080, 1440, 2160}) {
                PolarClockScene s = scene(true, true, REF_VALUES[i]);
                fill(s);
                s.layout(width, width * 2, 0L);
                float actual = s.ringAt(2).thickness / s.ringAt(0).radius;
                assertEquals(REF_VALUES[i] + " 在 " + width + " 宽上的环厚占比",
                        expected, actual, 1e-4f);
            }
        }
    }

    /** 在同一块屏上，三档由粗到细：粗(480) > 经典(640) > 细(720)。 */
    @Test
    public void presetsAreOrderedThickToThin() {
        float prev = Float.MAX_VALUE;
        for (int i = 0; i < REF_VALUES.length; i++) {
            PolarClockScene s = scene(true, true, REF_VALUES[i]);
            fill(s);
            s.layout(1080, 2400, 0L);
            float thickness = s.ringAt(2).thickness;
            assertTrue(REF_VALUES[i] + " 应比上一档细（" + thickness + " vs " + prev + "）",
                    thickness < prev);
            prev = thickness;
        }
    }

    /**
     * 四档 × 各种屏宽下，五道环都必须仍然从外往内严格递减、且半径为正。
     *
     * <p>缩放把间隙一起带着走，是为了不让环互相压住；这条守着那个结果。
     * 320 宽是原版就会退化的尺寸，不在范围里。
     */
    @Test
    public void layoutStaysValidAtEveryPresetAndSize() {
        String[] presets = {PolarClockScene.RING_THICKNESS_SCREEN,
                PolarClockScene.RING_THICKNESS_REF480,
                PolarClockScene.RING_THICKNESS_REF640,
                PolarClockScene.RING_THICKNESS_REF720};
        for (String preset : presets) {
            for (int width : new int[] {480, 640, 720, 1080, 1440, 2160}) {
                PolarClockScene s = scene(true, true, preset);
                fill(s);
                s.layout(width, width * 2, 0L);
                for (int i = 0; i < s.ringCount(); i++) {
                    assertTrue(preset + " @ " + width + " 第 " + i + " 环半径应为正，实际 "
                            + s.ringAt(i).radius, s.ringAt(i).radius > 0f);
                    if (i > 0) {
                        assertTrue(preset + " @ " + width + " 第 " + i + " 环半径未递减",
                                s.ringAt(i).radius < s.ringAt(i - 1).radius);
                    }
                }
            }
        }
    }
}
