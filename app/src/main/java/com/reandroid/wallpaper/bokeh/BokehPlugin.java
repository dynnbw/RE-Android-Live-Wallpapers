package com.reandroid.wallpaper.bokeh;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class BokehPlugin implements WallpaperPlugin {

    @Override
    public String getId() {
        return "bokeh";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new BokehEngine(context, host);
    }

    @Override
    public void release() {}
}
