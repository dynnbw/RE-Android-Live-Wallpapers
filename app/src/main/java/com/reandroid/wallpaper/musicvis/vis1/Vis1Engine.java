package com.reandroid.wallpaper.musicvis.vis1;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class Vis1Engine extends BasePluginEngine {
    public Vis1Engine(Context c, WallpaperPluginHost h) { super(c, h); }

    @Override
    protected GLESScene createScene(int w, int h, Context c) {
        return new MusicVisVisualizerGL(w, h, c);
    }
}
