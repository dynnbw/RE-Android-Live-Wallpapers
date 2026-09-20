/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reandroid.wallpaper.grass;

import com.reandroid.astronomy.DayNightResolver;
import com.reandroid.astronomy.DeviceLocation;
import com.reandroid.astronomy.SunCalculator;

import java.util.Calendar;
import java.util.TimeZone;
import com.reandroid.utils.MathUtils;

final public class GrassDayNightSystem {
    private static final float SECONDS_IN_DAY = 86400.0f;

    /** 位置与时区由公用组件持有；这里只管草地的四段权重。 */
    private final DayNightResolver mResolver = new DayNightResolver();
    private final DeviceLocation mDeviceLocation = new DeviceLocation();
    private final Calendar mCachedCalendar = Calendar.getInstance();

    private float dawn;
    private float morning;
    private float afternoon;
    private float dusk;
    /**
     * 节流专用：真实时间。
     *
     * <p>传入的 {@code nowMs} 是**模拟时间**（预览下把一天压进 30 秒），天文计算该用它；
     * 但节流衡量的是 CPU 开销，必须用真实时间 —— 否则预览里 60 秒的缓存会被压缩成
     * 一帧，重的星历计算每帧都跑。
     */
    private static long realNow() {
        return System.currentTimeMillis();
    }

    private static final long WEIGHT_UPDATE_INTERVAL_MS = 60000L;
    /**
     * 预览用的权重更新间隔：0 = 每帧都算。
     *
     * <p>预览把一天压进 30 秒，任何节流都会变成肉眼可见的台阶 —— 天空色、太阳高度角
     * 都需要逐帧连续。这里不算贵：{@code computeSunriseTime/SunsetTime} 都是直线计算
     * （无循环），整个方法几十次三角函数，微秒量级。GPS 读取另有 5 分钟节流兜着。
     */
    private static final long PREVIEW_WEIGHT_UPDATE_INTERVAL_MS = 0L;

    /** 预览模式（一天压缩进 30 秒）。决定节流间隔用实机还是预览那一档。 */
    private boolean mPreview;

    void setPreview(boolean preview) {
        mPreview = preview;
    }

    private long weightUpdateIntervalMs() {
        return mPreview ? PREVIEW_WEIGHT_UPDATE_INTERVAL_MS : WEIGHT_UPDATE_INTERVAL_MS;
    }

    private long lastSunUpdateMs;

    private final float[] accurateWeights = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
    private long lastWeightUpdateMs;
    private double lastSunAltitude;
    private double lastSunriseHour = -1.0;
    private double lastSunsetHour = -1.0;
    private double lastSunriseOfficialHour = -1.0;
    private double lastSunsetOfficialHour = -1.0;

    void initDefaultLocation() {
        mResolver.applyFallbackLocation(TimeZone.getDefault());
        updateSunTimes(System.currentTimeMillis());
    }

    /**
     * 该时刻的本地昼夜进度（0=本地零点，1=次日零点）。
     *
     * <p>时刻由调用方给定 —— 预览模式传的是压缩后的时间轴（见 {@code GrassScene#sceneClockMs}），
     * 实际壁纸传真实时间。两者走的都是这一套。
     */
    float timeFraction(long nowMs) {
        mCachedCalendar.setTimeZone(mResolver.getTimeZone());
        mCachedCalendar.setTimeInMillis(nowMs);
        return (mCachedCalendar.get(Calendar.HOUR_OF_DAY) * 3600.0f
                + mCachedCalendar.get(Calendar.MINUTE) * 60.0f
                + mCachedCalendar.get(Calendar.SECOND)) / SECONDS_IN_DAY;
    }

