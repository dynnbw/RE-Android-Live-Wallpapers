package com.reandroid.wallpaper.bluesea;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class BlueSeaEngine extends BasePluginEngine {

    public BlueSeaEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new BlueSeaGL(context, width, height);
    }
}
