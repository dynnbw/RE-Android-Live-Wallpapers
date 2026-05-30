package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis3Plugin implements WallpaperPlugin {
    public String getId() { return "vis3"; }
    public String getDisplayName(Context c) { return "Spectrum (vis3)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) { return new Vis3Engine(c, h); }
    public void release() {}
}
