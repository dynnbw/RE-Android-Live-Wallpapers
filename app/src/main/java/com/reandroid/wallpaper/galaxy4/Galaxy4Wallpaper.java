package com.reandroid.wallpaper.galaxy4;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

/**
 * Galaxy4动态壁纸的主类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建Galaxy4壁纸的OpenGL场景
 */
public class Galaxy4Wallpaper extends GLESWallpaper {
    /**
     * 创建壁纸的OpenGL渲染场景
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return 初始化后的Galaxy4GL场景实例，用于渲染Galaxy4壁纸
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new Galaxy4GL(width, height, this);
    }
}