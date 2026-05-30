package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis4Engine extends BasePluginEngine {
    public Vis4Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) { return new MusicVisVuScene(w, h, c); }
}
