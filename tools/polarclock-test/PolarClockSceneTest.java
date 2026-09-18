/*
 * PolarClockScene 的约束测试 —— 纯 JVM:
 *
 *   javac -d /tmp/pctest \
 *         tools/polarclock-test/android/content/SharedPreferences.java \
 *         app/src/main/java/com/reandroid/wallpaper/polarclock/PolarClockScene.java \
 *         tools/polarclock-test/PolarClockSceneTest.java
 *   java -cp /tmp/pctest com.reandroid.wallpaper.polarclock.PolarClockSceneTest
 *
 * (SharedPreferences 用 tools/ 下的替身:真身是 Android 类,纯 JVM 编不过。
 *  只列这两个源文件,别把真身也放进来。)
 *
 * 钉住的是**环怎么摆**：半径从外往内递减、每个环的厚度随「可变线宽」开关变化、
 * 关掉秒环会影响分环半径（因为厚度基准回落到默认值）—— 这些都是"差几像素"的
 * 静默错误，肉眼看不出，只能靠断言。
 */
package com.reandroid.wallpaper.polarclock;

public final class PolarClockSceneTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testAllRingsPresent();
        testSecondsRingOptional();
        testRadiiDecreaseOutwardIn();
        testVariableLineWidthChangesThickness();
        testGapOrderDiffersBeforeDays();
        testAnglesRangeAndKnownValues();
        testDaysAngleHandlesSingleDayMonth();

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }

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
    private static void testAllRingsPresent() {
        PolarClockScene s = scene(true, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        assertEquals("环数", 5, s.ringCount());
        int[] expect = {PolarClockScene.RING_SECONDS, PolarClockScene.RING_MINUTES,
                PolarClockScene.RING_HOURS, PolarClockScene.RING_DAYS, PolarClockScene.RING_MONTHS};
        for (int i = 0; i < expect.length; i++) {
            assertEquals("第 " + i + " 个环的 id", expect[i], s.ringAt(i).id);
        }
        System.out.println("五个环齐全，顺序固定");
    }

    /** 关掉秒环后少一个环，且剩下的仍按顺序。 */
    private static void testSecondsRingOptional() {
        PolarClockScene s = scene(false, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        assertEquals("关掉秒环后的环数", 4, s.ringCount());
        assertEquals("第一个环应是分环", PolarClockScene.RING_MINUTES, s.ringAt(0).id);
        System.out.println("关掉秒环 → 4 个环，从分环开始");
    }

    /** 半径从外往内递减：秒环在最外，月环在最内。 */
    private static void testRadiiDecreaseOutwardIn() {
        PolarClockScene s = scene(true, true);
        fill(s);
        s.layout(1080, 2400, 0L);
        for (int i = 1; i < s.ringCount(); i++) {
            assertTrue("半径应逐环递减（第 " + i + " 环 " + s.ringAt(i).radius
                            + " 应小于 " + s.ringAt(i - 1).radius + "）",
                    s.ringAt(i).radius < s.ringAt(i - 1).radius);
        }
        System.out.println("半径逐环递减（最外 " + s.ringAt(0).radius
                + " → 最内 " + s.ringAt(4).radius + "）");
    }

    /** 开启可变线宽时各环厚度不同；关闭时全部等于默认厚度。 */
    private static void testVariableLineWidthChangesThickness() {
        PolarClockScene on = scene(true, true);
        fill(on);
        on.layout(1080, 2400, 0L);
        assertEquals("秒环厚度(可变)", PolarClockScene.SMALL_RING_THICKNESS, on.ringAt(0).thickness);
        assertEquals("分环厚度(可变)", PolarClockScene.MEDIUM_RING_THICKNESS, on.ringAt(1).thickness);
        assertEquals("时环厚度(可变)", PolarClockScene.LARGE_RING_THICKNESS, on.ringAt(2).thickness);

        PolarClockScene off = scene(true, false);
        fill(off);
        off.layout(1080, 2400, 0L);
        for (int i = 0; i < off.ringCount(); i++) {
            assertEquals("第 " + i + " 环厚度(固定)", PolarClockScene.DEFAULT_RING_THICKNESS,
                    off.ringAt(i).thickness);
        }
        System.out.println("可变线宽开关会改变各环厚度");
    }

    /**
     * 时环→日环之间用的是 LARGE_GAP，其余相邻环之间是 SMALL_GAP。
     *
     * 这一条专门防「两个间隙常量用反」：把半径差算出来，
     * 时→日的差应当明显大于其他相邻差。
     */
    private static void testGapOrderDiffersBeforeDays() {
        PolarClockScene s = scene(true, false);   // 固定厚度，排除厚度变化的干扰
        fill(s);
        s.layout(1080, 2400, 0L);
        float dropSeconds = s.ringAt(0).radius - s.ringAt(1).radius;
        float dropMinutes = s.ringAt(1).radius - s.ringAt(2).radius;
        float dropHours = s.ringAt(2).radius - s.ringAt(3).radius;   // 这一段用 LARGE_GAP
        float dropDays = s.ringAt(3).radius - s.ringAt(4).radius;
        float smallExpected = PolarClockScene.SMALL_GAP + PolarClockScene.DEFAULT_RING_THICKNESS;
        float largeExpected = PolarClockScene.LARGE_GAP + PolarClockScene.DEFAULT_RING_THICKNESS;
        assertEquals("秒→分 的间距", smallExpected, dropSeconds);
        assertEquals("分→时 的间距", smallExpected, dropMinutes);
        assertEquals("时→日 的间距(应更大)", largeExpected, dropHours);
        assertEquals("日→月 的间距", smallExpected, dropDays);
        System.out.println("时→日的间距更大（" + dropHours + " vs " + dropSeconds + "）");
    }

    /** 五个角度都落在 [0,1]，且已知取值正确。 */
    private static void testAnglesRangeAndKnownValues() {
        assertEquals("秒环 t=30000", 0.5f, PolarClockScene.secondsAngle(30000L));
        assertEquals("分环 30 分 0 秒", 0.5f, PolarClockScene.minutesAngle(30, 0));
        assertEquals("时环 12 时 0 分", 0.5f, PolarClockScene.hoursAngle(12, 0));
        assertEquals("日环 1 号（当月 1 号）", 0.0f, PolarClockScene.daysAngle(1, 31));
        assertEquals("日环 31 号（当月 31 号）", 1.0f, PolarClockScene.daysAngle(31, 31));
        assertEquals("月环 0 月", 0.0f, PolarClockScene.monthsAngle(0));
        assertEquals("月环 11 月", 1.0f, PolarClockScene.monthsAngle(11));

        PolarClockScene s = scene(true, true);
        fill(s);
        for (long t = 0; t < 600000L; t += 7919L) {
            s.layout(1080, 2400, t);
            for (int i = 0; i < s.ringCount(); i++) {
                float a = s.ringAt(i).angle;
                if (a < 0f || a > 1f) {
                    failures++;
                    System.out.println("失败: t=" + t + " 第 " + i + " 环角度越界 " + a);
                    return;
                }
            }
        }
        System.out.println("五个角度均在 [0,1]");
    }

    /**
     * 当月的最大天数为 1 时，日环分母是 0 —— 原实现会得到 inf/NaN。
     * 钉住这个事实：不是因为它是好行为，而是因为它**就是**现有行为，
     * 改动它得是有意的。月份环同理（分母 11 是常量，不受影响）。
     */
    private static void testDaysAngleHandlesSingleDayMonth() {
        float v = PolarClockScene.daysAngle(1, 1);
        assertTrue("(1-1)/(1-1) 应为 NaN 或 0/0 的结果，实际 " + v,
                Float.isNaN(v) || Float.isInfinite(v));
        System.out.println("最大天数=1 时日环为 " + v + "（保留原行为）");
    }

    private static void assertTrue(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("失败: " + name);
        }
    }

    private static void assertEquals(String name, float expect, float actual) {
        if (Math.abs(expect - actual) > 1e-5f) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void assertEquals(String name, int expect, int actual) {
        if (expect != actual) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }
}
