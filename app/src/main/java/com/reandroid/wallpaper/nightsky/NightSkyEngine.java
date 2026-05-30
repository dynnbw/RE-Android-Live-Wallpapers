package com.reandroid.wallpaper.nightsky;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class NightSkyEngine extends BasePluginEngine {
    public NightSkyEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new NightSkyGL(width, height, context);
    }
}
