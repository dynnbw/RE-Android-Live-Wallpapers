package com.reandroid.wallpaper.microbes;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class MicrobesPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "microbes";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Microbes";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new MicrobesEngine(context, host);
    }

    @Override
    public void release() {
    }
}
