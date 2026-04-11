package com.reandroid.aurora2;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class Aurora2Wallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Aurora2GL(width, height);
    }
}
