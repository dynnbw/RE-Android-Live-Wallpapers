package com.reandroid.wallpaper.aurora1;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Aurora wallpaper as a plugin.
 * Wraps existing Aurora1GL into the plugin interface.
 */
public class Aurora1Plugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "aurora1";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Aurora";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new Aurora1Engine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
