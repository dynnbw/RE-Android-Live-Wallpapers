package com.reandroid.weather;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OpenWeather 那三个纯函数。
 *
 * <p>它们原先都是 {@code WeatherManager} 的 private 方法，一个都测不到 ——
 * 抽数据源的时候一并搬出来，正好把它们钉住。
 *
 * <p>举的例子都取真实量级：OpenWeather 的天气码是 2xx/3xx/5xx/6xx/7xx/800/80x。
 */
public class OpenWeatherSourceTest {

    // ---- mapCondition ----

    @Test
    public void thunderstormRange() {
        assertEquals(WeatherCondition.D6_THUNDERSTORMS,
                OpenWeatherSource.mapCondition(200, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D6_THUNDERSTORMS,
                OpenWeatherSource.mapCondition(232, 20.0f, 10.0f));
    }

    @Test
    public void freezingRainIsSleetNotRain() {
        // 511 是冻雨，落在 5xx 里 —— 必须先于 3xx/5xx 那条判，否则会被当成普通雨
        assertEquals(WeatherCondition.D9_SLEET,
                OpenWeatherSource.mapCondition(511, 1.0f, -1.0f));
        // 611~616 是雨夹雪/冻雨，同样要从 6xx 的雪里挑出来
        assertEquals(WeatherCondition.D9_SLEET,
                OpenWeatherSource.mapCondition(611, 1.0f, -1.0f));
        assertEquals(WeatherCondition.D9_SLEET,
                OpenWeatherSource.mapCondition(616, 1.0f, -1.0f));
        // 617 之后是纯雪
        assertEquals(WeatherCondition.D7_FLURRIES_SNOW,
                OpenWeatherSource.mapCondition(617, -1.0f, -5.0f));
    }

    @Test
    public void drizzleRainAndSnowRanges() {
        assertEquals(WeatherCondition.D5_RAIN_SHOWERS,
                OpenWeatherSource.mapCondition(300, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D5_RAIN_SHOWERS,
                OpenWeatherSource.mapCondition(502, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D7_FLURRIES_SNOW,
                OpenWeatherSource.mapCondition(600, -1.0f, -5.0f));
        assertEquals(WeatherCondition.D4_FOG,
                OpenWeatherSource.mapCondition(741, 10.0f, 5.0f));
    }

    @Test
    public void clearIsFrozenWhenAtOrBelowZero() {
        assertEquals(WeatherCondition.D1_CLEAR,
                OpenWeatherSource.mapCondition(800, 20.0f, 10.0f));
        // 晴但结冰 → 冰冻。判据是最高温**或**最低温触到 0
        assertEquals(WeatherCondition.D8_ICE_COLD,
                OpenWeatherSource.mapCondition(800, 0.0f, -8.0f));
        assertEquals(WeatherCondition.D8_ICE_COLD,
                OpenWeatherSource.mapCondition(800, 5.0f, 0.0f));
    }

    @Test
    public void cloudTiersMapToCloudyAndDreary() {
        assertEquals(WeatherCondition.D2_CLOUDY,
                OpenWeatherSource.mapCondition(801, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D2_CLOUDY,
                OpenWeatherSource.mapCondition(802, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D3_DREARY,
                OpenWeatherSource.mapCondition(803, 20.0f, 10.0f));
        assertEquals(WeatherCondition.D3_DREARY,
                OpenWeatherSource.mapCondition(804, 20.0f, 10.0f));
    }

    @Test
    public void unknownIdFallsBackToCloudy() {
        assertEquals(WeatherCondition.D2_CLOUDY,
                OpenWeatherSource.mapCondition(999, 20.0f, 10.0f));
    }

    // ---- resolveIsNight ----

    @Test
    public void sunriseSunsetWinOverTheClock() {
        // 有日出日落就按它算。**注意两头各留 30 分钟余量**：
        // 只有 dt < 日出-30min 或 dt >= 日落+30min 才算夜
        long sunrise = 1_000_000L;
        long sunset = 1_000_000L + 12 * 3600L;
        long buffer = 30 * 60L;

        assertTrue("日出前一小时是夜里",
                OpenWeatherSource.resolveIsNight(sunrise - 3600L, sunrise, sunset, 0));
        assertFalse("日出后 1 分钟已经是白天",
                OpenWeatherSource.resolveIsNight(sunrise + 60L, sunrise, sunset, 0));
        assertFalse("日落前 1 分钟还是白天",
                OpenWeatherSource.resolveIsNight(sunset - 60L, sunrise, sunset, 0));
        // 余量的另一头：日落后半小时之内仍算白天（这一点一开始就写错过，留在这儿当备忘）
        assertFalse("日落后 1 秒还在余量里，仍算白天",
                OpenWeatherSource.resolveIsNight(sunset + 1L, sunrise, sunset, 0));
        assertFalse("日落后半小时整也还在余量里",
                OpenWeatherSource.resolveIsNight(sunset + buffer - 1L, sunrise, sunset, 0));
        assertTrue("过了余量就是夜里",
                OpenWeatherSource.resolveIsNight(sunset + buffer, sunrise, sunset, 0));
    }

    @Test
    public void withoutSunTimesFallsBackToLocalClock() {
        // 没有日出日落时按 utc + timezone 的本地分钟数判：< 5:30 或 >= 18:30 算夜
        int tz8 = 8 * 3600;
        long noonUtc = 4 * 3600L;                    // 本地 12:00
        assertEquals("本地 12:00 是白天", false,
                OpenWeatherSource.resolveIsNight(noonUtc, 0L, 0L, tz8));

        long midnightUtc = 16 * 3600L;               // 本地 24:00
        assertTrue("本地 24:00 是夜里",
                OpenWeatherSource.resolveIsNight(midnightUtc, 0L, 0L, tz8));

        long fiveUtc = 21 * 3600L;                   // 本地 05:00
        assertTrue("本地 05:00 是夜里",
                OpenWeatherSource.resolveIsNight(fiveUtc, 0L, 0L, tz8));

        long sixUtc = 22 * 3600L;                    // 本地 06:00
        assertFalse("本地 06:00 已经是白天",
                OpenWeatherSource.resolveIsNight(sixUtc, 0L, 0L, tz8));
    }

    // ---- buildLang ----

    @Test
    public void traditionalChineseGoesToZhTw() {
        assertEquals("zh_tw", OpenWeatherSource.buildLang(Locale.TAIWAN));
        assertEquals("zh_tw", OpenWeatherSource.buildLang(new Locale("zh", "HK")));
        assertEquals("zh_tw", OpenWeatherSource.buildLang(new Locale("zh", "MO")));
    }

    @Test
    public void otherChineseGoesToZhCn() {
        assertEquals("zh_cn", OpenWeatherSource.buildLang(Locale.SIMPLIFIED_CHINESE));
        assertEquals("zh_cn", OpenWeatherSource.buildLang(new Locale("zh")));
        // 新加坡的华人也是简体 —— 归到 zh_cn 是对的
        assertEquals("zh_cn", OpenWeatherSource.buildLang(new Locale("zh", "SG")));
    }

    @Test
    public void otherLanguagesGoThroughLowercased() {
        assertEquals("en", OpenWeatherSource.buildLang(Locale.US));
        assertEquals("de", OpenWeatherSource.buildLang(Locale.GERMANY));
        // 带地区的也要只留语言码，接口只认两字母
        assertEquals("pt", OpenWeatherSource.buildLang(new Locale("pt", "BR")));
    }
}
