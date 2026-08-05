package com.reandroid.wallpaper.musicvis.vis3;

import android.content.Context;
import com.reandroid.wallpaper.musicvis.MusicVisWaveGL;
import com.reandroid.wallpaper.musicvis.WaveScene;

/** Preview-friendly subclass for FFT (vis3) mode. */
public class WaveSceneFFT extends MusicVisWaveGL {
    public WaveSceneFFT(int width, int height, Context context) {
        super(width, height, context, WaveScene.Mode.FFT, "musicvis/drawable/musicvis_ice.png");
    }
}
