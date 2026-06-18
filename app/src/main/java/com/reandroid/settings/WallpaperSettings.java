package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.preference.PreferenceManager;

import com.reandroid.gles.GLESWallpaper;

public class WallpaperSettings {
    private static volatile android.content.SharedPreferences sInjectedPrefs;

    /** Called by VK/plugin engines to inject plugin-isolated prefs. */
    public static void setSharedPreferences(android.content.SharedPreferences prefs) {
        sInjectedPrefs = prefs;
    }

    /** Called when engine is destroyed to prevent settings leakage across plugins. */
    public static void clearSharedPreferences() {
        sInjectedPrefs = null;
    }

    public static final String KEY_GLOBAL_FRAME_RATE = "global_frame_rate";
    public static final String KEY_VK_ANR_DIAGNOSTICS = "pref_vk_anr_diag";

    public static final String KEY_FALL_LEAF_COUNT = "pref_fall_leaf_count";
    public static final String KEY_FALL_GREEN_LEAVES = "pref_fall_green_leaves";
    public static final String KEY_FALL_MAX_DROPS = "pref_fall_max_drops";
    public static final String KEY_GALAXY_USE_LIGHT2 = "pref_galaxy_use_light2";
    public static final String KEY_GRASS_ENABLED = "pref_grass_enabled";
    public static final String KEY_GRASS_COUNT = "pref_grass_count";
    public static final String KEY_GRASS_HEIGHT = "pref_grass_height";
    public static final String KEY_GRASS_WIDTH = "pref_grass_width";
    public static final String KEY_GRASS_HARDNESS = "pref_grass_hardness";
    public static final String KEY_GRASS_COLOR = "pref_grass_color";
    public static final String KEY_GRASS_NIGHT_INVERT = "pref_grass_night_invert";
    public static final String KEY_GRASS_NIGHT_DESATURATE = "pref_grass_night_desaturate";
    public static final String KEY_GRASS_ACCURATE_SUN = "pref_grass_accurate_sun";
    public static final String KEY_GRASS_SUN = "pref_grass_sun";
    public static final String KEY_GRASS_MOON = "pref_grass_moon";
    public static final String KEY_GRASS_PROCEDURAL_SUN = "pref_grass_procedural_sun";
    public static final String KEY_GRASS_DANDELION = "pref_grass_dandelion";
    public static final String KEY_GRASS_FIREFLY = "pref_grass_firefly";
    public static final String KEY_GRASS_WEATHER_ENABLED = "pref_grass_weather_enabled";
    public static final String KEY_GRASS_DANDELION_COUNT = "pref_grass_dandelion_count";
    public static final String KEY_GRASS_FIREFLY_COUNT = "pref_grass_firefly_count";
    public static final String KEY_GRASS_STAR_COUNT = "pref_grass_star_count";
    public static final String KEY_GRASS_WEATHER_RAIN_COUNT = "pref_grass_weather_rain_count";
    public static final String KEY_GRASS_WEATHER_SNOW_COUNT = "pref_grass_weather_snow_count";
    public static final String KEY_GRASS_DANDELION_SPEED = "pref_grass_dandelion_speed";

    private static Context getContext() {
        return GLESWallpaper.getAppContext();
    }

    private static SharedPreferences prefs() {
        if (sInjectedPrefs != null) return sInjectedPrefs;
        Context ctx = getContext();
        if (ctx == null) return null;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static int getFallLeafCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getInt(KEY_FALL_LEAF_COUNT, defValue);
    }

