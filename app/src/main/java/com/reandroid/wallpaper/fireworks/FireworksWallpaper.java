package com.reandroid.wallpaper.fireworks;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

/**
 * 烟花壁纸主类
 * 继承GLESWallpaper，创建烟花渲染场景
 */
public class FireworksWallpaper extends GLESWallpaper {
    /**
     * 创建渲染场景
     * @param width 场景宽度
     * @param height 场景高度
     * @return 烟花渲染场景实例
     */
    @Override
    protected GLESScene createScene(int width, int height) {
        return new FireworksGL(width, height);
    }
}