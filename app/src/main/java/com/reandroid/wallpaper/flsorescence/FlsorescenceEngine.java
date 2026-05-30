package com.reandroid.wallpaper.flsorescence;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class FlsorescenceEngine extends BasePluginEngine {

    public FlsorescenceEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new FlsorescenceGL(width, height);
    }
}
