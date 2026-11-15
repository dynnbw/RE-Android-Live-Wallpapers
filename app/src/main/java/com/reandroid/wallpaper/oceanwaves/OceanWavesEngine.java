package com.reandroid.wallpaper.oceanwaves;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class OceanWavesEngine extends BasePluginEngine {

    public OceanWavesEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new OceanWavesGL(width, height, context);
    }
}
