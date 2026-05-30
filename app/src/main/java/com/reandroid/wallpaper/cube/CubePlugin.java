package com.reandroid.wallpaper.cube;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Cube wallpaper as a plugin.
 * Wraps existing CubeGL into the plugin interface.
 */
public class CubePlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "cube";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Cube";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new CubeEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
