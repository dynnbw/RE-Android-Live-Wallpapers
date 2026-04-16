package com.reandroid.wallpaper.aurora1;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class Aurora1Wallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Aurora1GL(width, height);
    }
}