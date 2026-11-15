package com.reandroid.wallpaper.dandelion;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class DandelionPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "dandelion";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new DandelionEngine(context, host);
    }

    @Override
    public void release() {}
}
