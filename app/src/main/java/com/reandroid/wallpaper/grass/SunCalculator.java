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

import android.location.Location;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 日出日落计算器（复刻原版 RenderScript 时间逻辑）
 */
public class SunCalculator {
    private static final String TAG = "SunCalculator";
    public static final double ZENITH_CIVIL = 96.0; // 民用晨昏（太阳在地平线下 6°）
    public static final double ZENITH_OFFICIAL = 90.83; // 官方日出日落天顶角

    private double mLatitude;
    private double mLongitude;
    private TimeZone mTimeZone;

    public SunCalculator(Location location, String timeZoneId) {
        mLatitude = location.getLatitude();
        mLongitude = location.getLongitude();
        mTimeZone = TimeZone.getTimeZone(timeZoneId);
        if (mTimeZone == null) mTimeZone = TimeZone.getDefault();
    }

    /**
     * 计算日出时间（0-24小时制）
     */
    public double computeSunriseTime(double zenith, Calendar calendar) {
        return calculateSunTime(zenith, calendar, true);
    }

    /**
     * 计算日落时间（0-24小时制）
     */
    public double computeSunsetTime(double zenith, Calendar calendar) {
        return calculateSunTime(zenith, calendar, false);
    }

    public static float timeToDayFraction(double time) {
        int hour = (int) Math.floor(time);
        int minute = (int) Math.round((time - hour) * 60);
        if (minute == 60) {
            minute = 0;
            hour++;
        }
        return (hour * 60 + minute) / 1440.0f;
    }

