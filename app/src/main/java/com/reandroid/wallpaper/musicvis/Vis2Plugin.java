package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class Vis2Plugin implements WallpaperPlugin {
    public String getId() { return "musicvis"; }
    public String getDisplayName(Context c) { return "Waveform (vis2)"; }
    public WallpaperEngine createEngine(Context c, WallpaperPluginHost h) {
        return new Vis2Engine(c, h);
    }
    public void release() {}
}
