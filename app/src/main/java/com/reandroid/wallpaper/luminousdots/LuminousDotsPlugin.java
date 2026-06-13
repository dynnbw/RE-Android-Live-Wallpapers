package com.reandroid.wallpaper.luminousdots;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class LuminousDotsPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "luminousdots";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Luminous Dots";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new LuminousDotsEngine(context, host);
    }

    @Override
    public void release() {}
}
