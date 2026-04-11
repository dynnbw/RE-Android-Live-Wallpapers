package com.reandroid.aurora1;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class Aurora1Wallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Aurora1GL(width, height);
    }
}