package com.reandroid.wallpaper.nebula;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class NebulaWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new NebulaGL(width, height);
    }
}