package com.reandroid.wallpaper.windmill;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class WindmillWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new WindmillGL(width, height);
    }
}
