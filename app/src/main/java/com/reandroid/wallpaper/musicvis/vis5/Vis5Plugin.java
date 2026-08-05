package com.reandroid.wallpaper.musicvis.vis5;
import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis5Plugin implements WallpaperPlugin {
    public String getId() { return "vis5"; }
    public String getDisplayName(Context c) { return "Many (vis5)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) { return new Vis5Engine(c, h); }
    public void release() {}
}
