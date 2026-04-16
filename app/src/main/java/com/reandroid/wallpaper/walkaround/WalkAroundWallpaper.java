package com.reandroid.wallpaper.walkaround;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class WalkAroundWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new WalkAroundGL(width, height, this);
    }
}

