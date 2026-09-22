package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 逐叶受光的回归。
 *
 * <p>这一层是第二版和第一版的分水岭：第一版只有「屏幕位置」和「轮廓线」，
 * 于是同一距离上每一片叶拿到的值**数学上必然相等**，整片草一起变金 —— 那就是塑料感的来源。
 * 这里补的是"这片叶怎么对着光"。
 *
 * <p>纯数学，无 Android 依赖：{@link Blade} 可以在 JVM 里直接构造
 * （{@link GrassBladeGeometryTest} 就是这么做的）。
 */
public class GrassBladeLightingTest {

    /** 竖直叶片：叶根在 (100, 500)，叶尖在 (100, 400) —— 屏幕坐标 y 向下。 */
    private static float facingOfVerticalBlade(float lightX, float lightY) {
        return GrassBladeLighting.facing(100.0f, 500.0f, 100.0f, 400.0f, lightX, lightY);
    }

    /** 光在叶片右侧 → 正；左侧 → 负。符号决定亮边落在哪一条边。 */
    @Test
    public void facingIsSignedByWhichSideTheLightIsOn() {
        float fromRight = facingOfVerticalBlade(900.0f, 450.0f);
        float fromLeft = facingOfVerticalBlade(-700.0f, 450.0f);

        assertTrue("光在右边时应当为正，实际 " + fromRight, fromRight > 0.0f);
        assertTrue("光在左边时应当为负，实际 " + fromLeft, fromLeft < 0.0f);
        assertEquals("两侧对称时绝对值应当相等", Math.abs(fromRight), Math.abs(fromLeft), 1.0E-4f);
    }

    /** 必须落在 [-1,1] —— 它会被当插值参数用。 */
    @Test
    public void facingStaysInRange() {
        for (float angle = 0.0f; angle < 360.0f; angle += 11.3f) {
            double rad = Math.toRadians(angle);
            float lx = 100.0f + (float) Math.cos(rad) * 5000.0f;
            float ly = 450.0f + (float) Math.sin(rad) * 5000.0f;
            float f = facingOfVerticalBlade(lx, ly);
            assertTrue("angle=" + angle + " 越界：" + f, f >= -1.0f && f <= 1.0f);
        }
    }

    /**
     * **叶片摆动时必须跟着变。**
     *
     * <p>这条最容易被重构悄悄丢掉，而丢掉的后果是特效从"活的"退化成"贴上去的"——
     * 正好是这一版要解决的那个毛病。GDC 的草渲染讲法把这个叫风与光照的耦合。
     *
     * <p>位形取**光源在叶片走向方向上**（正下方）：这是朝向最敏感的位置。叶片笔直时
     * 横截面轴与光向垂直、受光为 0；摆开之后才开始对准光。摆在光这一侧为正、另一侧为负
     * —— 也就是亮边会换边。换边发生在 {@code facing} 过零处，那里 {@code |beam|} 本来就接近 0，
     * 所以看着不会闪。
     */
    @Test
    public void facingFollowsTheBladeAsItSways() {
        float upright = GrassBladeLighting.facing(
                100.0f, 500.0f, 100.0f, 400.0f, 100.0f, 900.0f);
        // 同一条叶子摆 30°：叶尖从 (100,400) 分别摆到右侧 (150,413.4) 与左侧 (50,413.4)
        float bentRight = GrassBladeLighting.facing(
                100.0f, 500.0f, 150.0f, 413.4f, 100.0f, 900.0f);
        float bentLeft = GrassBladeLighting.facing(
                100.0f, 500.0f, 50.0f, 413.4f, 100.0f, 900.0f);

        assertEquals("笔直时横截面轴与光向垂直，应当为 0", 0.0f, upright, 1.0E-4f);
        assertTrue("向右摆应当偏正：" + bentRight, bentRight > 0.3f);
        assertTrue("向左摆应当偏负：" + bentLeft, bentLeft < -0.3f);
    }

    /** 退化输入不能产生 NaN —— 那会糊满整片草，而且不报错。 */
    @Test
    public void facingSurvivesDegenerateInput() {
        float zeroLength = GrassBladeLighting.facing(100.0f, 500.0f, 100.0f, 500.0f, 900.0f, 450.0f);
        float lightOnBase = GrassBladeLighting.facing(100.0f, 500.0f, 100.0f, 400.0f, 100.0f, 500.0f);

        assertEquals("零长叶片应当给 0", 0.0f, zeroLength, 0.0f);
        assertEquals("光源与叶根重合应当给 0", 0.0f, lightOnBase, 0.0f);
    }
}
