package com.reandroid.wallpaper.nebula;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class NebulaPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "nebula";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Nebula";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new NebulaEngine(context, host);
    }

    @Override
    public void release() {
    }
}
