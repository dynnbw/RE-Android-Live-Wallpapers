package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 逐叶萤火虫遮挡的守卫。
 *
 * <p>要守的是**方向**。太阳那条数的是"先画的"（远的）叶片，萤火虫这条数的是"后画的"
 * （近的）—— 两条式子一模一样、只是顺序反了。写反了不会报错，画面也不会坏到看得出来，
 * 只会是"影子朝着光的那一边"，属于看半天也说不清哪里不对的那种。
 */
public class GrassFireflyShadowTest {

    private static final float RADIUS = GrassConstants.FIREFLY_LIGHT_RADIUS;

    /** 一片竖直的草：叶根在 (x, y)，向上长 reach 那么高。 */
    private static Blade blade(float x, float y) {
        Blade b = new Blade();
        b.xPos = x;
        b.yPos = y;
        b.size = 8;
        b.scale = 0.5f;
        b.lengthX = 30.0f;
        b.lengthY = 30.0f;
        return b;
    }

    /** 一只萤火虫，写进光源数组的第 k 个槽位。 */
    private static void light(float[] lights, int k, float x, float y, float glow) {
        lights[k * 3] = x;
        lights[k * 3 + 1] = y;
        lights[k * 3 + 2] = glow;
    }

    /** 半径外的萤火虫不该影响任何叶片。 */
    @Test
    public void aLightOutOfReachLeavesEveryoneAtOne() {
        Blade[] blades = {blade(100.0f, 1000.0f), blade(120.0f, 1000.0f)};
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        light(lights, 0, 100.0f + RADIUS * 4.0f, 1000.0f, 1.0f);

        float[] out = new float[blades.length];
        GrassFireflyShadow.compute(blades, lights, 1, RADIUS, out);
        assertEquals(1.0f, out[0], 0.0f);
        assertEquals(1.0f, out[1], 0.0f);
    }

    /**
     * 本测试要守的那一条：**挡光的是后画的（近的）**。
     *
     * <p>第 1 片盖在第 0 片与光源之间，第 0 片应当被压暗；反过来第 1 片自己不受第 0 片
     * 影响 —— 第 0 片在它后面，光是从前面来的。
     */
    @Test
    public void theNearBladeShadowsTheFarOneNotTheOtherWayRound() {
        // 光源离第 0 片 150 像素（在半径内，不然它根本不参与），第 1 片正卡在中间
        Blade[] blades = {blade(100.0f, 1000.0f), blade(100.0f, 930.0f)};
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        light(lights, 0, 100.0f, 850.0f, 1.0f);

        float[] out = new float[blades.length];
        GrassFireflyShadow.compute(blades, lights, 1, RADIUS, out);

        assertTrue("远处的草没有被近处的挡住", out[0] < 1.0f);
        assertEquals("近处的草不该被后面的草挡住", 1.0f, out[1], 0.0f);
    }

    /** 反过来摆（挡光的那片画在前面）就不该有遮挡 —— 用来钉死判据不是"谁离得近"。 */
    @Test
    public void aBladeDrawnEarlierDoesNotShadow() {
        Blade[] blades = {blade(100.0f, 930.0f), blade(100.0f, 1000.0f)};
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        light(lights, 0, 100.0f, 850.0f, 1.0f);

        float[] out = new float[blades.length];
        GrassFireflyShadow.compute(blades, lights, 1, RADIUS, out);
        // 第 1 片在 y=1000，光源在 y=850，中间只夹着第 0 片 —— 而它是**先画的**（更远）
        assertEquals("先画的草不该挡住后面的", 1.0f, out[1], 0.0f);
    }

    /**
     * 代表光源取的是**最近**的那只，不是最亮的。
     *
     * <p>按亮度挑的话，两只挨得差不多的萤火虫会随着眨眼轮流被选中，整片叶的阴影每帧跟着
     * 跳 —— 画面上就是一片片硬闪的暗斑。
     *
     * <p>摆法：近的那只暗、远的那只亮；只有**近**的那条视线被挡住。取最近 → 有阴影；
     * 取最亮 → 没阴影。
     */
    @Test
    public void theNearestLightRepresentsTheBladeNotTheBrightest() {
        Blade[] blades = {blade(100.0f, 1000.0f), blade(100.0f, 900.0f)};
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        light(lights, 0, 100.0f, 880.0f, 0.10f);   // 近、暗，且 (100,900) 那片挡在中间
        light(lights, 1, 300.0f, 1000.0f, 1.00f);  // 远、亮，视线横向，没有东西挡

        float[] out = new float[blades.length];
        GrassFireflyShadow.compute(blades, lights, 2, RADIUS, out);
        assertTrue("挑的是最亮的那只 —— 亮度一振荡，阴影就会硬跳", out[0] < 1.0f);
    }

    /** 反过来：把最亮的那只挪到近处，结论不该变（阴影仍有）—— 钉死判据是距离不是亮度。 */
    @Test
    public void swappingTheGlowsDoesNotSwapTheVerdict() {
        Blade[] blades = {blade(100.0f, 1000.0f), blade(100.0f, 900.0f)};
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        light(lights, 0, 100.0f, 880.0f, 1.00f);
        light(lights, 1, 300.0f, 1000.0f, 0.10f);

        float[] out = new float[blades.length];
        GrassFireflyShadow.compute(blades, lights, 2, RADIUS, out);
        assertTrue("两只的亮度对调之后结论变了 —— 判据不是距离", out[0] < 1.0f);
    }

    /** 没有光源时全体归 1，而不是留下上一帧的数。 */
    @Test
    public void noLightsMeansNoShadow() {
        Blade[] blades = {blade(100.0f, 1000.0f), blade(120.0f, 1000.0f)};
        float[] out = new float[]{0.2f, 0.3f};
        GrassFireflyShadow.compute(blades, new float[GrassConstants.FIREFLY_LIGHT_MAX * 3], 0,
                RADIUS, out);
        assertEquals(1.0f, out[0], 0.0f);
        assertEquals(1.0f, out[1], 0.0f);
    }

    /** 数据不全时安静退出，不抛也不越界。 */
    @Test
    public void degenerateInputIsIgnored() {
        float[] lights = new float[GrassConstants.FIREFLY_LIGHT_MAX * 3];
        GrassFireflyShadow.compute(null, lights, 1, RADIUS, new float[4]);
        GrassFireflyShadow.compute(new Blade[]{blade(1.0f, 1.0f)}, lights, 1, RADIUS, null);
        // 输出数组比叶片少：不写、不抛
        GrassFireflyShadow.compute(new Blade[]{blade(1.0f, 1.0f), blade(2.0f, 2.0f)},
                lights, 1, RADIUS, new float[1]);
    }
}
