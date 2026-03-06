package com.reandroid.wallpaper.bluesea;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class BlueSeaWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new BlueSeaGL(width, height);
    }
}
