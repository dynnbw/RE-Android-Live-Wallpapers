package com.reandroid.wallpaper.walkaround;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class WalkAroundWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new WalkAroundGL(width, height, this);
    }
}

