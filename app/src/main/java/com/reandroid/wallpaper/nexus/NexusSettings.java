package com.reandroid.wallpaper.nexus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import androidx.preference.PreferenceManager;

final class NexusSettings {
    private static final int DEFAULT_MAX_PULSES = 20;
    private static final int DEFAULT_MAX_EXTRAS = 40;
    private static final int DEFAULT_PULSE_SIZE = 14;
    private static final int DEFAULT_GLOW_SIZE = 64;
    private static final float DEFAULT_SPEED = 0.2f;
    private static final float DEFAULT_SPEED_DELTA_MIN = 0.7f;
    private static final float DEFAULT_SPEED_DELTA_MAX = 1.7f;
    private static final int DEFAULT_TRAIL_SIZE = 40;
    private static final int DEFAULT_MAX_DELAY = 2000;

    final int maxPulses;
    final int maxExtras;
    final int pulseSize;
    final int halfPulseSize;
    final int glowSize;
    final int halfGlowSize;
    final float speed;
    final float speedDeltaMin;
    final float speedDeltaMax;
    final int trailSize;
    final int maxDelay;
    final int mode;

    private NexusSettings(int maxPulses,
                          int maxExtras,
                          int pulseSize,
                          int glowSize,
                          float speed,
                          float speedDeltaMin,
                          float speedDeltaMax,
                          int trailSize,
                          int maxDelay,
                          int mode) {
        this.maxPulses = maxPulses;
        this.maxExtras = maxExtras;
        this.pulseSize = pulseSize;
        this.halfPulseSize = pulseSize / 2;
        this.glowSize = glowSize;
        this.halfGlowSize = glowSize / 2;
        this.speed = speed;
        this.speedDeltaMin = speedDeltaMin;
        this.speedDeltaMax = speedDeltaMax;
        this.trailSize = trailSize;
        this.maxDelay = maxDelay;
        this.mode = mode;
    }

    static NexusSettings load(Resources resources) {
        return load(resources, null);
    }

    static NexusSettings load(Resources resources, SharedPreferences pluginPrefs) {
        Context ctx = com.reandroid.gles.GLESWallpaper.getAppContext();
        SharedPreferences prefs = pluginPrefs != null
                ? pluginPrefs
                : (ctx != null ? PreferenceManager.getDefaultSharedPreferences(ctx) : null);

        int maxPulses = getInt(prefs, "nexus_max_pulses", DEFAULT_MAX_PULSES);
        int maxExtras = getInt(prefs, "nexus_max_extras", DEFAULT_MAX_EXTRAS);
        int pulseSize = getInt(prefs, "nexus_pulse_size", DEFAULT_PULSE_SIZE);
        int glowSize = getInt(prefs, "nexus_glow_size", DEFAULT_GLOW_SIZE);
        float speed = getInt(prefs, "nexus_speed", 20) / 100.0f;
        float speedDeltaMin = getInt(prefs, "nexus_speed_delta_min", 70) / 100.0f;
        float speedDeltaMax = getInt(prefs, "nexus_speed_delta_max", 170) / 100.0f;
        int trailSize = getInt(prefs, "nexus_trail_size", DEFAULT_TRAIL_SIZE);

        int mode = 0;
        if (prefs != null) {
            try {
                mode = Integer.parseInt(prefs.getString("nexus_mode", "0"));
            } catch (Exception e) { Log.w("NexusSettings", "Failed to parse nexus_mode", e); mode = 0; }
        }
        if (mode == 0 && resources != null) {
            try {
                mode = resources.getInteger(com.reandroid.wallpaper.R.integer.nexus_mode);
            } catch (Resources.NotFoundException ignored) {
                mode = 0;
            }
        }

        return new NexusSettings(
                maxPulses,
                maxExtras,
                pulseSize,
                glowSize,
                speed,
                speedDeltaMin,
                speedDeltaMax,
                trailSize,
                DEFAULT_MAX_DELAY,
                mode
        );
    }

    private static int getInt(SharedPreferences prefs, String key, int fallback) {
        if (prefs == null) {
            return fallback;
        }
        try {
            return prefs.getInt(key, fallback);
        } catch (Exception e) { Log.w("NexusSettings", "Failed to read int pref: " + key, e); return fallback; }
    }
}
