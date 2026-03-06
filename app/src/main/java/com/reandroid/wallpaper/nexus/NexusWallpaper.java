package com.reandroid.wallpaper.nexus;

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.GLESScene;

/**
 * Nexus动态壁纸主类
 * 基于GLESWallpaper实现，创建NexusGL场景完成OpenGL ES 2.0渲染
 * 100%还原RenderScript版本（nexus.rs）的视觉效果
 */
public class NexusWallpaper extends GLESWallpaper {
    /**
     * 创建壁纸渲染场景
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return NexusGL场景实例（负责具体的OpenGL渲染逻辑）
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new NexusGL(width, height);
    }
}