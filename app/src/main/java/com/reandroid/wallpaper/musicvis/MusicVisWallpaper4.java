package com.reandroid.wallpaper.musicvis;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class MusicVisWallpaper4 extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisVuScene(width, height, getApplicationContext());
    }
}
