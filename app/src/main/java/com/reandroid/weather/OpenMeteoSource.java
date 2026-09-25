package com.reandroid.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Open-Meteo 的数据源。**不需要密钥，且全球可用** —— 这两点正好补上另外两路的缺口：
 * 中国气象局只在大陆成立，OpenWeather 要自己申请密钥（而且国内网络连不上）。
 *
 * <p>一次请求拿齐：实时天气码、当天最低/最高温、日出日落。
 *
 * <pre>
 * GET https://api.open-meteo.com/v1/forecast
 *     ?latitude=..&amp;longitude=..&amp;current=weather_code
 *     &amp;daily=temperature_2m_max,temperature_2m_min,sunrise,sunset
 *     &amp;timezone=auto&amp;forecast_days=1
 * </pre>
 *
 * <p>{@code timezone=auto} 之后，返回里的 {@code daily.sunrise} 之类是**不带偏移的本地时刻**
 * （{@code "2026-09-25T06:01"}），要靠顶层的 {@code utc_offset_seconds} 才能换算成绝对时间。
 */
final class OpenMeteoSource implements WeatherSource {

    static final String ID = "openmeteo";

    private static final String URL_FORMAT =
            "https://api.open-meteo.com/v1/forecast"
                    + "?latitude=%.6f&longitude=%.6f"
                    + "&current=weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                    + "&timezone=auto&forecast_days=1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WeatherState fetch(double lat, double lon) throws Exception {
        String url = String.format(Locale.US, URL_FORMAT, lat, lon);
        JSONObject root = new JSONObject(WeatherHttp.get(url, null));

        JSONObject current = root.optJSONObject("current");
        if (current == null || !current.has("weather_code")) {
            throw new IOException("返回里没有 current.weather_code");
        }
        WeatherCondition condition = toCondition(current.optInt("weather_code", -1));

        JSONObject daily = root.optJSONObject("daily");
        if (daily == null) {
            throw new IOException("返回里没有 daily");
        }
        float tempMax = (float) firstOf(daily, "temperature_2m_max", 0.0);
        float tempMin = (float) firstOf(daily, "temperature_2m_min", 0.0);

        int offsetSeconds = root.optInt("utc_offset_seconds", 0);
        long sunrise = parseLocalIso(daily.optJSONArray("sunrise"), 0, offsetSeconds);
        long sunset = parseLocalIso(daily.optJSONArray("sunset"), 0, offsetSeconds);
        long now = System.currentTimeMillis() / 1000L;

        /*
         * 昼夜沿用 OpenWeather 那条判据（含两端各 30 分钟余量）。
         *
         * **两路必须用同一条判据**：用户切换数据源时，画面不该因为换了数据源就跳一下。
         * 那边多出来的"没有日出日落就按本地钟表兜底"这条分支在这里用不上 ——
         * 这个接口一定给日出日落，拿不到就是解析失败。
         */
        boolean isNight = OpenWeatherSource.resolveIsNight(now, sunrise, sunset, 0);

        return new WeatherState(condition, isNight, tempMin, tempMax, sunrise, sunset, now);
    }

    private static double firstOf(JSONObject obj, String key, double fallback) {
        JSONArray arr = obj.optJSONArray(key);
        if (arr == null || arr.length() == 0 || arr.isNull(0)) {
            return fallback;
        }
        return arr.optDouble(0, fallback);
    }

    /**
     * {@code "2026-09-25T06:01"} + 时区偏移 → epoch 秒。
     *
     * <p>纯函数，所以能在 JVM 上测。认不出格式返回 0 —— 调用方拿 0 就是"没有日出日落"。
     *
     * <p>注意返回的时刻**不带偏移**：先按 UTC 组装，再减去 {@code utcOffsetSeconds}，
     * 不能直接用本机默认时区去解（那会在跨时区时错上一整天）。
     */
    static long parseLocalIso(JSONArray times, int index, int utcOffsetSeconds) {
        if (times == null || index >= times.length() || times.isNull(index)) {
            return 0L;
        }
        return parseLocalIso(times.optString(index, ""), utcOffsetSeconds);
    }

    static long parseLocalIso(String isoLocal, int utcOffsetSeconds) {
        if (isoLocal == null || isoLocal.length() < 16) {
            return 0L;
        }
        try {
            int year = Integer.parseInt(isoLocal.substring(0, 4));
            int month = Integer.parseInt(isoLocal.substring(5, 7));
            int day = Integer.parseInt(isoLocal.substring(8, 10));
            int hour = Integer.parseInt(isoLocal.substring(11, 13));
            int minute = Integer.parseInt(isoLocal.substring(14, 16));

            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(year, month - 1, day, hour, minute, 0);
            return c.getTimeInMillis() / 1000L - utcOffsetSeconds;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * WMO 天气码 → 本应用的九档。
     *
     * <p>表是 WMO 公开的标准码（Open-Meteo 用的就是它），不是从哪份实现里抄的。
     * 认不出的码一律**多云**兜底 —— 与另一路同一个原则：宁可显示得保守，
     * 也不要因为一个没见过的码就整条数据源判失败。
     *
     * <p>56/57（冻毛毛雨）与 66/67（冻雨）归到**冻雨**而不是普通雨，这与
     * OpenWeather 那条把 511 归到 SLEET 是一致的。
     */
    static WeatherCondition toCondition(int wmoCode) {
        switch (wmoCode) {
            case 0:
                return WeatherCondition.D1_CLEAR;
            case 1:
            case 2:
                return WeatherCondition.D2_CLOUDY;
            case 3:
                return WeatherCondition.D3_DREARY;
            case 45:
            case 48:
                return WeatherCondition.D4_FOG;
            case 51:
            case 53:
            case 55:
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82:
                return WeatherCondition.D5_RAIN_SHOWERS;
            case 56:
            case 57:
            case 66:
            case 67:
                return WeatherCondition.D9_SLEET;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return WeatherCondition.D7_FLURRIES_SNOW;
            case 95:
            case 96:
            case 99:
                return WeatherCondition.D6_THUNDERSTORMS;
            default:
                return WeatherCondition.D2_CLOUDY;
        }
    }
}
