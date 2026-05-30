package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class WindmillEngine extends BasePluginEngine {

    public WindmillEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new WindmillGL(width, height);
    }
}
