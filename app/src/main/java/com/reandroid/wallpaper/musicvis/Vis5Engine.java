package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis5Engine extends BasePluginEngine {
    public Vis5Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) { return new MusicVisManyGL(w, h, c); }
}
