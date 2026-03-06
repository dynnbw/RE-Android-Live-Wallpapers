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
        // 1. 计算儒略日
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1; // 1-12
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        double n = Math.floor(275 * month / 9.0) - Math.floor((month + 9) / 12.0) * (1 + Math.floor((year - 4 * Math.floor(year / 4.0) + 2) / 3.0)) + day - 30;

        // 2. 计算太阳平黄经
        double lng = (280.46646 + n * 0.98564736232) % 360.0;
        if (lng < 0) lng += 360.0;

        // 3. 计算太阳平近点角
        double m = 357.52911 + n * 0.98560025851;

        // 4. 计算太阳黄道经度
        double lambda = lng + 1.914602 * Math.sin(Math.toRadians(m)) + 0.019993 * Math.sin(Math.toRadians(2 * m)) + 0.000289 * Math.sin(Math.toRadians(3 * m));

        // 5. 计算太阳赤纬
        double sinDec = Math.sin(Math.toRadians(lambda)) * Math.sin(Math.toRadians(23.4397));
        double cosDec = Math.cos(Math.asin(sinDec));

        // 6. 计算当地时角
        double cosH = (Math.cos(Math.toRadians(zenith)) - sinDec * Math.sin(Math.toRadians(mLatitude))) / (cosDec * Math.cos(Math.toRadians(mLatitude)));
        if (cosH > 1) {
            Log.w(TAG, "Sun never rises");
            return 0;
        } else if (cosH < -1) {
            Log.w(TAG, "Sun never sets");
            return 24;
        }

        // 7. 计算时角
        double h = Math.toDegrees(Math.acos(cosH));
        if (isRise) h = 360 - h; // 日出时角

        // 8. 转换为小时
        h = h / 15.0;

        // 9. 计算太阳时
        double ra = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(lambda)) * Math.sin(Math.toRadians(23.4397)), Math.cos(Math.toRadians(lambda))));
        ra = ra / 15.0;
        double lst = (lng / 15.0 + h - ra) % 24.0;

        // 10. 转换为当地时间
        double localTime = lst - (mLongitude / 15.0);
        // 11. 调整时区
        double utcOffset = mTimeZone.getOffset(calendar.getTimeInMillis()) / 3600000.0; // 小时（含DST）
        double localTimeZone = localTime + utcOffset;
        localTimeZone = localTimeZone % 24.0;
        if (localTimeZone < 0) {
            localTimeZone += 24.0;
        }
        return localTimeZone;
    }
}