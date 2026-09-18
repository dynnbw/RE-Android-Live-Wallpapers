package com.reandroid.wallpaper.galaxy4;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Black Hole wallpaper as a plugin.
 * Wraps existing Galaxy4GL into the plugin interface.
 */
public class Galaxy4Plugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "galaxy4";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Black Hole";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new Galaxy4Engine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
