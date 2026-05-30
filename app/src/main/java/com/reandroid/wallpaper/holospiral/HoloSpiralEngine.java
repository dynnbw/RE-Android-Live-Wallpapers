package com.reandroid.wallpaper.holospiral;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class HoloSpiralEngine extends BasePluginEngine {

    public HoloSpiralEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new HoloSpiralGL(context, width, height);
    }
}
