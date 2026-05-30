package com.reandroid.wallpaper.nightsky;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class NightSkyWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new NightSkyGL(width, height, this);
    }
}
