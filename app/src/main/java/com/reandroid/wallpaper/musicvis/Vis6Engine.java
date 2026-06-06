package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis6Engine extends BasePluginEngine {
    public Vis6Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) { return new MusicVisCircleGL(w, h, c); }
}
