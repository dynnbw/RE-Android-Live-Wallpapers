package com.reandroid.wallpaper.noisefield;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class NoiseFieldWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new NoiseFieldGL(width, height, this);
    }
}
