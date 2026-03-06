package com.reandroid.wallpaper.deepsea;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class DeepSeaWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new DeepSeaGL(width, height);
    }
}
