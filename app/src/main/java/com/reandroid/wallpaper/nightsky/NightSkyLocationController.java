package com.reandroid.wallpaper.nightsky;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.List;

final class NightSkyLocationController {
    private static final float DEFAULT_LAT = 39.9042f;
    private static final float DEFAULT_LON = 116.4074f;
    private static final long LOCATION_REFRESH_INTERVAL_MS = 60L * 60L * 1000L;

    private float latitudeDeg = DEFAULT_LAT;
    private float longitudeDeg = DEFAULT_LON;
    private LocationManager locationManager;
    private long lastRefreshUptimeMs = Long.MIN_VALUE;

    float getLatitudeDeg() {
        return latitudeDeg;
    }

    float getLongitudeDeg() {
        return longitudeDeg;
    }

    void refresh(Context context, boolean force) {
        if (context == null) {
            return;
        }
        // Check debug location override first
        float[] debugLoc = com.reandroid.utils.LocationProvider.getDebugLocation();
        if (debugLoc != null) {
            latitudeDeg = debugLoc[0];
            longitudeDeg = debugLoc[1];
            return;
        }
        if (!hasLocationPermission(context)) {
            return;
        }

        long nowUptime = SystemClock.uptimeMillis();
        if (!force
                && lastRefreshUptimeMs != Long.MIN_VALUE
                && (nowUptime - lastRefreshUptimeMs) < LOCATION_REFRESH_INTERVAL_MS) {
            return;
        }

        ensureLocationManager(context);
        if (locationManager == null) {
            return;
        }

        updateFromBestLastKnown();
        lastRefreshUptimeMs = nowUptime;
    }

    void start(Context context) {
        refresh(context, true);
    }

    void stop() {
    }

    private void ensureLocationManager(Context context) {
        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }
    }

    private void updateFromBestLastKnown() {
        if (locationManager == null) {
            return;
        }

        Location best = null;
        try {
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l == null) {
                    continue;
                }
                if (best == null || l.getTime() > best.getTime()) {
                    best = l;
                }
            }
        } catch (SecurityException ignored) {
            return;
        }

        updateFromLocation(best);
    }

    private void updateFromLocation(Location location) {
        if (location == null) {
            return;
        }
        latitudeDeg = (float) location.getLatitude();
        longitudeDeg = (float) location.getLongitude();
    }

    private boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
