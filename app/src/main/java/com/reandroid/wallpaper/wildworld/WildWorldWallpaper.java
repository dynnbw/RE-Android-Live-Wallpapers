package com.reandroid.wallpaper.wildworld;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;

/**
 * 野生世界动态壁纸核心类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建野生世界的渲染场景
 */
public class WildWorldWallpaper extends GLESWallpaper {
    /**
     * 创建OpenGL ES渲染场景
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return 野生世界的GL渲染场景实例
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new WildWorldGL(width, height);
    }
}