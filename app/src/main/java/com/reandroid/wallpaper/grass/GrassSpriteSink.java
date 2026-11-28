package com.reandroid.wallpaper.grass;

/**
 * 天气图层画精灵的去处。
 *
 * <p>天气的几何与动画全在 {@link GrassWeatherRenderer} 里算，GLES 那条路把它直接画掉；
 * VK 那条路需要**同一份几何**，于是先录进顶点数组、再交给 native。
 * 两条路径共用同一套绘制调用，几何不可能各走各的。
 *
 * <p>{@code texture} 是调用方自己的句柄：GLES 给的是 GL 纹理 id，VK 给的是精灵槽位号，
 * 两端各自解释。
 */
interface GrassSpriteSink {

    void setTint(float r, float g, float b);

    void setTintWhite();

    void drawSprite(
            int texture,
            float cx,
            float cy,
            float size,
            float alpha,
            boolean flipV,
            float rotationDeg);

    void drawRect(int texture, float left, float top, float width, float height, float alpha);

    void drawRectUv(
            int texture,
            float left,
            float top,
            float right,
            float bottom,
            float uLeft,
            float vTop,
            float uRight,
            float vBottom,
            float alpha);

    void drawBatch(int texture, float[] vertices, int floatCount, float alpha);
}
