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

    /** 预览里的天气轮播（纯逻辑，见该类说明）。 */
    private final PreviewWeatherCycle mPreviewCycle = new PreviewWeatherCycle();

    private boolean weatherEnabled = true;
    private boolean weatherRunning;

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
        mPreviewCycle.stop();

        if (isPreview && weatherEnabled) {
            initPreviewWeatherCycle();
        }
    }

    void stop() {
        if (weatherManager != null) {
            weatherManager.stop();
        }
        weatherRunning = false;
        mPreviewCycle.stop();
    }

    void release() {
        if (weatherManager != null) {
            weatherManager.release();
        }
        weatherRunning = false;
        mPreviewCycle.stop();
    }

    FrameUpdate update(long timeMs, boolean isPreview) {
        if (isPreview && weatherEnabled) {
            WeatherCondition next = mPreviewCycle.advance(timeMs);
            if (next != null) {
                pendingWeatherState.set(new WeatherState(next, computePreviewIsNight(),
                        0.0f, 0.0f, 0L, 0L, 0L));
            }
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
                mPreviewCycle.stop();
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
        mPreviewCycle.reset();
        pendingWeatherState.set(new WeatherState(mPreviewCycle.current(), computePreviewIsNight(),
                0.0f, 0.0f, 0L, 0L, 0L));
    }
}
