package com.reandroid.wallpaper.fireworks;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Fireworks wallpaper as a plugin.
 * Wraps existing FireworksGL into the plugin interface.
 */
public class FireworksPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "fireworks";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Fireworks";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new FireworksEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
