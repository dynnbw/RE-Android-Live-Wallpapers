package com.reandroid.wallpaper.musicvis.vis4;
import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis4Plugin implements WallpaperPlugin {
    public String getId() { return "vis4"; }
    public String getDisplayName(Context c) { return "VU Meter (vis4)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) { return new Vis4Engine(c, h); }
    public void release() {}
}
