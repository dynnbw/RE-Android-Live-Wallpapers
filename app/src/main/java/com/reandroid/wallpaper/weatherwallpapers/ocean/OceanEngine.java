package com.reandroid.wallpaper.weatherwallpapers.ocean;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class OceanEngine extends BasePluginEngine {

    public OceanEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new OceanWeatherGL(context, width, height);
    }
}
