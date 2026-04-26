package com.reandroid.wallpaper.weatherwallpapers;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;

import java.util.Calendar;

public class WeatherStateManager {
    private static final long PREVIEW_STEP_MS = 3000L;

    private static final WeatherCondition[] PREVIEW_ORDER = {
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

    private final SharedPreferences mPrefs;
    private final WeatherManager mWeatherManager;

    private WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    private boolean mIsNight = false;

    private boolean mPreviewActive = false;
    private int mPreviewIndex = 0;
    private long mPreviewNextMs = 0L;

    public WeatherStateManager(Context appContext, SharedPreferences prefs) {
        mPrefs = prefs;
        mWeatherManager = appContext != null ? new WeatherManager(appContext, this::onWeatherUpdated) : null;
    }

    public synchronized void start(boolean preview) {
        if (!preview && mWeatherManager != null) {
            mPreviewActive = false;
            mPreviewNextMs = 0L;
            mWeatherManager.start();
            return;
        }
        initPreviewCycle();
    }

    public void stop() {
        if (mWeatherManager != null) {
            mWeatherManager.stop();
        }
    }

    public synchronized void update(long timeMs, boolean preview) {
        if (!preview) {
            return;
        }
        updatePreviewCycle(timeMs);
    }

    public synchronized WeatherCondition getCondition() {
        return mCondition;
    }

    public synchronized boolean isNight() {
        return mIsNight;
    }

    public synchronized boolean shouldFastAnimate() {
        return mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET;
    }

    private synchronized void onWeatherUpdated(WeatherState state) {
        if (state == null) {
            return;
        }
        mCondition = state.condition;
        mIsNight = state.isNight;
    }

    private void initPreviewCycle() {
        mPreviewActive = true;
        mPreviewIndex = 0;
        mPreviewNextMs = 0L;
        mIsNight = computePreviewIsNight();
        mCondition = WeatherCondition.D1_CLEAR;
    }

    private void updatePreviewCycle(long timeMs) {
        if (!mPreviewActive) {
            return;
        }
        if (mPreviewNextMs == 0L) {
            mPreviewNextMs = timeMs + PREVIEW_STEP_MS;
            return;
        }
        if (timeMs < mPreviewNextMs) {
            return;
        }

        if (mPreviewIndex >= PREVIEW_ORDER.length - 1) {
            mPreviewIndex = 0;
            mPreviewActive = false;
            mCondition = PREVIEW_ORDER[0];
            return;
        }

        mPreviewIndex++;
        mCondition = PREVIEW_ORDER[mPreviewIndex];
        mPreviewNextMs = timeMs + PREVIEW_STEP_MS;
    }

    private boolean computePreviewIsNight() {
        long nowMs = System.currentTimeMillis();
        long sunriseUtc = mPrefs != null ? mPrefs.getLong("last_sunrise", 0L) : 0L;
        long sunsetUtc = mPrefs != null ? mPrefs.getLong("last_sunset", 0L) : 0L;
        if (sunriseUtc > 0L && sunsetUtc > 0L) {
            long nowSec = nowMs / 1000L;
            return nowSec < sunriseUtc || nowSec >= sunsetUtc;
        }
        Calendar calendar = Calendar.getInstance();
        int time = (calendar.get(Calendar.HOUR_OF_DAY) * 100) + calendar.get(Calendar.MINUTE);
        return time < 600 || time > 1800;
    }
}