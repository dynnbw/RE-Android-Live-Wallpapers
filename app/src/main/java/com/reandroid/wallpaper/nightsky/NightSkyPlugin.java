package com.reandroid.wallpaper.nightsky;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class NightSkyPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "nightsky";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Night Sky";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new NightSkyEngine(context, host);
    }

    @Override
    public void release() {
    }
}
