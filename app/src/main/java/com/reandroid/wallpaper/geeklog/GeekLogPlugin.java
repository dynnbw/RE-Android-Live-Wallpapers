package com.reandroid.wallpaper.geeklog;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class GeekLogPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "geeklog";
    }

    @Override
    public String getDisplayName(Context context) {
        return "GeekLog";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new GeekLogEngine(context, host);
    }

    @Override
    public void release() {
    }
}
