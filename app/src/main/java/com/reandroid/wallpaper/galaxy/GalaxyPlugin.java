package com.reandroid.wallpaper.galaxy;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GalaxyPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "galaxy";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Galaxy";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new GalaxyEngine(context, host);
    }

    @Override
    public void release() {}
}
