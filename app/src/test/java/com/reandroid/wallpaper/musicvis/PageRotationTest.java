package com.reandroid.wallpaper.musicvis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link PageRotation#degrees} 的回归测试。纯函数、无 android 依赖，JVM 直接跑。
 *
 * <p>核心约束：{@link PageRotation#MODE_PER_PAGE} 每页固定多转 120°，与桌面页数无关；
 * {@link PageRotation#MODE_LEGACY}、未知模式、null 都逐位保持 AOSP 原公式
 * {@code xOffset * 4 * factor}。真实桌面步长取自 {@code onOffsetsChanged} 的 xStep。
 */
public class PageRotationTest {

    private static final float FFT = 360f;
    private static final float PCM = 180f;
    private static final float EPS = 0.001f;

    /** Lawnchair 4 页：xStep = 1/3。 */
    private static final float STEP4 = 1f / 3f;

    private static void assertDeg(
            float expected, float xOffset, float xStep, float factor, String mode) {
        assertEquals(expected, PageRotation.degrees(xOffset, xStep, factor, mode), EPS);
    }

    // ---- 原版模式 ----

    @Test
    public void legacyMode_keepsTheLegacyFormula() {
        assertDeg(480f, STEP4, STEP4, FFT, PageRotation.MODE_LEGACY);
        assertDeg(240f, STEP4, STEP4, PCM, PageRotation.MODE_LEGACY);
        assertDeg(720f, 0.5f, 0.5f, FFT, PageRotation.MODE_LEGACY);
    }

    @Test
    public void unknownMode_keepsTheLegacyFormula() {
        assertDeg(720f, 0.5f, 0.5f, FFT, "some_future_mode");
        assertDeg(720f, 0.5f, 0.5f, FFT, null);
    }

    // ---- 每页 +120° ----

    @Test
    public void fourPages_restsAt120PerPage() {
        assertDeg(0f, 0f, STEP4, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(120f, STEP4, STEP4, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(240f, 2f * STEP4, STEP4, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(360f, 1f, STEP4, FFT, PageRotation.MODE_PER_PAGE);
    }

    @Test
    public void threePages_restsAt120PerPage() {
        assertDeg(0f, 0f, 0.5f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(120f, 0.5f, 0.5f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(240f, 1f, 0.5f, FFT, PageRotation.MODE_PER_PAGE);
    }

    @Test
    public void twoPages_restsAt120PerPage() {
        assertDeg(0f, 0f, 1f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(120f, 1f, 1f, FFT, PageRotation.MODE_PER_PAGE);
    }

    @Test
    public void fivePages_restsAt120PerPage() {
        assertDeg(0f, 0f, 0.25f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(120f, 0.25f, 0.25f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(240f, 0.5f, 0.25f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(360f, 0.75f, 0.25f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(480f, 1f, 0.25f, FFT, PageRotation.MODE_PER_PAGE);
    }

    @Test
    public void fractionalPage_interpolatesLinearly() {
        assertDeg(180f, 0.5f, STEP4, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(60f, 0.25f, 0.5f, PCM, PageRotation.MODE_PER_PAGE);
    }

    // ---- 每页模式但步长无效：回退原公式 ----

    @Test
    public void nonPositiveStep_fallsBackToTheLegacyFormula() {
        assertDeg(720f, 0.5f, 0f, FFT, PageRotation.MODE_PER_PAGE);
        assertDeg(720f, 0.5f, -1f, FFT, PageRotation.MODE_PER_PAGE);
    }

    @Test
    public void nanStep_fallsBackToTheLegacyFormula() {
        assertDeg(720f, 0.5f, Float.NaN, FFT, PageRotation.MODE_PER_PAGE);
    }
}
