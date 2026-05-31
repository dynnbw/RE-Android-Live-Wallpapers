package com.reandroid.wallpaper.musicvis;

import android.content.Context;

/** Preview-friendly subclass for PCM (vis2/musicvis) mode. */
public class MusicVisWaveScenePCM extends MusicVisWaveScene {
    public MusicVisWaveScenePCM(int width, int height, Context context) {
        super(width, height, context, Mode.PCM, "musicvis/drawable/musicvis_fire.png");
    }
}
