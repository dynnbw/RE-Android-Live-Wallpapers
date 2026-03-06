package com.reandroid.wallpaper.grass;

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.GLESScene;

public class GrassWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new GrassGL(width, height);
    }
}

