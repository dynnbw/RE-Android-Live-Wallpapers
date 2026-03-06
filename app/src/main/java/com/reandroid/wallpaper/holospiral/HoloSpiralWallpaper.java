package com.reandroid.wallpaper.holospiral;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class HoloSpiralWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new HoloSpiralGL(getApplicationContext(), width, height);
    }
}
