package com.reandroid.wallpaper.musicvis;
import android.content.Context;
import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
public class Vis2Engine extends BasePluginEngine {
    public Vis2Engine(Context c, WallpaperPluginHost h) { super(c, h); }
    @Override protected GLESScene createScene(int w, int h, Context c) {
        return new MusicVisWaveScene(w, h, c, MusicVisWaveScene.Mode.PCM, "musicvis/drawable/musicvis_fire.png");
    }
}
