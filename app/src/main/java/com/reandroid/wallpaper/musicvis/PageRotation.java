package com.reandroid.wallpaper.musicvis;

/**
 * 音乐可视化的翻页旋转角。
 *
 * <p>AOSP 原版把 {@code (xOffset * 4) * factor} 写死，那个 4 等价于「每页偏移 0.25」，
 * 只在 5 页桌面上成立。桌面实际步长由 {@code onOffsetsChanged} 的 xStep 给出；
 * {@link #MODE_PER_PAGE} 按真实步长归一化，每页固定多转 {@link #DEGREES_PER_PAGE}。
 *
 * <p>纯函数，可在 JVM 上直接测试。
 */
public final class PageRotation {

    /** 原版行为：{@code (xOffset * 4) * factor}，依赖 5 页桌面的假设。 */
    public static final String MODE_LEGACY = "legacy";

    /** 每页固定 +120°，与桌面页数无关。 */
    public static final String MODE_PER_PAGE = "per_page";

    /** 节拍驱动：不响应桌面偏移，角度由 {@link BeatRotation} 维护。 */
    public static final String MODE_BEAT = "beat";

    /** {@link #MODE_PER_PAGE} 每页的旋转角度。 */
    public static final float DEGREES_PER_PAGE = 120f;

    private PageRotation() {}

    /**
     * @param xOffset      桌面水平偏移，0..1
     * @param xStep        桌面每页的偏移步长；{@code <= 0} 或 NaN 表示拿不到
     * @param legacyFactor 原版每 0.25 偏移转过的角度：FFT = 360，PCM = 180
     * @param mode         旋转模式；未知值按原版处理
     */
    public static float degrees(float xOffset, float xStep, float legacyFactor, String mode) {
        if (MODE_PER_PAGE.equals(mode) && xStep > 0f) {
            return (xOffset / xStep) * DEGREES_PER_PAGE;
        }
        return (xOffset * 4f) * legacyFactor;
    }
}
