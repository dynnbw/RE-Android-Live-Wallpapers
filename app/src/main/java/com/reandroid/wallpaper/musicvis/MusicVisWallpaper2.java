package com.reandroid.wallpaper.musicvis;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.wallpaper.R;

public class MusicVisWallpaper2 extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisWaveScene(width, height, getApplicationContext(),
                MusicVisWaveScene.Mode.PCM, R.drawable.musicvis_fire);
    }
}
