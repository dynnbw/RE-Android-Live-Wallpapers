package com.reandroid.wallpaper.musicvis.vis2;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.wallpaper.musicvis.MusicVisWaveGL;
import com.reandroid.wallpaper.musicvis.WaveScene;
public class Vis2Engine extends BasePluginEngine {
    public Vis2Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) {
        return new MusicVisWaveGL(w, h, c, WaveScene.Mode.PCM, "musicvis/drawable/musicvis_fire.png");
    }
}
