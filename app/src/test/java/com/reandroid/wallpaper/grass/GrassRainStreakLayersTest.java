package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 屏幕空间雨丝的层表。
 *
 * <p>这张表原来在参考实现的**顶点着色器**里算，对全屏四边形来说每帧算四次、结果完全一样。
 * 提到 Java 侧之后它就成了可测的纯函数 —— 这里守住的是"层次方向"：索引越大越细、越淡。
 * 反了的话远近关系就颠倒了，而那种错看着只是"有点怪"。
 */
public class GrassRainStreakLayersTest {

    private static final float[] ALPHA = new float[GrassRainStreakLayers.MAX_LAYERS];
    private static final float[] SCALE = new float[GrassRainStreakLayers.MAX_LAYERS];

    /** 索引越大：越细（scale 大）、越淡（alpha 小）。 */
    @Test
    public void laterLayersAreThinnerAndFainter() {
        GrassRainStreakLayers.fill(5.0f, GrassRainStreakLayers.MAX_LAYERS, ALPHA, SCALE);
        for (int i = 1; i < GrassRainStreakLayers.MAX_LAYERS; i++) {
            assertTrue("第 " + i + " 层应当比第 " + (i - 1) + " 层淡："
                            + ALPHA[i - 1] + " vs " + ALPHA[i],
                    ALPHA[i] < ALPHA[i - 1]);
            assertTrue("第 " + i + " 层应当比第 " + (i - 1) + " 层细："
                            + SCALE[i - 1] + " vs " + SCALE[i],
                    SCALE[i] > SCALE[i - 1]);
        }
    }

    /** 端点与参考实现的公式一致。 */
    @Test
    public void endpointsMatchTheReferenceFormula() {
        GrassRainStreakLayers.fill(5.0f, GrassRainStreakLayers.MAX_LAYERS, ALPHA, SCALE);
        assertEquals("第 0 层 alpha", 0.35f, ALPHA[0], 1.0E-5f);
        assertEquals("第 0 层 scale", 2.5f, SCALE[0], 1.0E-5f);
        assertEquals("第 1 层 scale", 4.0f, SCALE[1], 1.0E-5f);
    }

    /** filter 决定启用几层；未启用的层必须写成 0 —— 着色器靠 0 跳过。 */
    @Test
    public void filterLimitsTheEnabledLayers() {
        GrassRainStreakLayers.fill(3.0f, 2, ALPHA, SCALE);
        assertTrue("前两层应当启用", ALPHA[0] > 0.0f && ALPHA[1] > 0.0f);
        for (int i = 2; i < GrassRainStreakLayers.MAX_LAYERS; i++) {
            assertEquals("第 " + i + " 层应被停用（alpha）", 0.0f, ALPHA[i], 0.0f);
            assertEquals("第 " + i + " 层应被停用（scale）", 0.0f, SCALE[i], 0.0f);
        }
    }

    /** filter <= 0 表示一层都不要。 */
    @Test
    public void zeroFilterDisablesEverything() {
        GrassRainStreakLayers.fill(5.0f, 0, ALPHA, SCALE);
        for (int i = 0; i < GrassRainStreakLayers.MAX_LAYERS; i++) {
            assertEquals(0.0f, SCALE[i], 0.0f);
        }
    }

    /** 强度只影响浓淡，不影响粗细。 */
    @Test
    public void intensityOnlyChangesAlpha() {
        float[] a1 = new float[GrassRainStreakLayers.MAX_LAYERS];
        float[] s1 = new float[GrassRainStreakLayers.MAX_LAYERS];
        float[] a2 = new float[GrassRainStreakLayers.MAX_LAYERS];
        float[] s2 = new float[GrassRainStreakLayers.MAX_LAYERS];
        GrassRainStreakLayers.fill(1.0f, 3, a1, s1);
        GrassRainStreakLayers.fill(5.0f, 3, a2, s2);
        // 第 0 层的 alpha 是 0.35 - 0 * mix(...)，恒为 0.35 —— 参考实现如此，
        // 强度只影响后面几层的递减步长，所以那一层只能是 >=。
        assertEquals("第 0 层不该随强度变", a1[0], a2[0], 1.0E-5f);
        for (int i = 1; i < 3; i++) {
            assertEquals("第 " + i + " 层粗细不该随强度变", s1[i], s2[i], 0.0f);
            assertTrue("强度大时第 " + i + " 层应当更浓：" + a1[i] + " -> " + a2[i],
                    a2[i] > a1[i]);
        }
    }

    /** 整体不透明度：晴天为 0（整层不画），随强度单调。 */
    @Test
    public void opacityIsZeroWhenDryAndGrowsWithIntensity() {
        assertEquals("晴天不该画雨丝层", 0.0f, GrassRainStreakLayers.opacity(0.0f), 0.0f);
        assertTrue(GrassRainStreakLayers.opacity(2.5f) > 0.0f);
        assertTrue(GrassRainStreakLayers.opacity(5.0f) > GrassRainStreakLayers.opacity(2.5f));
        assertEquals("上限", 1.0f, GrassRainStreakLayers.opacity(5.0f), 1.0E-5f);
    }

    /** 数组太小要报错，而不是静默越界写。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUndersizedArrays() {
        GrassRainStreakLayers.fill(3.0f, 3, new float[2], new float[2]);
    }
}
