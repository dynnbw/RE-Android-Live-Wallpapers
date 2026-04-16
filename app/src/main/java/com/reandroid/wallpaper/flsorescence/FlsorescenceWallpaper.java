package com.reandroid.wallpaper.flsorescence;

import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.GLESScene;

/**
 * 荧光动态壁纸 - 从三星Luminous Dots完全移植
 * 100%还原视觉效果，展示荧光点在屏幕上平滑移动
 */
public class FlsorescenceWallpaper extends GLESWallpaper {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new FlsorescenceGL(width, height);
    }
}
