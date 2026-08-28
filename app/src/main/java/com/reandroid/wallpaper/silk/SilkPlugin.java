package com.reandroid.wallpaper.silk;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Silk wallpaper ("丝语流年") as a plugin.
 * Port of the vivo CSilk engine with 3 built-in color themes
 * (Silk / Coral Sea / Coffee Time) switchable from settings.
 */
public class SilkPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "silk";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Flowing Silk";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new SilkEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
