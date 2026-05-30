package com.reandroid.wallpaper.holospiral;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Holo Spiral wallpaper as a plugin.
 * Wraps existing HoloSpiralGL into the plugin interface.
 */
public class HoloSpiralPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "holospiral";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Holo Spiral";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new HoloSpiralEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
