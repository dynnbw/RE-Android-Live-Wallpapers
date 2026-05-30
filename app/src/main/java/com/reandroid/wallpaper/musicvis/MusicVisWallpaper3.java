package com.reandroid.wallpaper.musicvis;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class MusicVisWallpaper3 extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisWaveScene(width, height, getApplicationContext(),
                MusicVisWaveScene.Mode.FFT, "musicvis/drawable/musicvis_ice.png");
    }
}
