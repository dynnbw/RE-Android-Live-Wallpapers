package com.reandroid.wallpaper.grass;

/*
 * 各渲染器与 GL 层之间共用的两个小回调。
 *
 * 渲染器本身不碰 GLES，只通过这两个接口把"切程序 / 切混合模式 / 造单色纹理"交回给 GrassGL
 * （Scene/GL 分离的那套）。原先 GrassWeatherRenderer 和 GrassStarRenderer 各自声明了一份
 * 一模一样的，连实现都是同一份，合并到这里 —— 于是引用也从 GrassWeatherRenderer.RenderOps
 * 这种带宿主前缀的写法变回裸名。
 *
 * 两个接口放同一个文件是因为各自只有一两个方法、且服务于同一件事。GrassWeatherRenderer
 * 里的 TextureLoader 只有天气渲染器用，仍留在原处。
 */

/** 绑定背景 program 并切换到常规 alpha 混合。 */
interface RenderOps {
    void useBackgroundProgram();

    void setAlphaBlend();
}

/** 造一个单色纹理（光晕、闪电闪光这类纯色精灵用）。 */
interface SolidColorTextureFactory {
    int create(byte r, byte g, byte b, byte a);
}
