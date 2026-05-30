package com.reandroid.wallpaper.nebula;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class NebulaEngine extends BasePluginEngine {
    public NebulaEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new NebulaGL(width, height, context);
    }
}
