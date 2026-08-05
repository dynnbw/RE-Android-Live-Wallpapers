package com.reandroid.wallpaper.musicvis.vis2;

import android.content.Context;
import com.reandroid.wallpaper.musicvis.MusicVisWaveGL;
import com.reandroid.wallpaper.musicvis.WaveScene;

/** Preview-friendly subclass for PCM (vis2/musicvis) mode. */
public class WaveScenePCM extends MusicVisWaveGL {
    public WaveScenePCM(int width, int height, Context context) {
        super(width, height, context, WaveScene.Mode.PCM, "musicvis/drawable/musicvis_fire.png");
    }
}
