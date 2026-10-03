package com.reandroid.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FrameRatePolicy#decide} 的回归测试。纯函数、无 android 依赖，JVM 直接跑。
 *
 * <p>最要紧的一组是 {@link #vsyncPacingMatchesTheTarget()}：它把"壁纸真的跑得到设定的帧数"
 * 这件事钉在数值上 —— 目标不低于面板刷新率时必须交给 vsync，不能再补那个 1ms 的 sleep，
 * 否则 120Hz 屏会掉到 60。
 */
public class FrameRatePolicyTest {

    private static final float HZ60 = 60.0f;
    private static final float HZ120 = 120.0f;
    private static final float HZ90 = 90.0f;
    private static final String FOLLOW = FrameRatePolicy.FOLLOW_SCREEN;

    private static int fps(String pref, float hz, boolean saver, boolean linked) {
        return FrameRatePolicy.decide(pref, hz, saver, linked).fps;
    }

    private static boolean vsync(String pref, float hz, boolean saver, boolean linked) {
        return FrameRatePolicy.decide(pref, hz, saver, linked).vsyncPaced;
    }

    // ---- 跟随屏幕 ----

    @Test
    public void followScreen_usesThePanelRefreshRate() {
        assertEquals(120, fps(FOLLOW, HZ120, false, false));
        assertEquals(60, fps(FOLLOW, HZ60, false, false));
        assertEquals(90, fps(FOLLOW, HZ90, false, false));
    }

    @Test
    public void followScreen_roundsFractionalRefreshRates() {
        assertEquals(60, fps(FOLLOW, 59.94f, false, false));
        assertEquals(59, fps(FOLLOW, 59.4f, false, false));
        assertEquals(144, fps(FOLLOW, 143.7f, false, false));
    }

    @Test
    public void followScreen_unavailableRefreshFallsBack() {
        assertEquals(FrameRatePolicy.REFRESH_FALLBACK_FPS, fps(FOLLOW, 0f, false, false));
        assertEquals(FrameRatePolicy.REFRESH_FALLBACK_FPS, fps(FOLLOW, -1f, false, false));
        assertEquals(FrameRatePolicy.REFRESH_FALLBACK_FPS, fps(FOLLOW, Float.NaN, false, false));
        // 读不到也要交给 vsync —— 它不可能超过面板，是安全的
        assertTrue(vsync(FOLLOW, Float.NaN, false, false));
    }

    // ---- 这一条是本次的主要修复 ----

    @Test
    public void vsyncPacingMatchesTheTarget() {
        for (int target : new int[] {30, 60, 90, 120, 180}) {
            String pref = String.valueOf(target);
            // 180Hz 面板：任何不超过它的目标都得自己配速
            assertEquals(
                    "180Hz 面板 " + target + " FPS", target >= 180, vsync(pref, 180f, false, false));
            // 120Hz 面板：120 与 180 交给 vsync（180 自然封顶在 120），其余自己配速
            assertEquals(
                    "120Hz 面板 " + target + " FPS", target >= 120, vsync(pref, HZ120, false, false));
            // 60Hz 面板：只有 60 及以上交给 vsync
            assertEquals(
                    "60Hz 面板 " + target + " FPS", target >= 60, vsync(pref, HZ60, false, false));
        }
    }

    @Test
    public void unknownRefresh_doesNotHandFixedRatesToVsync() {
        // 刷新率读不到时不能假设 60：面板可能是 144Hz，把用户选的 60 交给 vsync 会跑成 144
        assertFalse(vsync("60", Float.NaN, false, false));
        assertFalse(vsync("30", 0f, false, false));
        assertEquals(60, fps("60", Float.NaN, false, false));
    }

    // ---- 固定档位 ----

    @Test
    public void fixedRatesAreParsedAsIs() {
        for (int target : new int[] {24, 30, 45, 60, 90, 120, 180}) {
            assertEquals(target, fps(String.valueOf(target), HZ60, false, false));
        }
    }

    @Test
    public void invalidValuesFallBackToDefault() {
        assertEquals(FrameRatePolicy.DEFAULT_FPS, fps(null, HZ60, false, false));
        assertEquals(FrameRatePolicy.DEFAULT_FPS, fps("", HZ60, false, false));
        assertEquals(FrameRatePolicy.DEFAULT_FPS, fps("abc", HZ60, false, false));
        assertEquals(FrameRatePolicy.DEFAULT_FPS, fps("0", HZ60, false, false));
        assertEquals(FrameRatePolicy.DEFAULT_FPS, fps("-30", HZ60, false, false));
    }

    // ---- 省电联动 ----

    @Test
    public void powerSaveCapsToTwentyFour() {
        assertEquals(FrameRatePolicy.POWER_SAVE_FPS, fps("60", HZ120, true, true));
        assertEquals(FrameRatePolicy.POWER_SAVE_FPS, fps("120", HZ120, true, true));
        assertEquals(FrameRatePolicy.POWER_SAVE_FPS, fps(FOLLOW, HZ120, true, true));
    }

    @Test
    public void powerSaveIsACapNotAFloor() {
        // 用户自己选了比 24 更低的（当前档位里没有，但别把口径写死成"强制 24"）
        assertEquals(24, fps("24", HZ120, true, true));
    }

    @Test
    public void powerSaveWithoutTheSwitchChangesNothing() {
        assertEquals(120, fps("120", HZ120, true, false));
        assertEquals(120, fps(FOLLOW, HZ120, true, false));
        assertEquals(60, fps("60", HZ60, true, false));
    }

    @Test
    public void powerSaveNeverRaisesABelowCapRefresh() {
        // 24Hz 的屏 + 省电：min(24, 24) = 24，不该被抬上去
        assertEquals(24, fps(FOLLOW, 24f, true, true));
    }

    @Test
    public void powerSaveDropsOutOfVsyncPacing() {
        // 24 低于面板刷新率，必须自己配速，不能交给 vsync
        assertFalse(vsync(FOLLOW, HZ120, true, true));
        assertFalse(vsync("120", HZ120, true, true));
    }

    // ---- 配速 sleep ----
    //
    // `vsyncPaced` 只放宽下限，**不是免除睡眠**。这一组守的就是这条：
    // 实测耗时接近 0（surface 没被合成时 eglSwapBuffers 根本不阻塞）那一次，
    // 曾经因为"vsync 已经在配速"而整个跳过 sleep → 循环没有上限，
    // 实测 120 帧跑不到 1ms、还伴生每秒数次的 GC。

    private static long sleep(long targetMs, boolean vsyncPaced, long costMs) {
        return FrameRatePolicy.pacingSleepMs(targetMs, vsyncPaced, costMs);
    }

    /** 面板 120Hz、目标 120、耗时 0（未被合成）：必须补满一个帧时，不能 0。 */
    @Test
    public void vsyncPacedStillSleepsWhenTheFrameWasFree() {
        assertEquals(8L, sleep(8L, true, 0L));
    }

    /** 同上但耗时已经超过目标（vsync 真的在等）：这才是那个 1ms 下限必须拿掉的场合。 */
    @Test
    public void vsyncPacedDoesNotAddTheFatalMillisecond() {
        assertEquals(0L, sleep(8L, true, 8L));
        // 耗时正好等于 vsync 周期 8.33ms 时，老公式给 max(1, -0.33) = 1ms → 掉到 60 帧
        assertEquals(0L, sleep(8L, true, 9L));
    }

    /** 未被 vsync 配速那一档保留下限 1ms（它就是靠 sleep 拉到目标帧率的）。 */
    @Test
    public void nonVsyncPacingKeepsTheOneMillisecondFloor() {
        assertEquals(1L, sleep(8L, false, 8L));
        assertEquals(1L, sleep(8L, false, 50L));
    }

    /** 两种情况在"还没到目标"时都补到目标，差别只在下限。 */
    @Test
    public void bothPaceTowardsTheTarget() {
        assertEquals(6L, sleep(8L, true, 2L));
        assertEquals(6L, sleep(8L, false, 2L));
        // 低帧率档：24 FPS → 41ms 帧时
        assertEquals(39L, sleep(41L, false, 2L));
    }

    /** 下限差一毫秒是刻意的，别把它当成笔误抹平。 */
    @Test
    public void theFloorIsTheOnlyDifference() {
        assertEquals(1L, sleep(8L, false, 100L) - sleep(8L, true, 100L));
    }
}
