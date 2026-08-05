package com.reandroid.wallpaper.musicvis.vis6;
import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis6Plugin implements WallpaperPlugin {
    public String getId() { return "vis6"; }
    public String getDisplayName(Context c) { return "Circular Spectrum (vis6)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) { return new Vis6Engine(c, h); }
    public void release() {}
}