    public static boolean isGreenLeavesEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_FALL_GREEN_LEAVES, defValue);
    }

    public static boolean getBoolean(String key, boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(key, defValue);
    }
    public static int getFallMaxDrops(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getInt(KEY_FALL_MAX_DROPS, defValue);
    }

    public static boolean isGalaxyLight2Enabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GALAXY_USE_LIGHT2, defValue);
    }

    public static int getGlobalFrameRate(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        try {
            String value = p.getString(KEY_GLOBAL_FRAME_RATE, String.valueOf(defValue));
            int fps = Integer.parseInt(value != null ? value : String.valueOf(defValue));
            return Math.max(1, fps);
        } catch (Exception ignored) {
            return defValue;
        }
    }

    public static boolean isVulkanAnrDiagnosticsEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_VK_ANR_DIAGNOSTICS, defValue);
    }

    public static boolean isGrassEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_ENABLED, defValue);
    }

    public static int getGrassBladeCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getInt(KEY_GRASS_COUNT, defValue);
    }

    public static float getGrassHeightScale(float defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int percent = p.getInt(KEY_GRASS_HEIGHT, Math.round(defValue * 100.0f));
        if (percent < 10) percent = 10;
        return percent / 100.0f;
    }

    public static float getGrassWidthScale(float defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int percent = p.getInt(KEY_GRASS_WIDTH, Math.round(defValue * 100.0f));
        if (percent < 10) percent = 10;
        return percent / 100.0f;
    }

    public static float getGrassHardnessScale(float defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int percent = p.getInt(KEY_GRASS_HARDNESS, Math.round(defValue * 100.0f));
        if (percent < 30) percent = 30;
        return percent / 100.0f;
    }

    public static boolean isNightInvert(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_NIGHT_INVERT, defValue);
    }

    public static boolean isGrassNightDesaturateEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_NIGHT_DESATURATE, defValue);
    }

    public static boolean isAccurateSunEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_ACCURATE_SUN, defValue);
    }

    public static boolean isSunEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_SUN, defValue);
    }

    public static boolean isProceduralSunEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_PROCEDURAL_SUN, defValue);
    }

    public static boolean isMoonEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_MOON, defValue);
    }

    public static boolean isDandelionEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_DANDELION, defValue);
    }

    public static boolean isFireflyEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_FIREFLY, defValue);
    }

    public static boolean isGrassWeatherEnabled(boolean defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getBoolean(KEY_GRASS_WEATHER_ENABLED, defValue);
    }

    public static int getDandelionCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getInt(KEY_GRASS_DANDELION_COUNT, defValue);
    }

    public static int getFireflyCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        return p.getInt(KEY_GRASS_FIREFLY_COUNT, defValue);
    }

    public static int getGrassStarCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int v = p.getInt(KEY_GRASS_STAR_COUNT, defValue);
        if (v < 0) return 0;
        return Math.min(v, 10000);
    }

    public static int getGrassWeatherRainCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int v = p.getInt(KEY_GRASS_WEATHER_RAIN_COUNT, defValue);
        if (v < 10) return 10;
        return Math.min(v, 1000);
    }

    public static int getGrassWeatherSnowCount(int defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int v = p.getInt(KEY_GRASS_WEATHER_SNOW_COUNT, defValue);
        if (v < 10) return 10;
        return Math.min(v, 1000);
    }

    public static float getDandelionSpeedScale(float defValue) {
        SharedPreferences p = prefs();
        if (p == null) return defValue;
        int percent = p.getInt(KEY_GRASS_DANDELION_SPEED, Math.round(defValue * 100.0f));
        if (percent < 100) percent = 100;
        return percent / 100.0f;
    }

    public static GrassTint getGrassTint() {
        SharedPreferences p = prefs();
        if (p == null) return new GrassTint(false, Color.WHITE);
        String value = p.getString(KEY_GRASS_COLOR, "default");
        if (value == null || "default".equals(value)) {
            return new GrassTint(false, Color.WHITE);
        }
        try {
            return new GrassTint(true, Color.parseColor(value));
        } catch (IllegalArgumentException ex) {
            return new GrassTint(false, Color.WHITE);
        }
    }

    public static class GrassTint {
        public final boolean enabled;
        public final int color;

        public GrassTint(boolean enabled, int color) {
            this.enabled = enabled;
            this.color = color;
        }
    }
}
