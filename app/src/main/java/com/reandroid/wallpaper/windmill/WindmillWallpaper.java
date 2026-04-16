package com.reandroid.wallpaper.windmill;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class WindmillWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new WindmillGL(width, height);
    }
}
