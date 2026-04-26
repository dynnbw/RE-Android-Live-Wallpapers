package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class WeatherFlagManager {
    private WeatherCondition mLastCondition = WeatherCondition.D1_CLEAR;
    private boolean mLastNight = false;

    private boolean mClearOn = false;
    private boolean mRainOn = false;
    private boolean mSnowOn = false;
    private boolean mThunderOn = false;

    public void update(WeatherCondition condition, boolean isNight) {
        if (condition != mLastCondition || isNight != mLastNight) {
            mClearOn = condition == WeatherCondition.D1_CLEAR;
            mRainOn = condition == WeatherCondition.D5_RAIN_SHOWERS
                    || condition == WeatherCondition.D6_THUNDERSTORMS
                    || condition == WeatherCondition.D9_SLEET;
            mSnowOn = condition == WeatherCondition.D7_FLURRIES_SNOW
                    || condition == WeatherCondition.D9_SLEET;
            mThunderOn = condition == WeatherCondition.D6_THUNDERSTORMS;
            mLastCondition = condition;
            mLastNight = isNight;
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