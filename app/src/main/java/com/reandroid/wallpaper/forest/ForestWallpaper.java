package com.reandroid.wallpaper.forest;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class ForestWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new ForestGL(width, height, this);
    }
}
