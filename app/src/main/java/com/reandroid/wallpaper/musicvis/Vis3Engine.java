package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis3Engine extends BasePluginEngine {
    public Vis3Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) {
        return new MusicVisWaveGL(w, h, c, WaveScene.Mode.FFT, "musicvis/drawable/musicvis_ice.png");
    }
}
