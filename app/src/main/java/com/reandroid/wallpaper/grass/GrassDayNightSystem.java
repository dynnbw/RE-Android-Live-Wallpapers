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

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import androidx.core.content.ContextCompat;

import com.reandroid.gles.GLESWallpaper;

import java.util.Calendar;
import java.util.TimeZone;

final class GrassDayNightSystem {
    private static final float SECONDS_IN_DAY = 86400.0f;

    private final Location location = new Location("grass_wallpaper");
    private TimeZone timeZone = TimeZone.getDefault();
    private SunCalculator sunCalculator;

    private float dawn;
    private float morning;
    private float afternoon;
    private float dusk;
    private long lastSunUpdateMs;

    private final float[] accurateWeights = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
    private long lastWeightUpdateMs;
    private double lastSunAltitude;
    private double lastSunriseHour = -1.0;
    private double lastSunsetHour = -1.0;
    private double lastSunriseOfficialHour = -1.0;
    private double lastSunsetOfficialHour = -1.0;
    private long lastLocationUpdateMs;

    void initDefaultLocation() {
        location.setLatitude(37.7749f);
        location.setLongitude(-122.4194f);
        sunCalculator = new SunCalculator(location, timeZone.getID());
        updateSunTimes(System.currentTimeMillis());
    }

    float timeFraction(boolean isPreview, boolean useAccurateSun) {
        if (!isPreview || useAccurateSun) {
            Calendar now = Calendar.getInstance(timeZone);
            return (now.get(Calendar.HOUR_OF_DAY) * 3600.0f
                    + now.get(Calendar.MINUTE) * 60.0f
                    + now.get(Calendar.SECOND)) / SECONDS_IN_DAY;
        }
        float t = (System.currentTimeMillis() % 30000L) / 30000.0f;
        return t - (int) t;
    }

    float computeSimpleNewB(float now) {
        if (now >= 0.0f && now < dawn) return 0.0f;
        if (now >= dawn && now <= morning) {
            float half = dawn + (morning - dawn) * 0.5f;
            return now <= half ? normf(dawn, half, now) : 1.0f;
        }
        if (now > morning && now < afternoon) return 1.0f;
        if (now >= afternoon && now <= dusk) {
            float half = afternoon + (dusk - afternoon) * 0.5f;
            return now <= half ? (1.0f - normf(afternoon, half, now)) : 0.0f;
        }
        return 0.0f;
    }

    void updateSunTimes(long nowMs) {
        float dawnValue = 0.3f;
        float duskValue = 0.75f;

        updateLocationFromSystem(nowMs);
        if (sunCalculator != null) {
            timeZone = TimeZone.getDefault();
            sunCalculator = new SunCalculator(location, timeZone.getID());
            Calendar now = Calendar.getInstance(timeZone);
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
        }

        dawn = clamp(dawnValue, 0.0f, 1.0f);
        dusk = clamp(duskValue, 0.0f, 1.0f);
        morning = dawn + 1.0f / 12.0f;
        afternoon = dusk - 1.0f / 12.0f;
        lastSunUpdateMs = nowMs;
    }

    void updateAccurateWeights(long nowMs) {
        if (lastWeightUpdateMs != 0L && (nowMs - lastWeightUpdateMs) < 60000L) {
            return;
        }

        updateLocationFromSystem(nowMs);
        TimeZone tz = TimeZone.getDefault();
        Calendar now = Calendar.getInstance(tz);

        SunCalculator calc = sunCalculator;
        if (calc == null || !tz.getID().equals(timeZone.getID())) {
            calc = new SunCalculator(location, tz.getID());
            sunCalculator = calc;
            timeZone = tz;
        }

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
            lastWeightUpdateMs = nowMs;
            lastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (noonAlt > 0.0 && midnightAlt > 0.0) {
            setWeights(0.0f, 0.0f, 0.0f, 1.0f);
            lastWeightUpdateMs = nowMs;
            lastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (sunrise <= 0.0 && sunset <= 0.0) {
            setWeights(1.0f, 0.0f, 0.0f, 0.0f);
            lastWeightUpdateMs = nowMs;
            lastSunAltitude = -90.0;
            return;
        }
        if (sunrise >= 24.0 && sunset >= 24.0) {
            setWeights(0.0f, 0.0f, 0.0f, 1.0f);
            lastWeightUpdateMs = nowMs;
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
        lastWeightUpdateMs = nowMs;
        lastSunAltitude = altitude;
    }

    void updateLocationFromSystem(long nowMs) {
        if (lastLocationUpdateMs != 0L && (nowMs - lastLocationUpdateMs) < 300000L) return;

        Context ctx = GLESWallpaper.getAppContext();
        if (ctx == null) return;

        boolean hasFine = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) return;

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        Location best = null;
        try {
            Location gps = hasFine ? lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location passive = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            best = pickBestLocation(gps, net);
            best = pickBestLocation(best, passive);
        } catch (SecurityException ignored) {
        }

        if (best != null) {
            location.setLatitude(best.getLatitude());
            location.setLongitude(best.getLongitude());
            sunCalculator = new SunCalculator(location, timeZone.getID());
            lastLocationUpdateMs = nowMs;
        }
    }

    private static Location pickBestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
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
        return sunCalculator;
    }

    Location getLocation() {
        return location;
    }

    TimeZone getTimeZone() {
        return timeZone;
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

    private static float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
