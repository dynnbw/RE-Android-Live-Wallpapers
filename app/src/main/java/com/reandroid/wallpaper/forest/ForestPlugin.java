package com.reandroid.wallpaper.forest;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Forest wallpaper as a plugin.
 * Wraps existing ForestGL into the plugin interface.
 */
public class ForestPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "forest";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Forest";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new ForestEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
