package com.reandroid.wallpaper.magicsmoke;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

/**
 * Magic Smoke live wallpaper entry point.
 */
public class MagicSmokeWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MagicSmokeGL(getApplicationContext(), width, height);
    }
}
