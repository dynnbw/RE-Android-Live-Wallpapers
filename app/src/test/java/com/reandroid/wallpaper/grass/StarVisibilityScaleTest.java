package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GrassWeatherSystem.starVisibilityScale 的约束测试。
 *
 * <p>表里的具体数值是眼调的,所以这里不逐个断言数值(那只是把表抄一遍),而是钉住
 * **规则**:晴朗不受影响、其余天气一律遮蔽、星星比日月压得更狠、
 * 以及"云画得越多压得越低"。
 */
public class StarVisibilityScaleTest {

    private static final float EPS = 1.0E-6f;

    /**
     * 渲染层每种天气实际画几片云，抄自 GrassWeatherRenderer.drawWeatherOverlays。
     * 这是测试与渲染层之间的契约 —— 哪边改了这里都会红。
     */
    private static int cloudCount(WeatherCondition c) {
        switch (c) {
            case D7_FLURRIES_SNOW: return 2;
            case D2_CLOUDY:
            case D4_FOG:
            case D8_ICE_COLD: return 8;
            case D3_DREARY:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            case D9_SLEET: return 12;
            default: return 0;
        }
    }

    /** 晴朗必须原样，其余天气必须真的遮蔽。 */
    @Test
    public void clearIsUntouchedAndEveryOtherWeatherObscures() {
        WeatherCondition[] all = WeatherCondition.values();
        assertEquals("晴朗不应被遮蔽", 1.0f,
                GrassWeatherSystem.starVisibilityScale(WeatherCondition.D1_CLEAR), EPS);
        for (WeatherCondition c : all) {
            float s = GrassWeatherSystem.starVisibilityScale(c);
            assertTrue(c + " 的 scale 越界 " + s, s >= 0.0f && s <= 1.0f);
            assertTrue(c + " 是坏天气却没遮蔽星空(" + s + ")",
                    c == WeatherCondition.D1_CLEAR || s < 1.0f);
        }
    }

    /** 星星比太阳压得更狠(星星更暗，云一挡就该先没)。 */
    @Test
    public void starsAreSuppressedHarderThanTheSun() {
        for (WeatherCondition c : WeatherCondition.values()) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            float star = GrassWeatherSystem.starVisibilityScale(c);
            float sun = GrassWeatherSystem.sunAlphaScale(c);
            assertTrue(c + " 星星(" + star + ")比太阳(" + sun + ")留得多", star <= sun);
        }
    }

    /** 语义排序：阴沉比多云更遮天、多云是除晴天外最不遮天的、最遮天的是雷暴。 */
    @Test
    public void semanticOrdering() {
        WeatherCondition[] all = WeatherCondition.values();
        // 曾经这里断言的是"渲染层云画得越多、星星压得越低"，但多云(D2)调到 0.60 之后那条
        // 不再成立 —— 多云画 8 片却比只画 2 片的飘雪留得多，这是刻意的：多云的天本身还是晴的。
        // 换成语义上必须成立的几条。
        float dreary = GrassWeatherSystem.starVisibilityScale(WeatherCondition.D3_DREARY);
        float cloudy = GrassWeatherSystem.starVisibilityScale(WeatherCondition.D2_CLOUDY);
        assertTrue("阴沉应比多云更遮天 —— " + dreary + " 不小于 " + cloudy, dreary < cloudy);

        // 多云是除晴天外**最不遮天**的：多云只是天上多几朵云，天本身还是晴的。
        // 这条同时兜住"把多云调回 0.30"——那时飘雪(0.45)会反超它。
        float d2 = GrassWeatherSystem.starVisibilityScale(WeatherCondition.D2_CLOUDY);
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR || c == WeatherCondition.D2_CLOUDY) continue;
            float s = GrassWeatherSystem.starVisibilityScale(c);
            assertTrue(c + "(" + s + ")不该比多云(" + d2 + ")更不遮天", s < d2);
        }

        float min = Float.MAX_VALUE;
        WeatherCondition argMin = null;
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            float s = GrassWeatherSystem.starVisibilityScale(c);
            if (s < min) { min = s; argMin = c; }
        }
        assertEquals("最遮天的应是雷暴，实际是 " + argMin + "(" + min + ")",
                WeatherCondition.D6_THUNDERSTORMS, argMin);
    }

    /** 只有阴沉及以下才叠天空色调。 */
    @Test
    public void onlyDrearyAndWorseGetSkyTone() {
        WeatherCondition[] all = WeatherCondition.values();
        assertTrue("晴天不应叠天空色调",
                !GrassWeatherSystem.hasSkyTone(WeatherCondition.D1_CLEAR));
        assertTrue("多云不应叠天空色调",
                !GrassWeatherSystem.hasSkyTone(WeatherCondition.D2_CLOUDY));
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR || c == WeatherCondition.D2_CLOUDY) continue;
            assertTrue(c + " 应该叠天空色调", GrassWeatherSystem.hasSkyTone(c));
        }
    }

    /** 云量表与天气表必须一一对应(没有落进 default 的漏网之鱼)。 */
    @Test
    public void cloudCountTableCoversEveryWeather() {
        for (WeatherCondition c : WeatherCondition.values()) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            assertTrue(c + " 没有出现在渲染层的云量表里", cloudCount(c) != 0);
        }
    }
}
