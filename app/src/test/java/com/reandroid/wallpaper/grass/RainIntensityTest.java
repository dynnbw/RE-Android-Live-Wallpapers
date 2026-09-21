package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 雨量强度的回归。
 *
 * <p>要守住的性质很具体：**漏掉一个档位，那种天气就一滴雨都不下**，
 * 而且不会有任何报错 —— 只是看起来"这个天气没做效果"。所以这里逐档列出，
 * 不写成集合包含判断。
 */
public class RainIntensityTest {

    @Test
    public void rainyConditionsHaveIntensity() {
        assertTrue("阵雨", GrassWeatherSystem.rainIntensity(WeatherCondition.D5_RAIN_SHOWERS) > 0.0f);
        assertTrue("雷暴", GrassWeatherSystem.rainIntensity(WeatherCondition.D6_THUNDERSTORMS) > 0.0f);
        assertTrue("雨夹雪", GrassWeatherSystem.rainIntensity(WeatherCondition.D9_SLEET) > 0.0f);
    }

    /** 逐档列出非降雨，防止以后加档位时被默认值悄悄吞掉。 */
    @Test
    public void everyOtherConditionIsDry() {
        WeatherCondition[] dry = {
                WeatherCondition.D1_CLEAR,
                WeatherCondition.D2_CLOUDY,
                WeatherCondition.D3_DREARY,
                WeatherCondition.D4_FOG,
                WeatherCondition.D7_FLURRIES_SNOW,
                WeatherCondition.D8_ICE_COLD,
        };
        for (WeatherCondition c : dry) {
            assertEquals(c + " 不该有雨量", 0.0f, GrassWeatherSystem.rainIntensity(c), 0.0f);
        }
    }

    /** 落在着色器约定的 0..5（参考实现里到处 /5.）。 */
    @Test
    public void intensityStaysWithinTheShadersZeroToFiveRange() {
        for (WeatherCondition c : WeatherCondition.values()) {
            float v = GrassWeatherSystem.rainIntensity(c);
            assertTrue(c + " 强度越界：" + v, v >= 0.0f && v <= 5.0f);
        }
    }

    /** 雷暴最大、阵雨其次、雨夹雪最小 —— 与 sunAlphaScale 的档位排序一致。 */
    @Test
    public void thunderstormsAreHeaviest() {
        float t = GrassWeatherSystem.rainIntensity(WeatherCondition.D6_THUNDERSTORMS);
        float s = GrassWeatherSystem.rainIntensity(WeatherCondition.D5_RAIN_SHOWERS);
        float l = GrassWeatherSystem.rainIntensity(WeatherCondition.D9_SLEET);
        assertTrue("雷暴应大于阵雨", t > s);
        assertTrue("阵雨应大于雨夹雪", s > l);
        assertTrue("雨夹雪应大于 0", l > 0.0f);
    }
}
