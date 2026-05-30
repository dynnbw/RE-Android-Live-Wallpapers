package com.reandroid.wallpaper.fall;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Fall wallpaper as a plugin.
 * Wraps existing FallGL/FallScene into the plugin interface.
 */
public class FallPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "fall";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Water Leaves";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new FallEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
