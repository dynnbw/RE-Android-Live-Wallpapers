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

        // ---- 3. 云画得越多，压得越低 ----
        // 判据取"同云量里最宽松的那个"随云量单调不增 —— 同一云量内部的差异是天气性质
        // (雾比多云挡得多)造成的，不参与这条排序。
        int prevCount = -1;
        float prevMax = Float.MAX_VALUE;
        for (int count : new int[]{2, 8, 12}) {
            float maxScale = -1.0f;
            StringBuilder group = new StringBuilder();
            for (WeatherCondition c : all) {
                if (cloudCount(c) != count) continue;
                float s = GrassWeatherSystem.starVisibilityScale(c);
                maxScale = Math.max(maxScale, s);
                group.append(' ').append(c).append('=').append(s);
            }
            System.out.println("云 " + count + " 片:" + group + "  最宽松=" + maxScale);
            if (maxScale > prevMax) {
                failures++;
                System.out.println("失败: 云 " + count + " 片(" + maxScale
                        + ")比云 " + prevCount + " 片(" + prevMax + ")还宽松");
            }
            prevCount = count;
            prevMax = maxScale;
        }

        // ---- 4. 每个天气都必须在这张表里有明确取值(没有落进 default 的漏网之鱼) ----
        for (WeatherCondition c : all) {
            if (cloudCount(c) == 0 && c != WeatherCondition.D1_CLEAR) {
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
}
