package com.reandroid.wallpaper.nixietube;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class NixieTubePlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "nixietube";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new NixieTubeEngine(context, host);
    }

    @Override
    public void release() {}
}
