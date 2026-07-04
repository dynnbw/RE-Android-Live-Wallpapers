package com.reandroid.utils;

/**
 * Centralized debug location override shared across all location-dependent wallpapers.
 * SettingsActivity writes; Grass, Windmill, Ocean, NightSky, etc. read.
 */
public final class LocationProvider {
    private static volatile float[] sDebugLatLng = null;

    private LocationProvider() {}

    /** Set debug location override. Pass (0, 0) or both 0 to clear. */
    public static void setDebugLocation(float lat, float lng) {
        if (lat == 0.0f && lng == 0.0f) {
            sDebugLatLng = null;
        } else {
            sDebugLatLng = new float[]{lat, lng};
        }
    }

    /** Returns {lat, lng} or null if no debug override is set. */
    public static float[] getDebugLocation() {
        return sDebugLatLng;
    }
}
