package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Windmill wallpaper as a plugin.
 * Wraps existing WindmillGL into the plugin interface.
 */
public class WindmillPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "windmill";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Windmills";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new WindmillEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
