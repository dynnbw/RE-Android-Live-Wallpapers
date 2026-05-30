package com.reandroid.wallpaper.aurora2;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class Aurora2Wallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Aurora2GL(GLESWallpaper.getAppContext(), width, height);
    }
}
