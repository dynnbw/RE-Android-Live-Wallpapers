package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GrassWeatherSystem.weatherSpriteTint 的约束测试。
 *
 * <p>要钉住的是**两种做错的方式**:
 * <ol>
 *   <li>夜里染色接近 1 —— 等于没染，云在夜里还是白的（原版就是这样）；</li>
 *   <li>夜里染色到 0 —— 纯黑的云在天空上是个洞。</li>
 * </ol>
 *
 * <p>更早还有一个做错的方式：靠降透明度让云"消失"（夜里 cloudAlpha 乘 0.30，星空直接
 * 透出来）。那条在渲染器里，本类看不到 —— 它靠 GrassWeatherRenderer 始终以 alpha=1.0
 * 画云来保证，不是本测试的范围。
 */
public class WeatherSpriteTintTest {

    private static final float[] NIGHT = {
            GrassWeatherSystem.NIGHT_WEATHER_R,
            GrassWeatherSystem.NIGHT_WEATHER_G,
            GrassWeatherSystem.NIGHT_WEATHER_B,
    };
    private static final String[] NAMES = {"R", "G", "B"};
    private static final float EPS = 1.0E-6f;

    /** 夜里必须真的暗，但不能是纯黑。 */
    @Test
    public void nightTintIsDarkButNotBlack() {
        for (int c = 0; c < 3; c++) {
            float v = NIGHT[c];
            assertTrue(NAMES[c] + " 为 0 —— 纯黑的云在天空上是个洞", v > 0.0f);
            assertTrue(NAMES[c] + " = " + v + " 太亮，夜里云还是白的", v < 0.5f);
        }
    }

    /** 白天必须恰好是纯白（云贴图原样输出）。 */
    @Test
    public void dayTintIsExactlyWhite() {
        for (int c = 0; c < 3; c++) {
            assertEquals(NAMES[c] + " 白天应为 1", 1.0f,
                    GrassWeatherSystem.weatherSpriteTint(NIGHT[c], 1.0f), EPS);
        }
    }

    /** 深夜取夜间值。 */
    @Test
    public void deepNightUsesNightTint() {
        for (int c = 0; c < 3; c++) {
            assertEquals(NAMES[c] + " 深夜应为 " + NIGHT[c], NIGHT[c],
                    GrassWeatherSystem.weatherSpriteTint(NIGHT[c], 0.0f), EPS);
        }
    }

    /** 对白天权重单调不减，且始终落在 [夜间值, 1]。 */
    @Test
    public void tintIsMonotonicAndBounded() {
        for (int c = 0; c < 3; c++) {
            float prev = -1.0f;
            for (int i = 0; i <= 100; i++) {
                float t = i / 100.0f;
                float v = GrassWeatherSystem.weatherSpriteTint(NIGHT[c], t);
                assertTrue(NAMES[c] + " 在 dayWeight=" + t + " 处回落", v >= prev - EPS);
                assertTrue(NAMES[c] + " 越界 " + v + " @" + t,
                        v >= NIGHT[c] - EPS && v <= 1.0f + EPS);
                prev = v;
            }
        }
    }

    /** 权重越界要被夹住，不能外插。 */
    @Test
    public void outOfRangeDayWeightIsClamped() {
        assertEquals("dayWeight < 0 应夹到深夜值", 0.1f,
                GrassWeatherSystem.weatherSpriteTint(0.1f, -5.0f), EPS);
        assertEquals("dayWeight > 1 应夹到纯白", 1.0f,
                GrassWeatherSystem.weatherSpriteTint(0.1f, 5.0f), EPS);
    }
}
