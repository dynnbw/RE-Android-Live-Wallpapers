package com.reandroid.wallpaper.cube;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class CubeWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new CubeGL(width, height, this);
    }
}
