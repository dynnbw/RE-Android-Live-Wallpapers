package com.reandroid.wallpaper.musicvis;

import android.content.Context;

/** Preview-friendly subclass for FFT (vis3) mode. */
public class MusicVisWaveSceneFFT extends MusicVisWaveScene {
    public MusicVisWaveSceneFFT(int width, int height, Context context) {
        super(width, height, context, Mode.FFT, "musicvis/drawable/musicvis_ice.png");
    }
}
