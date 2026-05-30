package com.reandroid.wallpaper.microbes;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class MicrobesWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MicrobesGL(width, height, this);
    }
}
