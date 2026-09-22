package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 草叶逆光的强度模型。
 *
 * <p>要守住的核心是**时间窗口**：只在清晨与黄昏出现，正午和深夜都必须恰好为 0。
 * 这条错了不会有任何报错 —— 要么"这个特性从来看不见"，要么"大中午草地莫名其妙在发光"。
 */
public class GrassBacklightTest {

    // ---- 高度角曲线 ----

    /** 正午、深夜都必须是**恰好 0**，不是"很小"。 */
    @Test
    public void offAtNoonAndAtNight() {
        assertEquals("正午不该有逆光", 0.0f, GrassBacklight.strength(45.0f), 0.0f);
        assertEquals("正午不该有逆光", 0.0f, GrassBacklight.strength(90.0f), 0.0f);
        assertEquals("深夜不该有逆光", 0.0f, GrassBacklight.strength(-40.0f), 0.0f);
        assertEquals("深夜不该有逆光", 0.0f, GrassBacklight.strength(-GrassBacklight.FADE_DEG), 0.0f);
        assertEquals("天亮到头也不该有", 0.0f, GrassBacklight.strength(GrassBacklight.FADE_DEG), 0.0f);
    }

    /** 峰值在地平线，而且是 1.0。 */
    @Test
    public void peaksAtTheHorizon() {
        assertEquals("地平线处应为峰值 1.0", 1.0f, GrassBacklight.strength(0.0f), 1.0E-5f);
        for (float alt = -GrassBacklight.FADE_DEG; alt <= GrassBacklight.FADE_DEG; alt += 0.5f) {
            assertTrue("alt=" + alt + " 超过了峰值",
                    GrassBacklight.strength(alt) <= 1.0f + 1.0E-5f);
        }
    }

    /** 两侧单调：太阳升起来越来越弱、落下去也越来越弱，没有回头。 */
    @Test
    public void fallsOffMonotonicallyOnBothSides() {
        float prev = GrassBacklight.strength(0.0f);
        for (float alt = 0.5f; alt <= GrassBacklight.FADE_DEG; alt += 0.5f) {
            float cur = GrassBacklight.strength(alt);
            assertTrue("alt=" + alt + " 向上时强度回升了：" + prev + " -> " + cur, cur <= prev);
            prev = cur;
        }
        prev = GrassBacklight.strength(0.0f);
        for (float alt = -0.5f; alt >= -GrassBacklight.FADE_DEG; alt -= 0.5f) {
            float cur = GrassBacklight.strength(alt);
            assertTrue("alt=" + alt + " 向下时强度回升了：" + prev + " -> " + cur, cur <= prev);
            prev = cur;
        }
    }

    /** 峰值附近是**平的**，不是尖角 —— 太阳过地平线时不该有个可察觉的折点。 */
    @Test
    public void peakIsFlatNotPointed() {
        // 距峰值 1° 处仍然要很接近 1（尖角的话会掉到约 0.92）
        assertTrue("+1° 处太低了：" + GrassBacklight.strength(1.0f),
                GrassBacklight.strength(1.0f) > 0.97f);
        assertTrue("-1° 处太低了：" + GrassBacklight.strength(-1.0f),
                GrassBacklight.strength(-1.0f) > 0.97f);
    }

    // ---- 天气压制 ----

    /** 晴天不压，雷暴几乎压没。 */
    @Test
    public void weatherSuppressesTheEffect() {
        float clear = GrassBacklight.weatherScale(WeatherCondition.D1_CLEAR);
        float storm = GrassBacklight.weatherScale(WeatherCondition.D6_THUNDERSTORMS);
        assertEquals("晴天不该被压", 1.0f, clear, 1.0E-5f);
        assertTrue("雷暴里应当几乎为 0：" + storm, storm < 0.10f);
        assertTrue("雷暴应当比多云压得狠", storm < GrassBacklight.weatherScale(WeatherCondition.D2_CLOUDY));
    }

    /**
     * 每个档位的压制值 —— **这是一张必须逐档登记的表**。
     *
     * <p>写成表驱动而不是"检查落在 [0,1]"：后者对新加的档位是**放行**的
     * （{@code GrassBacklight.weatherScale} 的 {@code default} 返回 1.0，仍在范围内），
     * 于是"加了一个天气档位、逆光却按晴天算"会静默通过 ——
     * 那正是"漏一个档位"的实际后果。
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
                assertEquals("关掉时 alt=" + alt + " " + c + " 仍不为 0",
                        0.0f, GrassBacklight.effectiveStrength(false, alt, c), 0.0f);
            }
        }
    }

    /** 打开时 = 高度角曲线 × 天气。 */
    @Test
    public void enabledMultipliesTheTwoFactors() {
        float expected = GrassBacklight.strength(0.0f)
                * GrassBacklight.weatherScale(WeatherCondition.D1_CLEAR);
        assertEquals(expected, GrassBacklight.effectiveStrength(
                true, 0.0f, WeatherCondition.D1_CLEAR), 1.0E-6f);
    }

    // ---- 光源选择 ----

    /** 太阳在地平线上 → 用太阳。 */
    @Test
    public void usesTheSunWhileItIsUp() {
        float[] out = new float[2];
        GrassBacklight.lightPosition(10.0f, 100f, 200f, true, 800f, 300f, out);
        assertEquals("应当取太阳的 x", 100f, out[0], 0.0f);
        assertEquals("应当取太阳的 y", 200f, out[1], 0.0f);
    }

    /** 太阳落了、月亮可见 → 用月亮（对上黄昏目标图：光晕在月亮上）。 */
    @Test
    public void usesTheMoonAfterSunset() {
        float[] out = new float[2];
        GrassBacklight.lightPosition(-5.0f, 100f, 2400f, true, 800f, 300f, out);
        assertEquals("应当取月亮的 x", 800f, out[0], 0.0f);
        assertEquals("应当取月亮的 y", 300f, out[1], 0.0f);
    }

    /**
     * **两者都不在天上 → 退回太阳位置**（新月黄昏）。
     *
     * <p>不加这条的话，新月那几天的黄昏特性会静默不出现 —— 正是"看着像没做"的一类失效。
     * 太阳的位置与高度角无关地一直在算，所以这条不花额外代价。
     */
    @Test
    public void fallsBackToTheSunWhenNoBodyIsVisible() {
        float[] out = new float[2];
        GrassBacklight.lightPosition(-5.0f, 100f, 2400f, false, 800f, 300f, out);
        assertEquals("应当退回太阳的 x", 100f, out[0], 0.0f);
        assertEquals("应当退回太阳的 y", 2400f, out[1], 0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUndersizedArray() {
        GrassBacklight.lightPosition(10.0f, 1f, 2f, false, 3f, 4f, new float[1]);
    }
}
