package com.reandroid.wallpaper.flsorescence;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Flsorescence wallpaper as a plugin.
 * Wraps existing FlsorescenceGL into the plugin interface.
 */
public class FlsorescencePlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "flsorescence";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Flsorescence";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new FlsorescenceEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
