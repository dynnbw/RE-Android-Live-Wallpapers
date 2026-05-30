package com.reandroid.wallpaper.noisefield;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Noise Field wallpaper as a plugin.
 * Wraps existing NoiseFieldGL into the plugin interface.
 */
public class NoiseFieldPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "noisefield";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Noise Field";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new NoiseFieldEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
