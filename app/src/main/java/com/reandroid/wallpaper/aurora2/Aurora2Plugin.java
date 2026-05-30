package com.reandroid.wallpaper.aurora2;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Aurora 2 wallpaper as a plugin.
 * Wraps existing Aurora2GL into the plugin interface.
 */
public class Aurora2Plugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "aurora2";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Aurora 2";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new Aurora2Engine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
