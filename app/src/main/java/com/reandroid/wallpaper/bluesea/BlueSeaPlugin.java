package com.reandroid.wallpaper.bluesea;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Blue Sea wallpaper as a plugin.
 * Wraps existing BlueSeaGL into the plugin interface.
 */
public class BlueSeaPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "bluesea";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Blue Sea";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new BlueSeaEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
