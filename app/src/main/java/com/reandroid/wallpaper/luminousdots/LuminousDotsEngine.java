package com.reandroid.wallpaper.luminousdots;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class LuminousDotsEngine extends BasePluginEngine {

    public LuminousDotsEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new LuminousDotsGL(width, height, context);
    }
}