    /**
     * 计算太阳高度角（单位：度）
     */
    public double computeSunAltitude(Calendar calendar) {
        long millis = calendar.getTimeInMillis();

        // Julian Day
        double jd = millis / 86400000.0 + 2440587.5;
        double t = (jd - 2451545.0) / 36525.0;

        double l0 = (280.46646 + 36000.76983 * t + 0.0003032 * t * t) % 360.0;
        if (l0 < 0) l0 += 360.0;
        double m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t;
        double e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t;

        double c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(Math.toRadians(m))
                + (0.019993 - 0.000101 * t) * Math.sin(Math.toRadians(2 * m))
                + 0.000289 * Math.sin(Math.toRadians(3 * m));

        double trueLong = l0 + c;
        double omega = 125.04 - 1934.136 * t;
        double lambda = trueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));

        double epsilon0 = 23.439291 - 0.0130042 * t;
        double epsilon = epsilon0 + 0.00256 * Math.cos(Math.toRadians(omega));

        double sinDecl = Math.sin(Math.toRadians(epsilon)) * Math.sin(Math.toRadians(lambda));
        double decl = Math.toDegrees(Math.asin(sinDecl));

        double y = Math.tan(Math.toRadians(epsilon / 2.0));
        y *= y;
        double eqTime = 4.0 * Math.toDegrees(
                y * Math.sin(2.0 * Math.toRadians(l0))
                        - 2.0 * e * Math.sin(Math.toRadians(m))
                        + 4.0 * e * y * Math.sin(Math.toRadians(m)) * Math.cos(2.0 * Math.toRadians(l0))
                        - 0.5 * y * y * Math.sin(4.0 * Math.toRadians(l0))
                        - 1.25 * e * e * Math.sin(2.0 * Math.toRadians(m))
        );

        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        double localMinutes = hour * 60.0 + minute + second / 60.0;
        double tzOffsetHours = mTimeZone.getOffset(millis) / 3600000.0;

        double trueSolarTime = (localMinutes + eqTime + 4.0 * mLongitude - 60.0 * tzOffsetHours) % 1440.0;
        if (trueSolarTime < 0) trueSolarTime += 1440.0;

        double hourAngle = trueSolarTime / 4.0 - 180.0;
        if (hourAngle < -180.0) hourAngle += 360.0;

        double latRad = Math.toRadians(mLatitude);
        double declRad = Math.toRadians(decl);
        double haRad = Math.toRadians(hourAngle);

        double cosZenith = Math.sin(latRad) * Math.sin(declRad)
                + Math.cos(latRad) * Math.cos(declRad) * Math.cos(haRad);
        cosZenith = Math.max(-1.0, Math.min(1.0, cosZenith));
        double zenith = Math.toDegrees(Math.acos(cosZenith));
        return 90.0 - zenith;
    }

    /**
     * 计算太阳时角（单位：度，范围 [-180, 180]）
     */
    public double computeHourAngle(Calendar calendar) {
        long millis = calendar.getTimeInMillis();

        double jd = millis / 86400000.0 + 2440587.5;
        double t = (jd - 2451545.0) / 36525.0;

        double l0 = (280.46646 + 36000.76983 * t + 0.0003032 * t * t) % 360.0;
        if (l0 < 0) l0 += 360.0;
        double m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t;
        double e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t;

        double y = Math.tan(Math.toRadians(23.439291 / 2.0));
        y *= y;
        double eqTime = 4.0 * Math.toDegrees(
                y * Math.sin(2.0 * Math.toRadians(l0))
                        - 2.0 * e * Math.sin(Math.toRadians(m))
                        + 4.0 * e * y * Math.sin(Math.toRadians(m)) * Math.cos(2.0 * Math.toRadians(l0))
                        - 0.5 * y * y * Math.sin(4.0 * Math.toRadians(l0))
                        - 1.25 * e * e * Math.sin(2.0 * Math.toRadians(m))
        );

        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        double localMinutes = hour * 60.0 + minute + second / 60.0;
        double tzOffsetHours = mTimeZone.getOffset(millis) / 3600000.0;

        double trueSolarTime = (localMinutes + eqTime + 4.0 * mLongitude - 60.0 * tzOffsetHours) % 1440.0;
        if (trueSolarTime < 0) trueSolarTime += 1440.0;

        double hourAngle = trueSolarTime / 4.0 - 180.0;
        if (hourAngle < -180.0) hourAngle += 360.0;
        return hourAngle;
    }

    /**
     * 判断太阳是否在上升（早晨）
     */
    public boolean isSunRising(Calendar calendar) {
        Calendar prev = (Calendar) calendar.clone();
        prev.add(Calendar.MINUTE, -1);
        double hNow = computeSunAltitude(calendar);
        double hPrev = computeSunAltitude(prev);
        return hNow >= hPrev;
    }

    private double calculateSunTime(double zenith, Calendar calendar, boolean isRise) {
        calendar.setTimeZone(mTimeZone);

        double longitudeHour = getLongitudeHour(calendar, isRise);
        double meanAnomaly = getMeanAnomaly(longitudeHour);
        double sunTrueLong = getSunTrueLongitude(meanAnomaly);
        double cosineSunLocalHour = getCosineSunLocalHour(sunTrueLong, zenith);

        if (cosineSunLocalHour < -1.0 || cosineSunLocalHour > 1.0) {
            if (cosineSunLocalHour > 1.0) {
                Log.w(TAG, "Sun never rises");
                return 0.0;
            }
            Log.w(TAG, "Sun never sets");
            return 24.0;
        }

        double sunLocalHour = getSunLocalHour(cosineSunLocalHour, isRise);
        double localMeanTime = getLocalMeanTime(sunTrueLong, longitudeHour, sunLocalHour);
        return getLocalTime(localMeanTime, calendar);
    }

    private double getBaseLongitudeHour() {
        return mLongitude / 15.0;
    }

    private double getLongitudeHour(Calendar date, boolean isSunrise) {
        int offset = isSunrise ? 6 : 18;
        double dividend = offset - getBaseLongitudeHour();
        double addend = dividend / 24.0;
        return getDayOfYear(date) + addend;
    }

    private static double getMeanAnomaly(double longitudeHour) {
        return 0.9856 * longitudeHour - 3.289;
    }

    private static double getSunTrueLongitude(double meanAnomaly) {
        final double meanRadians = Math.toRadians(meanAnomaly);
        double sinMeanAnomaly = Math.sin(meanRadians);
        double sinDoubleMeanAnomaly = Math.sin(meanRadians * 2.0);

        double firstPart = meanAnomaly + sinMeanAnomaly * 1.916;
        double secondPart = sinDoubleMeanAnomaly * 0.020 + 282.634;
        double trueLongitude = firstPart + secondPart;

        if (trueLongitude > 360.0) {
            trueLongitude = trueLongitude - 360.0;
        }
        return trueLongitude;
    }

    private static double getRightAscension(double sunTrueLong) {
        double tanL = Math.tan(Math.toRadians(sunTrueLong));
        double innerParens = Math.toDegrees(tanL) * 0.91764;
        double rightAscension = Math.atan(Math.toRadians(innerParens));
        rightAscension = Math.toDegrees(rightAscension);

        if (rightAscension < 0.0) {
            rightAscension = rightAscension + 360.0;
        } else if (rightAscension > 360.0) {
            rightAscension = rightAscension - 360.0;
        }

        double ninety = 90.0;
        double longitudeQuadrant = (int) (sunTrueLong / ninety);
        longitudeQuadrant = longitudeQuadrant * ninety;

        double rightAscensionQuadrant = (int) (rightAscension / ninety);
        rightAscensionQuadrant = rightAscensionQuadrant * ninety;

        double augend = longitudeQuadrant - rightAscensionQuadrant;
        return (rightAscension + augend) / 15.0;
    }

    private double getCosineSunLocalHour(double sunTrueLong, double zenith) {
        double sinSunDeclination = getSinOfSunDeclination(sunTrueLong);
        double cosineSunDeclination = getCosineOfSunDeclination(sinSunDeclination);

        final double zenithInRads = Math.toRadians(zenith);
        final double latitude = Math.toRadians(mLatitude);

        double cosineZenith = Math.cos(zenithInRads);
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);

        double sinDeclinationTimesSinLat = sinSunDeclination * sinLatitude;
        double dividend = cosineZenith - sinDeclinationTimesSinLat;
        double divisor = cosineSunDeclination * cosLatitude;
        return dividend / divisor;
    }

    private static double getSinOfSunDeclination(double sunTrueLong) {
        double sinTrueLongitude = Math.sin(Math.toRadians(sunTrueLong));
        return sinTrueLongitude * 0.39782;
    }

    private static double getCosineOfSunDeclination(double sinSunDeclination) {
        double arcSinOfSinDeclination = Math.asin(sinSunDeclination);
        return Math.cos(arcSinOfSinDeclination);
    }

    private static double getSunLocalHour(double cosineSunLocalHour, boolean isSunrise) {
        double arcCosineOfCosineHourAngle = Math.acos(cosineSunLocalHour);
        double localHour = Math.toDegrees(arcCosineOfCosineHourAngle);
        if (isSunrise) {
            localHour = 360.0 - localHour;
        }
        return localHour / 15.0;
    }

    private static double getLocalMeanTime(double sunTrueLong, double longitudeHour, double sunLocalHour) {
        double rightAscension = getRightAscension(sunTrueLong);
        double innerParens = longitudeHour * 0.06571;
        double localMeanTime = sunLocalHour + rightAscension - innerParens;
        localMeanTime = localMeanTime - 6.622;

        if (localMeanTime < 0.0) {
            localMeanTime = localMeanTime + 24.0;
        } else if (localMeanTime > 24.0) {
            localMeanTime = localMeanTime - 24.0;
        }
        return localMeanTime;
    }

    private double getLocalTime(double localMeanTime, Calendar date) {
        double utcTime = localMeanTime - getBaseLongitudeHour();
        double utcOffsetTime = utcTime + getUTCOffset(date);
        return adjustForDst(utcOffsetTime, date);
    }

    private double adjustForDst(double localMeanTime, Calendar date) {
        double localTime = localMeanTime;
        if (mTimeZone.inDaylightTime(date.getTime())) {
            localTime++;
        }
        if (localTime > 24.0) {
            localTime = localTime - 24.0;
        }
        return localTime;
    }

    private static double getDayOfYear(Calendar date) {
        return date.get(Calendar.DAY_OF_YEAR);
    }

    private static double getUTCOffset(Calendar date) {
        int offsetInMillis = date.get(Calendar.ZONE_OFFSET);
        return offsetInMillis / 3600000.0;
    }
}