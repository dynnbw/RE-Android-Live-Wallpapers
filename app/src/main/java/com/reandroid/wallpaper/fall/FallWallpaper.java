package com.reandroid.wallpaper.fall;

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.GLESScene;

/**
 * 落叶壁纸的壁纸服务实现类
 * 继承自GLESWallpaper（OpenGL ES壁纸基类），负责创建OpenGL渲染场景
 */
public class FallWallpaper extends GLESWallpaper {
    /**
     * 创建OpenGL渲染场景
     * @param width 壁纸显示宽度
     * @param height 壁纸显示高度
     * @return FallGL 落叶壁纸的OpenGL ES 2.0渲染实现
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new FallGL(width, height);
    }
}