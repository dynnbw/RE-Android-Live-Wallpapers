package com.reandroid.wallpaper.polarclock;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Polar Clock wallpaper as a plugin.
 * Wraps existing PolarClockGL into the plugin interface.
 */
public class PolarClockPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "polarclock";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Polar Clock";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new PolarClockEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
