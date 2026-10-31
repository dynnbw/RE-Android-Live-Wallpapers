package com.reandroid.wallpaper.bokeh;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class BokehEngine extends BasePluginEngine {

    public BokehEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new BokehGL(width, height, context);
    }
}