    void updateSunTimes(long nowMs) {
        float dawnValue = 0.3f;
        float duskValue = 0.75f;

        updateLocation();
        mResolver.setTimeZone(TimeZone.getDefault());
        TimeZone timeZone = mResolver.getTimeZone();
        SunCalculator sunCalculator = mResolver.getSunCalculator();
        Calendar now = Calendar.getInstance(timeZone);
        now.setTimeInMillis(nowMs);
        double sunrise = sunCalculator.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
        double sunset = sunCalculator.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
        if (!Double.isNaN(sunrise) && !Double.isNaN(sunset)
                && sunrise > 0.0 && sunrise < 24.0
                && sunset > 0.0 && sunset < 24.0
                && sunrise < sunset) {
            float computedDawn = SunCalculator.timeToDayFraction(sunrise);
            float computedDusk = SunCalculator.timeToDayFraction(sunset);
            if (computedDusk - computedDawn >= 2.0f / 12.0f) {
                dawnValue = computedDawn;
                duskValue = computedDusk;
            }
        }

        dawn = MathUtils.clamp(dawnValue, 0.0f, 1.0f);
        dusk = MathUtils.clamp(duskValue, 0.0f, 1.0f);
        morning = dawn + 1.0f / 12.0f;
        afternoon = dusk - 1.0f / 12.0f;
        lastSunUpdateMs = realNow();
    }

    void updateAccurateWeights(long nowMs) {
        if (lastWeightUpdateMs != 0L && (realNow() - lastWeightUpdateMs) < weightUpdateIntervalMs()) {
            return;
        }

        updateLocation();
        TimeZone tz = TimeZone.getDefault();
        mResolver.setTimeZone(tz);
        Calendar now = Calendar.getInstance(tz);
        now.setTimeInMillis(nowMs);

        SunCalculator calc = mResolver.getSunCalculator();

        double sunrise = calc.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
        double sunset = calc.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
        double sunriseOfficial = calc.computeSunriseTime(SunCalculator.ZENITH_OFFICIAL, now);
        double sunsetOfficial = calc.computeSunsetTime(SunCalculator.ZENITH_OFFICIAL, now);
        lastSunriseHour = sunrise;
        lastSunsetHour = sunset;
        lastSunriseOfficialHour = sunriseOfficial;
        lastSunsetOfficialHour = sunsetOfficial;

        Calendar noon = (Calendar) now.clone();
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0);
        noon.set(Calendar.MILLISECOND, 0);

