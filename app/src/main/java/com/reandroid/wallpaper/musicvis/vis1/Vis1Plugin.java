package com.reandroid.wallpaper.musicvis.vis1;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class Vis1Plugin implements WallpaperPlugin {
    public String getId() { return "vis1"; }
    public String getDisplayName(Context c) { return "Visualizer (vis1)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) { return new Vis1Engine(c, h); }
    public void release() {}
}
