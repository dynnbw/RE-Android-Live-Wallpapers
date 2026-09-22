package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 太阳本体随高度角的颜色。
 *
 * <p>要守住的是**方向**：高度角越低越红。反了的话日出日落会变成一轮冷蓝的太阳 ——
 * 而那正是这次要修的现象（原来是恒定的白偏蓝），所以这条断言就是这个改动的全部意义。
 */
public class GrassSunAppearanceTest {

    private static final float[] OUT = new float[3];

    /** 高空 = 参考实现的原值，低空 = 橙红。 */
    @Test
    public void endpointsMatch() {
        GrassSunAppearance.fill(90.0f, OUT);
        assertEquals("高空 R", GrassSunAppearance.HIGH_R, OUT[0], 1.0E-5f);
        assertEquals("高空 G", GrassSunAppearance.HIGH_G, OUT[1], 1.0E-5f);
        assertEquals("高空 B", GrassSunAppearance.HIGH_B, OUT[2], 1.0E-5f);

        GrassSunAppearance.fill(0.0f, OUT);
        assertEquals("低空 R", GrassSunAppearance.LOW_R, OUT[0], 1.0E-5f);
        assertEquals("低空 G", GrassSunAppearance.LOW_G, OUT[1], 1.0E-5f);
        assertEquals("低空 B", GrassSunAppearance.LOW_B, OUT[2], 1.0E-5f);
    }

    /** 越高越白：红降、绿升、蓝升，整体从暖到冷。 */
    @Test
    public void altitudeMakesItCooler() {
        float[] low = new float[3];
        float[] high = new float[3];
        GrassSunAppearance.fill(0.0f, low);
        GrassSunAppearance.fill(30.0f, high);

        assertTrue("红应当随高度下降", high[0] < low[0]);
        assertTrue("绿应当随高度上升", high[1] > low[1]);
        assertTrue("蓝应当随高度上升", high[2] > low[2]);
    }

    /** 单调：逐度走一遍，三个分量都不该回头。 */
    @Test
    public void transitionIsMonotonic() {
        float[] cur = new float[3];
        float[] prev = new float[3];
        GrassSunAppearance.fill(-10.0f, prev);
        for (float alt = -9.0f; alt <= 90.0f; alt += 1.0f) {
            GrassSunAppearance.fill(alt, cur);
            assertTrue("alt=" + alt + " 红回升了", cur[0] <= prev[0] + 1.0E-6f);
            assertTrue("alt=" + alt + " 绿回落了", cur[1] >= prev[1] - 1.0E-6f);
            assertTrue("alt=" + alt + " 蓝回落了", cur[2] >= prev[2] - 1.0E-6f);
            System.arraycopy(cur, 0, prev, 0, 3);
        }
    }

    /** 地平线以下不再变得更红（那边太阳本来就已经淡到看不见了）。 */
    @Test
    public void belowHorizonHoldsTheLowColour() {
        float[] below = new float[3];
        float[] zero = new float[3];
        GrassSunAppearance.fill(-20.0f, below);
        GrassSunAppearance.fill(0.0f, zero);
        assertEquals(below[0], zero[0], 1.0E-6f);
        assertEquals(below[1], zero[1], 1.0E-6f);
        assertEquals(below[2], zero[2], 1.0E-6f);
    }

    /** 低空必须是暖的：红 > 绿 > 蓝 —— 这就是"橙红"。 */
    @Test
    public void lowAltitudeIsWarm() {
        GrassSunAppearance.fill(0.0f, OUT);
        assertTrue("红应大于绿", OUT[0] > OUT[1]);
        assertTrue("绿应大于蓝", OUT[1] > OUT[2]);
    }

    /** 高空是冷的：蓝 > 绿 > 红。 */
    @Test
    public void highAltitudeIsCool() {
        GrassSunAppearance.fill(45.0f, OUT);
        assertTrue("蓝应大于绿", OUT[2] > OUT[1]);
        assertTrue("绿应大于红", OUT[1] > OUT[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUndersizedArray() {
        GrassSunAppearance.fill(30.0f, new float[2]);
    }

    // ---- 不透明度 ----

    /**
     * **地平线及以上必须完全不透明。**
     *
     * <p>这条就是这个改动的全部意义：原来地平线处只有 0.5，太阳在低空是半透明的，
     * 叠在亮蓝天空上像"化开了"。
     */
    @Test
    public void sunIsSolidAtAndAboveTheHorizon() {
        assertEquals("地平线处应完全不透明", 1.0f, GrassSunAppearance.opacity(0.0f), 1.0E-5f);
        for (float alt = 0.0f; alt <= 90.0f; alt += 1.0f) {
            assertEquals("alt=" + alt + " 不该有任何透明",
                    1.0f, GrassSunAppearance.opacity(alt), 1.0E-5f);
        }
    }

    /** 地平线以下淡出到 0，而且单调。 */
    @Test
    public void belowTheHorizonFadesOutMonotonically() {
        assertEquals("淡出到底", 0.0f, GrassSunAppearance.opacity(-10.0f), 1.0E-5f);
        float prev = GrassSunAppearance.opacity(-10.0f);
        for (float alt = -9.0f; alt <= 0.0f; alt += 0.25f) {
            float cur = GrassSunAppearance.opacity(alt);
            assertTrue("alt=" + alt + " 处不透明度回落了：" + prev + " -> " + cur,
                    cur >= prev - 1.0E-6f);
            prev = cur;
        }
    }

    /** 淡出窗口就是声明的那个宽度，别悄悄变宽 —— 变宽就又回到"低空半透明"了。 */
    @Test
    public void fadeWindowIsAsDeclared() {
        assertEquals(0.0f, GrassSunAppearance.opacity(-GrassSunAppearance.FADE_BELOW_DEG), 1.0E-5f);
        assertTrue("窗口应当很窄，实为 " + GrassSunAppearance.FADE_BELOW_DEG,
                GrassSunAppearance.FADE_BELOW_DEG <= 6.0f);
    }
}