        Calendar midnight = (Calendar) now.clone();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);

        double noonAlt = calc.computeSunAltitude(noon);
        double midnightAlt = calc.computeSunAltitude(midnight);

        if (noonAlt < 0.0 && midnightAlt < 0.0) {
            setWeights(1.0f, 0.0f, 0.0f, 0.0f);
            lastWeightUpdateMs = realNow();
            lastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (noonAlt > 0.0 && midnightAlt > 0.0) {
            setWeights(0.0f, 0.0f, 0.0f, 1.0f);
            lastWeightUpdateMs = realNow();
            lastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (sunrise <= 0.0 && sunset <= 0.0) {
            setWeights(1.0f, 0.0f, 0.0f, 0.0f);
            lastWeightUpdateMs = realNow();
            lastSunAltitude = -90.0;
            return;
        }
        if (sunrise >= 24.0 && sunset >= 24.0) {
            setWeights(0.0f, 0.0f, 0.0f, 1.0f);
            lastWeightUpdateMs = realNow();
            lastSunAltitude = 90.0;
            return;
        }

        double altitude = calc.computeSunAltitude(now);
        boolean rising = calc.isSunRising(now);
        lastSunAltitude = altitude;

        float wNight = 0.0f;
        float wSunrise = 0.0f;
        float wSunset = 0.0f;
        float wSky = 0.0f;

        float nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60.0f
                + now.get(Calendar.MINUTE)
                + now.get(Calendar.SECOND) / 60.0f;
        float sunriseMin = (float) (sunrise * 60.0);
        float sunsetMin = (float) (sunset * 60.0);

        if (sunriseMin >= 0.0f && sunriseMin < 1440.0f && sunsetMin > 0.0f && sunsetMin <= 1440.0f
                && sunsetMin > sunriseMin) {
            float dawnStart = sunriseMin;
            float dawnToSunriseEnd = dawnStart + 20.0f;
            float dawnHoldEnd = dawnStart + 40.0f;
            float dawnToDayEnd = dawnStart + 60.0f;
            float duskStart = sunsetMin - 80.0f;
            float duskHoldStart = duskStart + 40.0f;
            float duskToNightStart = duskHoldStart + 20.0f;
            float duskEnd = duskToNightStart + 50.0f;

            if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, dawnStart, dawnToSunriseEnd)) {
                float t = GrassAstronomyCalculator.clockProgress(nowMinutes, dawnStart, dawnToSunriseEnd);
                wNight = 1.0f - t;
                wSunrise = t;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, dawnToSunriseEnd, dawnHoldEnd)) {
                wSunrise = 1.0f;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, dawnHoldEnd, dawnToDayEnd)) {
                float t = GrassAstronomyCalculator.clockProgress(nowMinutes, dawnHoldEnd, dawnToDayEnd);
                wSunrise = 1.0f - t;
                wSky = t;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, duskStart, duskHoldStart)) {
                float t = GrassAstronomyCalculator.clockProgress(nowMinutes, duskStart, duskHoldStart);
                wSky = 1.0f - t;
                wSunset = t;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, duskHoldStart, duskToNightStart)) {
                wSunset = 1.0f;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, duskToNightStart, duskEnd)) {
                float t = GrassAstronomyCalculator.clockProgress(nowMinutes, duskToNightStart, duskEnd);
                wSunset = 1.0f - t;
                wNight = t;
            } else if (GrassAstronomyCalculator.isBetweenClock(nowMinutes, dawnToDayEnd, duskStart)) {
                wSky = 1.0f;
            } else {
                wNight = 1.0f;
            }
        } else {
            if (rising) {
                if (altitude <= -6.0) {
                    wNight = 1.0f;
                } else if (altitude <= 0.0) {
                    wNight = 1.0f - (float) ((altitude + 6.0) / 6.0);
                    wSunrise = 1.0f - wNight;
                } else if (altitude <= 5.0) {
                    wSunrise = (float) ((5.0 - altitude) / 5.0);
                    wSky = 1.0f - wSunrise;
                } else {
                    wSky = 1.0f;
                }
            } else {
                if (altitude >= 5.0) {
                    wSky = 1.0f;
                } else if (altitude >= 0.0) {
                    wSky = (float) (altitude / 5.0);
                    wSunset = 1.0f - wSky;
                } else if (altitude >= -6.0) {
                    wSunset = (float) ((altitude + 6.0) / 6.0);
                    wNight = 1.0f - wSunset;
                } else {
                    wNight = 1.0f;
                }
            }
        }

        setWeights(wNight, wSunrise, wSunset, wSky);
        lastWeightUpdateMs = realNow();
        lastSunAltitude = altitude;
    }

    /** 位置每 5 分钟问一次系统；拿不到就沿用上一次的（首次没有则用兜底经纬度）。 */
    private void updateLocation() {
        float[] resolved = mDeviceLocation.resolve();
        if (resolved != null) {
            mResolver.setLocation(resolved[0], resolved[1]);
        }
    }

    private void setWeights(float wNight, float wSunrise, float wSunset, float wSky) {
        accurateWeights[0] = wNight;
        accurateWeights[1] = wSunrise;
        accurateWeights[2] = wSunset;
        accurateWeights[3] = wSky;
    }

    float[] getAccurateWeights() {
        return accurateWeights;
    }

    double getLastSunAltitude() {
        return lastSunAltitude;
    }

    SunCalculator getSunCalculator() {
        return mResolver.getSunCalculator();
    }

    double getLatitude() {
        return mResolver.getLatitude();
    }

    double getLongitude() {
        return mResolver.getLongitude();
    }

    TimeZone getTimeZone() {
        return mResolver.getTimeZone();
    }

    float getDawn() {
        return dawn;
    }

    float getMorning() {
        return morning;
    }

    float getAfternoon() {
        return afternoon;
    }

    float getDusk() {
        return dusk;
    }

    long getLastSunUpdateMs() {
        return lastSunUpdateMs;
    }
}
