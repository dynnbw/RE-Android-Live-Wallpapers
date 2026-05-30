package com.reandroid.wallpaper.magicsmoke;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * Magic Smoke wallpaper as a plugin.
 * Wraps existing MagicSmokeGL into the plugin interface.
 */
public class MagicSmokePlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "magicsmoke";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Magic Smoke";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new MagicSmokeEngine(context, host);
    }

    @Override
    public void release() {
        // No global state to release
    }
}
