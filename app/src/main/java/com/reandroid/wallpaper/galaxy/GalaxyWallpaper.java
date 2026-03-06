package com.reandroid.wallpaper.galaxy;

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.GLESScene;

/**
 * 星系壁纸入口类
 * 继承GLESWallpaper，创建GalaxyGL场景用于渲染
 */
public class GalaxyWallpaper extends GLESWallpaper {
    /**
     * 创建GL渲染场景
     * @param width  屏幕宽度
     * @param height 屏幕高度
     * @return GalaxyGL渲染场景
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new GalaxyGL(width, height, this);
    }
}