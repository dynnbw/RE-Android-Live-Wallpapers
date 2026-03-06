package com.reandroid.wallpaper.phasebeam;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

public class PhaseBeamWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new PhaseBeamGL(width, height, this);
    }
}
