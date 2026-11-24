package com.reandroid.wallpaper.plant3d;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class Plant3DPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "plant3d";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new Plant3DEngine(context, host);
    }

    @Override
    public void release() {}
}
