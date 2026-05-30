package com.reandroid.wallpaper.walkaround;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class WalkAroundEngine extends BasePluginEngine {
    public WalkAroundEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new WalkAroundGL(width, height, context);
    }
}
