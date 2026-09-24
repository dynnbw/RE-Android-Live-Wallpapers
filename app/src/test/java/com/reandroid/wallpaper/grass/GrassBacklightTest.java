package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 草叶逆光的强度模型。
 *
 * <p><b>两个量是分开的，因为它们驱动的东西不同：</b>
 * <ul>
 *   <li>{@link GrassBacklight#strength} —— 高度渐变与阴影。白天满、夜里一半、
 *       两者都不在天上就是 **恰好 0**（"夜里没月亮就没光"）</li>
 *   <li>{@link GrassBacklight#highlight} —— 透光与迎光边。**正午恰好 0**，
 *       地平线处 1，夜里月亮升起来也是 1</li>
 * </ul>
 *
 * <p>这条错了不会有任何报错：要么"特性从来看不见"，要么"大中午草地莫名其妙在发光"。
 */
public class GrassBacklightTest {

    private static final float EPS = 1.0E-5f;

    /**
     * "没有月亮"在模型里就是**一个很低的高度角**，而不是另一个布尔。
     *
     * <p>这是刻意的：光照只认高度角（自己会淡到 −12°），"月亮可见"那个布尔只管画不画月盘。
     * 早先两者共用同一个布尔，于是在 −2°（月盘消失的门限）光照被硬切掉 ——
     * 症状正是"月亮一消失，光立马没了"。见 {@link #moonLightFadesOutSmoothlyAsItSets}。
     */
    private static final float NO_MOON = -90.0f;

    private static float strength(float sunAlt, boolean moonUp, float moonAlt) {
        return GrassBacklight.strength(sunAlt, moonUp ? moonAlt : NO_MOON);
    }

    private static float highlight(float sunAlt, boolean moonUp, float moonAlt) {
        return GrassBacklight.highlight(sunAlt, moonUp ? moonAlt : NO_MOON);
    }

    // ---- 总强度：白天满、夜里一半、都没有就是 0 ----

    /** 白天（太阳在地平线上）是满的。 */
    @Test
    public void dayIsFull() {
        assertEquals("地平线上就是满的", 1.0f, strength(0.0f, false, 0.0f), EPS);
        assertEquals("正午也是满的", 1.0f, strength(60.0f, false, 0.0f), EPS);
    }

    /** 夜里靠月亮，强度取白天的一半。 */
    @Test
    public void nightWithMoonIsHalfOfDay() {
        assertEquals("地平线上的月亮", GrassBacklight.NIGHT_LEVEL,
                strength(-30.0f, true, 0.0f), EPS);
        assertEquals("高挂的月亮也是同一个上限", GrassBacklight.NIGHT_LEVEL,
                strength(-30.0f, true, 60.0f), EPS);
        assertTrue("夜里必须比白天弱", GrassBacklight.NIGHT_LEVEL < GrassBacklight.DAY_LEVEL);
    }

    /**
     * **两个光源都不在天上 → 恰好 0。**
     *
     * <p>这条就是"夜里没月亮就没光"。写成恰好 0 而不是"很小"：很小会留下一层
     * 淡淡的底光，而那正是"关了开关还有东西"的观感。
     */
    @Test
    public void noLightSourceMeansExactlyZero() {
        assertEquals("深夜无月", 0.0f, strength(-40.0f, false, 0.0f), 0.0f);
        assertEquals("天还没亮到底", 0.0f,
                strength(-GrassBacklight.FADE_DEG, false, 0.0f), 0.0f);
        assertEquals("月亮在地平线下", 0.0f,
                strength(-30.0f, true, -GrassBacklight.FADE_DEG), 0.0f);
    }

    /** 太阳升起的过程必须连续，不能有跳变 —— 昼夜是连续量在过渡。 */
    @Test
    public void strengthIsContinuousAcrossSunrise() {
        float prev = strength(-GrassBacklight.FADE_DEG - 1.0f, false, 0.0f);
        for (float alt = -GrassBacklight.FADE_DEG; alt <= 10.0f; alt += 0.25f) {
            float cur = strength(alt, false, 0.0f);
            assertTrue("alt=" + alt + " 有跳变：" + prev + " -> " + cur,
                    Math.abs(cur - prev) < 0.05f);
            prev = cur;
        }
    }

    /** 月亮升起时接手，而且是渐显不是硬切。 */
    @Test
    public void theMoonTakesOverGradually() {
        float low = strength(-30.0f, true, -GrassBacklight.FADE_DEG + 0.5f);
        float high = strength(-30.0f, true, 0.0f);
        assertTrue("月亮升起过程中应当渐强：" + low + " -> " + high, high > low);
        assertEquals("升到地平线时到顶", GrassBacklight.NIGHT_LEVEL, high, EPS);
    }

    /**
     * **月亮落下时，光照必须平滑淡出。**
     *
     * <p>这是实测 bug 的回归。月盘在高度角 −2° 就消失（那是 {@code moonVisible} 的门限），
     * 而光照要淡到 −{@code FADE_DEG}。早先光照读的是那个布尔，于是在 −2° 那一刻
     * 它正带着 0.93 的强度被硬切成 0 —— 画面上就是"月亮一消失，光立马没了"。
     *
     * <p>所以这条要**逐度走一遍**：任何一步的跳变都不许超过 0.05。
     */
    @Test
    public void moonLightFadesOutSmoothlyAsItSets() {
        // 太阳取一个远在地平线下的角度，把太阳那一项排除掉 —— 否则测的就不是月亮了。
        // （太阳在 −5° 这种暮光时刻自己还有 0.62 的强度，月亮落不落都盖得住。）
        final float night = -30.0f;
        float prev = strength(night, true, 0.0f);
        for (float moonAlt = -0.25f; moonAlt >= -25.0f; moonAlt -= 0.25f) {
            float cur = strength(night, true, moonAlt);
            assertTrue("月亮高度 " + moonAlt + "° 处跳变了：" + prev + " -> " + cur,
                    Math.abs(cur - prev) < 0.05f);
            prev = cur;
        }
        assertEquals("远在地平线下就该恰好是 0", 0.0f,
                strength(night, true, -GrassBacklight.FADE_DEG), 0.0f);
        assertTrue("−2°（月盘消失的那一刻）附近必须还在渐变，不能已经掉到 0",
                strength(night, true, -2.0f) > GrassBacklight.NIGHT_LEVEL * 0.5f);
    }

    /**
     * 暮光时刻月亮落下**不该有可见变化** —— 那时太阳项还盖得住。
     *
     * <p>顺带说明"什么时候才看得出月亮消失"：只有太阳也沉下去之后。
     */
    @Test
    public void theSunCoversTheMoonsetDuringTwilight() {
        assertEquals("暮光时月亮落不落都一样",
                strength(-5.0f, true, 0.0f), strength(-5.0f, true, NO_MOON), 1.0E-5f);
    }

    // ---- 高光门控：正午 0、地平线 1、夜里 1 ----

    /** **正午必须恰好 0** —— 太阳在头顶，没有逆光可言。这就是"白天不要高光"。 */
    @Test
    public void noHighlightAtNoon() {
        assertEquals("正午", 0.0f, highlight(45.0f, false, 0.0f), 0.0f);
        assertEquals("天顶", 0.0f, highlight(90.0f, false, 0.0f), 0.0f);
        assertEquals("开始渐隐处", 0.0f,
                highlight(GrassBacklight.FADE_DEG, false, 0.0f), 0.0f);
    }

    /** 峰值在地平线，而且是 1.0。 */
    @Test
    public void highlightPeaksAtTheHorizon() {
        assertEquals("地平线处应为峰值 1.0", 1.0f, highlight(0.0f, false, 0.0f), EPS);
        for (float alt = -GrassBacklight.FADE_DEG; alt <= GrassBacklight.FADE_DEG; alt += 0.5f) {
            assertTrue("alt=" + alt + " 超过了峰值",
                    highlight(alt, false, 0.0f) <= 1.0f + EPS);
        }
    }

    /** 峰值附近是**平的**，不是尖角 —— 太阳过地平线时不该有个可察觉的折点。 */
    @Test
    public void highlightIsFlatAtThePeak() {
        assertTrue("+1° 处太低了：" + highlight(1.0f, false, 0.0f),
                highlight(1.0f, false, 0.0f) > 0.97f);
        assertTrue("-1° 处太低了：" + highlight(-1.0f, false, 0.0f),
                highlight(-1.0f, false, 0.0f) > 0.97f);
    }

    /** 两侧单调。 */
    @Test
    public void highlightFallsOffMonotonicallyOnBothSides() {
        float prev = highlight(0.0f, false, 0.0f);
        for (float alt = 0.5f; alt <= GrassBacklight.FADE_DEG; alt += 0.5f) {
            float cur = highlight(alt, false, 0.0f);
            assertTrue("alt=" + alt + " 向上时回升了：" + prev + " -> " + cur, cur <= prev);
            prev = cur;
        }
        prev = highlight(0.0f, false, 0.0f);
        for (float alt = -0.5f; alt >= -GrassBacklight.FADE_DEG; alt -= 0.5f) {
            float cur = highlight(alt, false, 0.0f);
            assertTrue("alt=" + alt + " 向下时回升了：" + prev + " -> " + cur, cur <= prev);
            prev = cur;
        }
    }

    /** **夜里月亮在 → 高光是满的**（冷色的那套逆光）。 */
    @Test
    public void moonlightLightsTheHighlight() {
        assertEquals("月亮升到地平线上", 1.0f, highlight(-30.0f, true, 0.0f), EPS);
        assertEquals("月亮高挂", 1.0f, highlight(-40.0f, true, 50.0f), EPS);
    }

    /** 月亮升起时高光渐显，不是硬切 —— 硬切会在那一刻"啪"地跳一下。 */
    @Test
    public void highlightFadesInAsTheMoonRises() {
        float below = highlight(-30.0f, true, -GrassBacklight.FADE_DEG + 0.5f);
        float above = highlight(-30.0f, true, 0.0f);
        assertTrue("应当渐显：" + below + " -> " + above, above > below);
        assertTrue("地平线以下不该有高光", below < 0.1f);
    }

    /** 白天月亮也在天上时，高光仍按太阳算 —— 不该让月亮把正午的高光点起来。 */
    @Test
    public void aDaytimeMoonDoesNotLightTheHighlight() {
        assertEquals("正午有月亮也不该有高光", 0.0f,
                highlight(40.0f, true, 30.0f), 0.0f);
    }

    // ---- 天气压制 ----

    /** 晴天不压，雷暴几乎压没。 */
    @Test
    public void weatherSuppressesTheEffect() {
        float clear = GrassBacklight.weatherScale(WeatherCondition.D1_CLEAR);
        float storm = GrassBacklight.weatherScale(WeatherCondition.D6_THUNDERSTORMS);
        assertEquals("晴天不该被压", 1.0f, clear, EPS);
        assertTrue("雷暴里应当几乎为 0：" + storm, storm < 0.10f);
        assertTrue("雷暴应当比多云压得狠",
                storm < GrassBacklight.weatherScale(WeatherCondition.D2_CLOUDY));
    }

    /**
     * 每个档位的压制值 —— **这是一张必须逐档登记的表**。
     *
     * <p>写成表驱动而不是"检查落在 [0,1]"：后者对新加的档位是**放行**的
     * （{@code weatherScale} 的 {@code default} 返回 1.0，仍在范围内），
     * 于是"加了一个天气档位、逆光却按晴天算"会静默通过。
     *
     * <p>这里 {@code default} 直接抛，新档位一加进来测试就红，并且告诉你该做什么。
     */
    private static float expectedScale(WeatherCondition c) {
        switch (c) {
            case D1_CLEAR: return 1.00f;
            case D2_CLOUDY: return 0.70f;
            case D3_DREARY: return 0.35f;
            case D4_FOG: return 0.25f;
            case D5_RAIN_SHOWERS: return 0.12f;
            case D6_THUNDERSTORMS: return 0.05f;
            case D7_FLURRIES_SNOW: return 0.45f;
            case D8_ICE_COLD: return 0.50f;
            case D9_SLEET: return 0.15f;
            default:
                throw new AssertionError("新增了天气档位 " + c
                        + "，但没有登记它在逆光下该被压多少 —— 先定下这个值，"
                        + "再加进 GrassBacklight.weatherScale 和这张表");
        }
    }

    /** 逐档覆盖：任何一个档位没登记，{@link #expectedScale} 就会抛。 */
    @Test
    public void everyConditionIsListed() {
        for (WeatherCondition c : WeatherCondition.values()) {
            expectedScale(c);
        }
    }

    /** 表里的值与实现一致，且都在 [0,1]。 */
    @Test
    public void scalesMatchTheTable() {
        for (WeatherCondition c : WeatherCondition.values()) {
            float expected = expectedScale(c);
            float actual = GrassBacklight.weatherScale(c);
            assertTrue(c + " 越界：" + actual, actual >= 0.0f && actual <= 1.0f);
            assertEquals(c + " 的压制值", expected, actual, 1.0E-6f);
        }
    }

    // ---- 开关 ----

    /** **关掉必须恰好是 0** —— 这就是"关掉等于今天"的可测形式。 */
    @Test
    public void disabledMeansExactlyZero() {
        for (float alt = -90.0f; alt <= 90.0f; alt += 1.0f) {
            for (WeatherCondition c : WeatherCondition.values()) {
                assertEquals("关掉时 alt=" + alt + " " + c + " 仍不为 0", 0.0f,
                        GrassBacklight.effectiveStrength(false, alt, 40.0f, c), 0.0f);
            }
        }
    }

    /** 打开时 = 高度角曲线 × 天气。 */
    @Test
    public void enabledMultipliesTheTwoFactors() {
        float expected = strength(-30.0f, true, 40.0f)
                * GrassBacklight.weatherScale(WeatherCondition.D1_CLEAR);
        assertEquals(expected, GrassBacklight.effectiveStrength(
                true, -30.0f, 40.0f, WeatherCondition.D1_CLEAR), 1.0E-6f);
    }

    // ---- 光源选择 ----

    /** 白天太阳占优 → 用太阳。 */
    @Test
    public void usesTheSunWhileItIsUp() {
        float[] out = new float[2];
        boolean moon = GrassBacklight.sourceIsMoon(10.0f, 30.0f);
        GrassBacklight.lightPosition(moon, 100f, 200f, 800f, 300f, out);
        assertFalse("白天月亮不该抢走光源", moon);
        assertEquals("应当取太阳的 x", 100f, out[0], 0.0f);
        assertEquals("应当取太阳的 y", 200f, out[1], 0.0f);
    }

    /** 太阳落了、月亮可见 → 用月亮。 */
    @Test
    public void usesTheMoonAfterSunset() {
        float[] out = new float[2];
        boolean moon = GrassBacklight.sourceIsMoon(-10.0f, 20.0f);
        GrassBacklight.lightPosition(moon, 100f, 2400f, 800f, 300f, out);
        assertTrue("太阳落山后应当轮到月亮", moon);
        assertEquals("应当取月亮的 x", 800f, out[0], 0.0f);
        assertEquals("应当取月亮的 y", 300f, out[1], 0.0f);
    }

    /**
     * 两者都不在天上 → **没有光源**。
     *
     * <p>这里与改造前**相反**：原先有一条"退回太阳位置"的兜底（为了让新月那几天的黄昏
     * 仍有光晕）。现在太阳在地平线下本来就给 0，兜底反而会造出"没有光源却在发光" ——
     * 这里要的正是不该有的光源一个都不留。
     */
    @Test
    public void noSourceWhenNeitherBodyIsUp() {
        assertFalse("没有月亮就不该选月亮", GrassBacklight.sourceIsMoon(-30.0f, NO_MOON));
        assertEquals("此时强度必须恰好为 0", 0.0f,
                strength(-30.0f, false, 0.0f), 0.0f);
        assertFalse("月亮在地平线下也一样", GrassBacklight.sourceIsMoon(-30.0f, -20.0f));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUndersizedArray() {
        GrassBacklight.lightPosition(false, 1f, 2f, 3f, 4f, new float[1]);
    }
}
