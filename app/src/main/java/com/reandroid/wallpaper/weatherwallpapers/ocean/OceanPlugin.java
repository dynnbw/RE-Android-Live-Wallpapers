package com.reandroid.wallpaper.weatherwallpapers.ocean;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Ocean Weather wallpaper as a plugin.
 * Wraps existing OceanWeatherGL into the plugin interface.
 */
public class OceanPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "ocean";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Ocean Weather";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new OceanEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
