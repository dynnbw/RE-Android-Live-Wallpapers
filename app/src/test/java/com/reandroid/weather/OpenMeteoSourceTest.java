package com.reandroid.weather;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Open-Meteo 那一路里纯的部分：WMO 天气码映射与本地时刻换算。
 *
 * <p>JSON 反序列化那一步不在这里 —— 单测跑在 {@code android.jar} 上，那份 {@code org.json}
 * 是 stub，所以它留在设备上验（与另外两路同一个做法）。
 */
public class OpenMeteoSourceTest {

    /**
     * WMO 全表。
     *
     * <p>逐条写不是为了啰嗦：漏一个码就会静默落到兜底的"多云"，
     * 而"明明下暴雨却显示多云"在画面上看得出来、在日志里看不出来。
     *
     * <p>56/57（冻毛毛雨）与 66/67（冻雨）归**冻雨**而不是普通雨 —— 与把 OpenWeather 的
     * 511 归到 SLEET 是同一条口径。
     */
    @Test
    public void theWholeWmoTable() {
        Object[][] table = {
                { 0, WeatherCondition.D1_CLEAR },           // 晴
                { 1, WeatherCondition.D2_CLOUDY },          // 少云
                { 2, WeatherCondition.D2_CLOUDY },          // 多云
                { 3, WeatherCondition.D3_DREARY },          // 阴
                { 45, WeatherCondition.D4_FOG },            // 雾
                { 48, WeatherCondition.D4_FOG },            // 雾凇
                { 51, WeatherCondition.D5_RAIN_SHOWERS },   // 毛毛雨（轻）
                { 53, WeatherCondition.D5_RAIN_SHOWERS },   // 毛毛雨（中）
                { 55, WeatherCondition.D5_RAIN_SHOWERS },   // 毛毛雨（浓）
                { 56, WeatherCondition.D9_SLEET },          // 冻毛毛雨（轻）
                { 57, WeatherCondition.D9_SLEET },          // 冻毛毛雨（浓）
                { 61, WeatherCondition.D5_RAIN_SHOWERS },   // 小雨
                { 63, WeatherCondition.D5_RAIN_SHOWERS },   // 中雨
                { 65, WeatherCondition.D5_RAIN_SHOWERS },   // 大雨
                { 66, WeatherCondition.D9_SLEET },          // 冻雨（轻）
                { 67, WeatherCondition.D9_SLEET },          // 冻雨（强）
                { 71, WeatherCondition.D7_FLURRIES_SNOW },  // 小雪
                { 73, WeatherCondition.D7_FLURRIES_SNOW },  // 中雪
                { 75, WeatherCondition.D7_FLURRIES_SNOW },  // 大雪
                { 77, WeatherCondition.D7_FLURRIES_SNOW },  // 米雪
                { 80, WeatherCondition.D5_RAIN_SHOWERS },   // 阵雨（轻）
                { 81, WeatherCondition.D5_RAIN_SHOWERS },   // 阵雨（中）
                { 82, WeatherCondition.D5_RAIN_SHOWERS },   // 阵雨（强）
                { 85, WeatherCondition.D7_FLURRIES_SNOW },  // 阵雪（轻）
                { 86, WeatherCondition.D7_FLURRIES_SNOW },  // 阵雪（强）
                { 95, WeatherCondition.D6_THUNDERSTORMS },  // 雷暴
                { 96, WeatherCondition.D6_THUNDERSTORMS },  // 雷暴伴小冰雹
                { 99, WeatherCondition.D6_THUNDERSTORMS },  // 雷暴伴大冰雹
        };
        for (Object[] row : table) {
            int code = (Integer) row[0];
            assertEquals("WMO " + code, row[1], OpenMeteoSource.toCondition(code));
        }
    }

    @Test
    public void unknownCodeFallsBackToCloudy() {
        assertEquals(WeatherCondition.D2_CLOUDY, OpenMeteoSource.toCondition(-1));
        assertEquals(WeatherCondition.D2_CLOUDY, OpenMeteoSource.toCondition(4));
        assertEquals(WeatherCondition.D2_CLOUDY, OpenMeteoSource.toCondition(200));
    }

    /**
     * {@code "2026-09-25T06:01"} 是**本地**时刻，要减掉偏移才是绝对时间。
     *
     * <p>这台设备所在的东八区：06:01 本地 = 前一天 22:01 UTC。
     */
    @Test
    public void localIsoBecomesEpoch() {
        int utcPlus8 = 8 * 3600;
        long sunrise = OpenMeteoSource.parseLocalIso("2026-09-25T06:01", utcPlus8);
        // 2026-09-24T22:01:00Z
        assertEquals(1790287260L, sunrise);
    }

    @Test
    public void offsetIsSubtractedNotAdded() {
        long zero = OpenMeteoSource.parseLocalIso("2026-09-25T06:01", 0);
        long plus8 = OpenMeteoSource.parseLocalIso("2026-09-25T06:01", 8 * 3600);
        assertEquals("加了偏移就是方向反了", zero - 8 * 3600, plus8);
    }

    @Test
    public void westernOffsetsGoTheOtherWay() {
        long utc = OpenMeteoSource.parseLocalIso("2026-09-25T06:01", 0);
        long newYork = OpenMeteoSource.parseLocalIso("2026-09-25T06:01", -4 * 3600);
        assertEquals(utc + 4 * 3600, newYork);
    }

    @Test
    public void unparseableInputYieldsZero() {
        assertEquals(0L, OpenMeteoSource.parseLocalIso(null, 0));
        assertEquals(0L, OpenMeteoSource.parseLocalIso("", 0));
        assertEquals(0L, OpenMeteoSource.parseLocalIso("2026-09-25", 0));
        assertEquals(0L, OpenMeteoSource.parseLocalIso("not a time at all", 0));
        assertEquals(0L, OpenMeteoSource.parseLocalIso((org.json.JSONArray) null, 0, 0));
    }
}
