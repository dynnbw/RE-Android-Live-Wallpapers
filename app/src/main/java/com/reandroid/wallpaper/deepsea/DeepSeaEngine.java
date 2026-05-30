package com.reandroid.wallpaper.deepsea;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class DeepSeaEngine extends BasePluginEngine {

    public DeepSeaEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new DeepSeaGL(width, height);
    }
}
