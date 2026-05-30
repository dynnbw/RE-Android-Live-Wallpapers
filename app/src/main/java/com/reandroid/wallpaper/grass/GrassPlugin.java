package com.reandroid.wallpaper.grass;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Grass wallpaper as a plugin.
 * Wraps existing GrassGL into the plugin interface.
 */
public class GrassPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "grass";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Grass";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new GrassEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
