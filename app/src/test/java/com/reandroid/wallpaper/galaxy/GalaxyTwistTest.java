package com.reandroid.wallpaper.galaxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 旋臂的总扭曲量必须与屏幕宽度无关。
 *
 * <p>着色器拿到的 {@code dist} 是按屏幕宽度归一化的，所以原版那个写死的 5.5
 * 会让盘面的总扭曲变成 {@code 5.5 * 600 / width} 弧度 —— 宽度一涨，旋臂就散。
 * {@link GalaxyScene#twistFor(int)} 把宽度除掉之后，这里守的就是"总扭曲恒定"这一条。
 */
public class GalaxyTwistTest {

    private static final float GALAXY_RADIUS = 300.0f;

    /** 基准宽度下应当逐位等于原版那个 5.5。 */
    @Test
    public void referenceWidthKeepsTheOriginalConstant() {
        assertEquals(5.5f, GalaxyScene.twistFor(GalaxyScene.TWIST_REFERENCE_WIDTH), 0.0f);
    }

    /** 基准宽度上的总扭曲 = 5.5 * 600 / 640 ≈ 5.156 rad ≈ 295.4°。 */
    @Test
    public void totalTwistAtTheReferenceIsTheOriginalLook() {
        float scale = GALAXY_RADIUS / (GalaxyScene.TWIST_REFERENCE_WIDTH * 0.5f);
        float total = GalaxyScene.twistFor(GalaxyScene.TWIST_REFERENCE_WIDTH) * scale;
        assertEquals(5.156f, total, 1e-3f);
        assertEquals(295.4f, (float) Math.toDegrees(total), 0.5f);
    }

    /**
     * 核心不变式：任何宽度下，盘面边缘的总扭曲都相同。
     *
     * <p>取值范围覆盖到实际会遇到的档位：320（QVGA/HVGA）、480（WVGA）、640（基准）、
     * 720 / 1080 / 1440 / 2160（现代屏）。
     */
    @Test
    public void totalTwistIsIndependentOfWidth() {
        int[] widths = {320, 480, 640, 720, 1080, 1440, 2160, 3840};
        float expected = GalaxyScene.twistFor(widths[0]) * (GALAXY_RADIUS / (widths[0] * 0.5f));
        for (int width : widths) {
            float total = GalaxyScene.twistFor(width) * (GALAXY_RADIUS / (width * 0.5f));
            assertEquals("宽度 " + width, expected, total, 1e-4f);
        }
    }

    /**
     * 而且这个恒定的总扭曲要够"转得开"。
     *
     * <p>椭圆每 π 重复一次形状，所以总扭曲不能小于 π —— 那正是当年高分屏上
     * 旋臂消失的判据（3300 / π ≈ 1050 px 以上的屏就掉到 π 以下）。
     */
    @Test
    public void totalTwistExceedsHalfATurn() {
        for (int width : new int[] {320, 480, 720, 1080, 1440, 2160}) {
            float total = GalaxyScene.twistFor(width) * (GALAXY_RADIUS / (width * 0.5f));
            assertTrue("宽度 " + width + " 的总扭曲 " + total + " 不足半个周期", total > Math.PI);
        }
    }

    /** 宽度为 0（构造期、或还没测到尺寸）不能把系数算成 0 —— 那样整个盘面就不扭了。 */
    @Test
    public void degenerateWidthDoesNotCollapseTheTwist() {
        assertTrue(GalaxyScene.twistFor(0) > 0.0f);
        assertEquals(GalaxyScene.TWIST_BASE * 1.0f / GalaxyScene.TWIST_REFERENCE_WIDTH,
                GalaxyScene.twistFor(0), 1e-6f);
        assertEquals(GalaxyScene.twistFor(0), GalaxyScene.twistFor(-1920), 0.0f);
    }
}
