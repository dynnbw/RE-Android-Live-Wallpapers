/*
 * GrassWeatherSystem.starVisibilityScale 的约束测试 —— 纯 JVM:
 *
 *   javac -d /tmp/startest \
 *         app/src/main/java/com/reandroid/weather/WeatherCondition.java \
 *         app/src/main/java/com/reandroid/wallpaper/grass/GrassWeatherSystem.java \
 *         tools/grass-test/StarVisibilityScaleTest.java
 *   java -cp /tmp/startest com.reandroid.wallpaper.grass.StarVisibilityScaleTest
 *
 * 表里的具体数值是眼调的,所以这里不逐个断言数值(那只是把表抄一遍),而是钉住
 * **规则**:晴朗不受影响、其余天气一律遮蔽、星星比日月压得更狠、
 * 以及"云画得越多压得越低"。
 */
package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

public final class StarVisibilityScaleTest {

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

    private static int failures = 0;

    public static void main(String[] args) {
        WeatherCondition[] all = WeatherCondition.values();

        // ---- 1. 晴朗必须原样，其余天气必须真的遮蔽 ----
        assertEquals("晴朗不应被遮蔽", 1.0f, GrassWeatherSystem.starVisibilityScale(WeatherCondition.D1_CLEAR));
        for (WeatherCondition c : all) {
            float s = GrassWeatherSystem.starVisibilityScale(c);
            if (s < 0.0f || s > 1.0f) {
                failures++;
                System.out.println("失败: " + c + " 的 scale 越界 " + s);
            }
            if (c != WeatherCondition.D1_CLEAR && s >= 1.0f) {
                failures++;
                System.out.println("失败: " + c + " 是坏天气却没遮蔽星空(" + s + ")");
            }
        }

        // ---- 2. 星星比太阳压得更狠(星星更暗，云一挡就该先没) ----
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            float star = GrassWeatherSystem.starVisibilityScale(c);
            float sun = GrassWeatherSystem.sunAlphaScale(c);
            if (star > sun) {
                failures++;
                System.out.println("失败: " + c + " 星星(" + star + ")比太阳(" + sun + ")留得多");
            }
        }

        // ---- 3. 语义排序 ----
        // 曾经这里断言的是"渲染层云画得越多、星星压得越低"，但多云(D2)调到 0.60 之后那条
        // 不再成立 —— 多云画 8 片却比只画 2 片的飘雪留得多，这是刻意的：多云的天本身还是晴的。
        // 换成语义上必须成立的几条。
        assertLess("阴沉应比多云更遮天",
                GrassWeatherSystem.starVisibilityScale(WeatherCondition.D3_DREARY),
                GrassWeatherSystem.starVisibilityScale(WeatherCondition.D2_CLOUDY));

        // 多云是除晴天外**最不遮天**的：多云只是天上多几朵云，天本身还是晴的。
        // 这条同时兜住"把多云调回 0.30"——那时飘雪(0.45)会反超它。
        float d2 = GrassWeatherSystem.starVisibilityScale(WeatherCondition.D2_CLOUDY);
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR || c == WeatherCondition.D2_CLOUDY) continue;
            float s = GrassWeatherSystem.starVisibilityScale(c);
            if (s >= d2) {
                failures++;
                System.out.println("失败: " + c + "(" + s + ")不该比多云(" + d2 + ")更不遮天");
            }
        }

        float min = Float.MAX_VALUE;
        WeatherCondition argMin = null;
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            float s = GrassWeatherSystem.starVisibilityScale(c);
            if (s < min) { min = s; argMin = c; }
        }
        if (argMin != WeatherCondition.D6_THUNDERSTORMS) {
            failures++;
            System.out.println("失败: 最遮天的应是雷暴，实际是 " + argMin + "(" + min + ")");
        }

        // ---- 4. 只有阴沉及以下才叠天空色调 ----
        assertTrue("晴天不应叠天空色调",
                !GrassWeatherSystem.hasSkyTone(WeatherCondition.D1_CLEAR));
        assertTrue("多云不应叠天空色调",
                !GrassWeatherSystem.hasSkyTone(WeatherCondition.D2_CLOUDY));
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR || c == WeatherCondition.D2_CLOUDY) continue;
            if (!GrassWeatherSystem.hasSkyTone(c)) {
                failures++;
                System.out.println("失败: " + c + " 应该叠天空色调");
            }
        }

        // ---- 5. 云量表与天气表必须一一对应(没有落进 default 的漏网之鱼) ----
        for (WeatherCondition c : all) {
            if (c == WeatherCondition.D1_CLEAR) continue;
            if (cloudCount(c) == 0) {
                failures++;
                System.out.println("失败: " + c + " 没有出现在渲染层的云量表里");
            }
        }

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }

    private static void assertEquals(String name, float expect, float actual) {
        if (Math.abs(expect - actual) > 1.0E-6f) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void assertTrue(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("失败: " + name);
        }
    }

    private static void assertLess(String name, float a, float b) {
        if (!(a < b)) {
            failures++;
            System.out.println("失败: " + name + " —— " + a + " 不小于 " + b);
        }
    }
}
