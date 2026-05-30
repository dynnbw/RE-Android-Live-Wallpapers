package com.reandroid.wallpaper.weatherwallpapers.ocean;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

public class OceanWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new OceanWeatherGL(this, width, height);
    }
}
