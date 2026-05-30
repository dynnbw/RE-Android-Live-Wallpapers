package com.reandroid.wallpaper.deepsea;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Deep Sea wallpaper as a plugin.
 * Wraps existing DeepSeaGL into the plugin interface.
 */
public class DeepSeaPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "deepsea";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Deep Sea";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new DeepSeaEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
