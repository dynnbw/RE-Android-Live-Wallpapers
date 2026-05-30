package com.reandroid.wallpaper.polarclock;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class PolarClockEngine extends BasePluginEngine {

    public PolarClockEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new PolarClockGL(width, height, context);
    }
}
