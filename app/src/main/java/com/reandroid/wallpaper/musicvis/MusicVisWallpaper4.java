package com.reandroid.wallpaper.musicvis;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class MusicVisWallpaper4 extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisVuScene(width, height, getApplicationContext());
    }
}
