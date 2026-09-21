package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class WeatherFlagManager {
    private WeatherCondition mLastCondition = WeatherCondition.D1_CLEAR;

    private boolean mClearOn = false;
    private boolean mRainOn = false;
    private boolean mSnowOn = false;
    private boolean mThunderOn = false;

    /**
     * 四个开关**只看天气档**，与昼夜无关。
     *
     * <p>原先签名里还带着 {@code isNight}，但它只参与"要不要重算"的缓存判据，
     * 从不参与计算 —— 于是它唯一的作用是让昼夜每次翻转都白算一遍。现在昼夜是连续量，
     * 更没有理由带着它。
     */
    public void update(WeatherCondition condition) {
        if (condition != mLastCondition) {
            mClearOn = condition == WeatherCondition.D1_CLEAR;
            mRainOn = condition == WeatherCondition.D5_RAIN_SHOWERS
                    || condition == WeatherCondition.D6_THUNDERSTORMS
                    || condition == WeatherCondition.D9_SLEET;
            mSnowOn = condition == WeatherCondition.D7_FLURRIES_SNOW
                    || condition == WeatherCondition.D9_SLEET;
            mThunderOn = condition == WeatherCondition.D6_THUNDERSTORMS;
            mLastCondition = condition;
        }
    }

    public boolean isClearOn() {
        return mClearOn;
    }

    public void setClearOn(boolean clearOn) {
        mClearOn = clearOn;
    }

    public boolean isRainOn() {
        return mRainOn;
    }

    public void setRainOn(boolean rainOn) {
        mRainOn = rainOn;
    }

    public boolean isSnowOn() {
        return mSnowOn;
    }

    public void setSnowOn(boolean snowOn) {
        mSnowOn = snowOn;
    }

    public boolean isThunderOn() {
        return mThunderOn;
    }

    public void setThunderOn(boolean thunderOn) {
        mThunderOn = thunderOn;
    }
}