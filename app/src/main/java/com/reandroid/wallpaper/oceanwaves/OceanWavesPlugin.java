package com.reandroid.wallpaper.oceanwaves;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class OceanWavesPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "oceanwaves";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new OceanWavesEngine(context, host);
    }

    @Override
    public void release() {}
}
