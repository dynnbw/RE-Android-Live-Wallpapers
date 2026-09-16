package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class GrassWeatherIntegration {

    static final class FrameUpdate {
        final WeatherState stateToApply;
        final boolean clearSceneWeather;

        FrameUpdate(WeatherState stateToApply, boolean clearSceneWeather) {
            this.stateToApply = stateToApply;
            this.clearSceneWeather = clearSceneWeather;
        }
    }

    private WeatherManager weatherManager;
    private SharedPreferences prefs;
    private SharedPreferences mPluginPrefs;

    private final AtomicReference<WeatherState> pendingWeatherState = new AtomicReference<>();
    private final AtomicBoolean clearWeatherStatePending = new AtomicBoolean();

    private boolean weatherEnabled = true;
    private boolean weatherRunning;
    private boolean previewWeatherActive;
    private int previewWeatherIndex;
    private long previewWeatherNextMs;

    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
    }

    void onCreate(Context appContext) {
        if (appContext != null && weatherManager == null) {
            prefs = mPluginPrefs != null ? mPluginPrefs : PreferenceManager.getDefaultSharedPreferences(appContext);
            weatherManager = new WeatherManager(appContext, this::onWeatherUpdated);
        }
    }

    void start(boolean isPreview) {
        weatherEnabled = prefs != null ? prefs.getBoolean("pref_grass_weather_enabled", false) : false;
        if (!isPreview && weatherManager != null && weatherEnabled) {
            weatherManager.start();
            weatherRunning = true;
            return;
        }

        weatherRunning = false;
        clearWeatherStatePending.set(true);
        previewWeatherActive = false;
        previewWeatherIndex = 0;
        previewWeatherNextMs = 0L;

        if (isPreview && weatherEnabled) {
            initPreviewWeatherCycle();
        }
    }

    void stop() {
        if (weatherManager != null) {
            weatherManager.stop();
        }
        weatherRunning = false;
        previewWeatherActive = false;
        previewWeatherNextMs = 0L;
    }

    void release() {
        if (weatherManager != null) {
            weatherManager.release();
        }
        weatherRunning = false;
        previewWeatherActive = false;
        previewWeatherNextMs = 0L;
    }

    FrameUpdate update(long timeMs, boolean isPreview) {
        if (isPreview && weatherEnabled) {
            updatePreviewWeatherCycle(timeMs);
        }

        // Atomically consume the pending weather state (get + clear in one operation)
        WeatherState weatherState = pendingWeatherState.getAndSet(null);
        boolean enabledNow = prefs != null ? prefs.getBoolean("pref_grass_weather_enabled", false) : false;
        if (enabledNow != weatherEnabled) {
            weatherEnabled = enabledNow;
            if (weatherManager != null && !isPreview) {
                if (weatherEnabled && !weatherRunning) {
                    weatherManager.start();
                    weatherRunning = true;
                } else if (!weatherEnabled && weatherRunning) {
                    weatherManager.stop();
                    weatherRunning = false;
                }
            }

            if (!weatherEnabled) {
                clearWeatherStatePending.set(true);
                pendingWeatherState.set(null);
                weatherState = null;
                previewWeatherActive = false;
                previewWeatherNextMs = 0L;
            } else if (isPreview) {
                initPreviewWeatherCycle();
                weatherState = pendingWeatherState.getAndSet(null);
            }
        }

        // Atomically consume the clear-weather flag
        boolean shouldClear = clearWeatherStatePending.getAndSet(false);

        return new FrameUpdate(weatherState, shouldClear);
    }

    boolean isWeatherEnabled() {
        return weatherEnabled;
    }

    private void onWeatherUpdated(WeatherState state) {
        if (state == null || !weatherEnabled) {
            return;
        }
        pendingWeatherState.set(state);
    }

    private boolean computePreviewIsNight() {
        long nowMs = System.currentTimeMillis();
        long sunriseUtc = prefs != null ? prefs.getLong("last_sunrise", 0L) : 0L;
        long sunsetUtc = prefs != null ? prefs.getLong("last_sunset", 0L) : 0L;
        if (sunriseUtc > 0L && sunsetUtc > 0L) {
            long nowSec = nowMs / 1000L;
            return nowSec < sunriseUtc || nowSec >= sunsetUtc;
        }

        Calendar calendar = Calendar.getInstance();
        int time = (calendar.get(Calendar.HOUR_OF_DAY) * 100) + calendar.get(Calendar.MINUTE);
        return time < 600 || time > 1800;
    }

    private void initPreviewWeatherCycle() {
        previewWeatherActive = true;
        previewWeatherIndex = 0;
        previewWeatherNextMs = 0L;
        boolean isNight = computePreviewIsNight();
        pendingWeatherState.set(new WeatherState(WeatherCondition.D1_CLEAR, isNight,
                0.0f, 0.0f, 0L, 0L, 0L));
    }

    private void updatePreviewWeatherCycle(long timeMs) {
        if (!previewWeatherActive) {
            return;
        }
        /*
         * nextMs 为 0 表示"还没排过期"，这时要**直接落下去推进一档**，而不是只排期就返回。
         *
         * 原实现是 `nextMs = now + 3000; return;`，于是开关打开后先停在 D1_CLEAR
         * （晴天，画面与关闭时完全一样）整整 3 秒，看上去就像"天气没生效" ——
         * 这才是当年要靠重建预览来救的那个现象，其实只要让第一档立刻发生就够了。
         *
         * 已经排过期（nextMs != 0）时仍按 3 秒一档走，不要每次都抢拍。
         */
        if (previewWeatherNextMs != 0L && timeMs < previewWeatherNextMs) {
            return;
        }

        WeatherCondition[] order = {
                WeatherCondition.D1_CLEAR,
                WeatherCondition.D2_CLOUDY,
                WeatherCondition.D3_DREARY,
                WeatherCondition.D4_FOG,
                WeatherCondition.D5_RAIN_SHOWERS,
                WeatherCondition.D6_THUNDERSTORMS,
                WeatherCondition.D7_FLURRIES_SNOW,
                WeatherCondition.D8_ICE_COLD,
                WeatherCondition.D9_SLEET
        };

        boolean isNight = computePreviewIsNight();
        if (previewWeatherIndex >= order.length - 1) {
            previewWeatherIndex = 0;
            previewWeatherActive = false;
            pendingWeatherState.set(new WeatherState(order[0], isNight,
                    0.0f, 0.0f, 0L, 0L, 0L));
        } else {
            previewWeatherIndex++;
            pendingWeatherState.set(new WeatherState(order[previewWeatherIndex], isNight,
                    0.0f, 0.0f, 0L, 0L, 0L));
            previewWeatherNextMs = timeMs + 3000L;
        }
    }
}
