package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 逆光颜色随光源走。
 *
 * <p>守两条：**黄金时刻那套琥珀原样保住**（改造前就有的观感，不能被这次改动冲淡），
 * 以及**月光必须是冷色**（B &gt; G &gt; R）—— 写成暖色的话月亮会看着像第二颗太阳，
 * 而这种错不会报任何错，只会"看着不对"。
 */
public class GrassLightColorTest {

    private final float[] out = new float[3];

    private static void assertSame(String what, float[] expected, float[] actual) {
        assertEquals(what + " R", expected[0], actual[0], 1.0E-5f);
        assertEquals(what + " G", expected[1], actual[1], 1.0E-5f);
        assertEquals(what + " B", expected[2], actual[2], 1.0E-5f);
    }

    // ---- 透光色 ----

    /** **贴着地平线必须是原来那套琥珀** —— 这次改造不该动黄金时刻。 */
    @Test
    public void theHorizonKeepsTheOriginalAmber() {
        GrassLightColor.transmit(0.0f, false, out);
        assertSame("地平线的透光色", GrassConstants.GRASS_LIGHT_TRANSMIT, out);
    }

    /** 正午切到"近白"那组。 */
    @Test
    public void noonGoesNearlyNeutral() {
        GrassLightColor.transmit(60.0f, false, out);
        assertSame("正午的透光色", GrassConstants.GRASS_LIGHT_TRANSMIT_DAY, out);
    }

    /** 月光是**冷色**：B > G > R。写成暖色就会像第二颗太阳。 */
    @Test
    public void moonlightIsCool() {
        GrassLightColor.transmit(-30.0f, true, out);
        assertTrue("月光应当蓝多于绿：" + out[2] + " vs " + out[1], out[2] > out[1]);
        assertTrue("月光应当绿多于红：" + out[1] + " vs " + out[0], out[1] > out[0]);
    }

    /** 太阳越升越高，颜色是**连续**变化的，不是到某个角度才跳一下。 */
    @Test
    public void theSunColourInterpolatesContinuously() {
        float[] prev = new float[3];
        GrassLightColor.transmit(-1.0f, false, prev);
        for (float alt = 0.0f; alt <= 40.0f; alt += 0.5f) {
            GrassLightColor.transmit(alt, false, out);
            for (int i = 0; i < 3; i++) {
                assertTrue("alt=" + alt + " 通道 " + i + " 跳变了："
                        + prev[i] + " -> " + out[i], Math.abs(out[i] - prev[i]) < 0.05f);
                prev[i] = out[i];
            }
        }
    }

    // ---- 阴影色 ----

    /** 贴地平线同样是原来那套冷影。 */
    @Test
    public void theHorizonKeepsTheOriginalShadow() {
        GrassLightColor.cool(0.0f, false, out);
        assertSame("地平线的阴影色", GrassConstants.GRASS_LIGHT_COOL, out);
    }

    /** 正午切到中性那组。 */
    @Test
    public void noonShadowGoesNeutral() {
        GrassLightColor.cool(60.0f, false, out);
        assertSame("正午的阴影色", GrassConstants.GRASS_LIGHT_COOL_DAY, out);
    }

    /** **月光下的阴影更深** —— 冷色光配更暗的影。 */
    @Test
    public void moonShadowIsDeeperAndCooler() {
        GrassLightColor.cool(-30.0f, true, out);
        float moonLum = 0.299f * out[0] + 0.587f * out[1] + 0.114f * out[2];
        float[] golden = GrassConstants.GRASS_LIGHT_COOL;
        float goldenLum = 0.299f * golden[0] + 0.587f * golden[1] + 0.114f * golden[2];
        assertTrue("月光的影子应当比黄金时刻更深：" + moonLum + " vs " + goldenLum,
                moonLum < goldenLum);
        assertTrue("而且偏蓝：" + out[0] + " vs " + out[2], out[2] > out[0]);
    }

    // ---- 边界 ----

    /** 月亮那一组与太阳高度角无关 —— 夜里太阳在地平线下，拿它的角度去插值没有意义。 */
    @Test
    public void moonlightIgnoresTheSunAltitude() {
        float[] a = new float[3];
        float[] b = new float[3];
        GrassLightColor.transmit(-5.0f, true, a);
        GrassLightColor.transmit(-60.0f, true, b);
        assertSame("月亮就是月亮", a, b);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUndersizedArray() {
        GrassLightColor.transmit(0.0f, false, new float[2]);
    }
}
