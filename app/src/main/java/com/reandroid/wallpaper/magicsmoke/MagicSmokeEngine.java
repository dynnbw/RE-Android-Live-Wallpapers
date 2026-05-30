package com.reandroid.wallpaper.magicsmoke;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class MagicSmokeEngine extends BasePluginEngine {

    public MagicSmokeEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new MagicSmokeGL(context, width, height);
    }
}
