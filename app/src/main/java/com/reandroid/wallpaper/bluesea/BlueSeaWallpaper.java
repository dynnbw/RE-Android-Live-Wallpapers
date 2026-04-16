package com.reandroid.wallpaper.bluesea;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class BlueSeaWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new BlueSeaGL(width, height);
    }
}
