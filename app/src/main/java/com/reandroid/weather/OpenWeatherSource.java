package com.reandroid.weather;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * OpenWeather 的数据源。
 *
 * <p>这一整块原先长在 {@link WeatherManager} 里，而且 {@code mapCondition} /
 * {@code resolveIsNight} / {@code buildLang} 都是 private —— 一个都测不到。
 * 搬出来之后纯的部分是**包内 static**，可以在 JVM 上直接测（见 {@code OpenWeatherSourceTest}）。
 */
final class OpenWeatherSource implements WeatherSource {

    static final String ID = "openweather";

    private static final String TAG = "OpenWeatherSource";
    private static final String URL_FORMAT =
            "https://api.openweathermap.org/data/2.5/weather"
                    + "?lat=%.6f&lon=%.6f&appid=%s&units=metric&lang=%s&mode=json";

    private final String mApiKey;
    private final Locale mLocale;

    OpenWeatherSource(String apiKey, Locale locale) {
        mApiKey = apiKey;
        mLocale = locale;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public WeatherState fetch(double lat, double lon) throws Exception {
        if (mApiKey == null || mApiKey.trim().isEmpty()) {
            // 没填密钥就不该发这个请求。原先这里也是直接不取，只是没抛出 ——
            // 于是"没配"和"取到了同样的旧值"在外面长得一模一样
            throw new IOException("没有 OpenWeather API 密钥");
        }
        String url = String.format(Locale.US, URL_FORMAT, lat, lon, mApiKey, buildLang(mLocale));
        return parse(WeatherHttp.get(url, null));
    }

    private WeatherState parse(String json) throws JSONException, IOException {
        JSONObject root = new JSONObject(json);
        if (!isSuccessResponse(root)) {
            // 正文里的 message 是这里唯一有用的信息（"Invalid API key" 之类），别丢
            throw new IOException("OpenWeather: " + root.optString("message", "unknown"));
        }
        JSONArray weatherArr = root.optJSONArray("weather");
        int weatherId = 800;
        if (weatherArr != null && weatherArr.length() > 0) {
            weatherId = weatherArr.getJSONObject(0).optInt("id", 800);
        }

        JSONObject main = root.optJSONObject("main");
        float tempMin = main != null ? (float) main.optDouble("temp_min", 0.0) : 0.0f;
        float tempMax = main != null ? (float) main.optDouble("temp_max", 0.0) : 0.0f;

        JSONObject sys = root.optJSONObject("sys");
        long sunrise = sys != null ? sys.optLong("sunrise", 0L) : 0L;
        long sunset = sys != null ? sys.optLong("sunset", 0L) : 0L;
        long dt = root.optLong("dt", System.currentTimeMillis() / 1000L);
        int timezone = root.optInt("timezone", 0);

        boolean isNight = resolveIsNight(dt, sunrise, sunset, timezone);
        return new WeatherState(mapCondition(weatherId, tempMax, tempMin), isNight,
                tempMin, tempMax, sunrise, sunset, dt);
    }

    private static boolean isSuccessResponse(JSONObject root) {
        if (!root.has("cod")) {
            return true;
        }
        try {
            return root.optInt("cod", 200) == 200;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse weather response code as int, falling back to string", e);
            return "200".equals(root.optString("cod", "200"));
        }
    }

    /** 有日出日落就用它判昼夜（留 30 分钟余量），否则退回本地时钟的 6:00/18:00。 */
    static boolean resolveIsNight(long dtUtc, long sunriseUtc, long sunsetUtc, int timezoneSeconds) {
        long buffer = 30 * 60L;
        if (sunriseUtc > 0 && sunsetUtc > 0) {
            return dtUtc < (sunriseUtc - buffer) || dtUtc >= (sunsetUtc + buffer);
        }

        long localSeconds = dtUtc + timezoneSeconds;
        if (localSeconds <= 0) {
            localSeconds = System.currentTimeMillis() / 1000L;
        }
        int localMinutes = (int) ((localSeconds % 86400L) / 60L);
        return localMinutes < (6 * 60 - 30) || localMinutes >= (18 * 60 + 30);
    }

    /**
     * OpenWeather 的天气码 → 本应用的九档。
     *
     * <p>{@code tempMax/tempMin} 只用于"晴/多云但结冰"这一种情形 —— 那读作冰冻而不是晴天。
     */
    static WeatherCondition mapCondition(int weatherId, float tempMax, float tempMin) {
        if (weatherId >= 200 && weatherId < 300) {
            return WeatherCondition.D6_THUNDERSTORMS;
        }
        if (weatherId == 511 || (weatherId >= 611 && weatherId <= 616)) {
            return WeatherCondition.D9_SLEET;
        }
        if (weatherId >= 300 && weatherId < 600) {
            return WeatherCondition.D5_RAIN_SHOWERS;
        }
        if (weatherId >= 600 && weatherId < 700) {
            return WeatherCondition.D7_FLURRIES_SNOW;
        }
        if (weatherId >= 700 && weatherId < 800) {
            return WeatherCondition.D4_FOG;
        }
        if (weatherId == 800) {
            return isFreezing(tempMax, tempMin) ? WeatherCondition.D8_ICE_COLD : WeatherCondition.D1_CLEAR;
        }
        if (weatherId == 801 || weatherId == 802) {
            return isFreezing(tempMax, tempMin) ? WeatherCondition.D8_ICE_COLD : WeatherCondition.D2_CLOUDY;
        }
        if (weatherId == 803 || weatherId == 804) {
            return isFreezing(tempMax, tempMin) ? WeatherCondition.D8_ICE_COLD : WeatherCondition.D3_DREARY;
        }
        return WeatherCondition.D2_CLOUDY;
    }

    private static boolean isFreezing(float tempMax, float tempMin) {
        return tempMax <= 0.0f || tempMin <= 0.0f;
    }

    /** 接口的 {@code lang} 参数：繁体中文走 {@code zh_tw}，其余中文一律 {@code zh_cn}。 */
    static String buildLang(Locale locale) {
        if (locale == null) {
            return "en";
        }
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if ("zh".equalsIgnoreCase(language)) {
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
                    || "MO".equalsIgnoreCase(country)) {
                return "zh_tw";
            }
            return "zh_cn";
        }
        return language.toLowerCase(Locale.US);
    }
}
