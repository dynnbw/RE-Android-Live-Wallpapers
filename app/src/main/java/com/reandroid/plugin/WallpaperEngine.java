package com.reandroid.plugin;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * Rendering engine for a single wallpaper plugin instance.
 * Each plugin manages its own EGL/Vulkan context internally.
 * Lifecycle is driven by the ProxyEngine host.
 */
public interface WallpaperEngine {

    /** Surface created — plugin should set up EGL/Vulkan context. */
    void onCreate(SurfaceHolder holder);

    /** Surface destroyed — plugin should tear down EGL/Vulkan context. */
    void onDestroy();

    /** Visibility changed — plugin may pause/resume rendering. */
    void onVisibilityChanged(boolean visible);

    /**
     * Surface dimensions changed.
     * @param holder  current surface holder
     * @param format  pixel format
     * @param width   new width in pixels
     * @param height  new height in pixels
     */
    void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height);

    /**
     * Desktop scroll offset changed.
     * @param xOffset  0..1 horizontal scroll fraction
     * @param yOffset  0..1 vertical scroll fraction
     * @param xStep    pixels per horizontal scroll step
     * @param yStep    pixels per vertical scroll step
     * @param xPixels  total horizontal scroll pixels
     * @param yPixels  total vertical scroll pixels
     */
    void onOffsetsChanged(
            float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels);

    /** Touch event forwarded from the wallpaper surface. */
    void onTouchEvent(MotionEvent event);

    /** System command (e.g. android.wallpaper.tap). */
    void onCommand(String action, int x, int y, int z, Bundle extras);

    /** Render one frame. Called from a dedicated render thread. */
    void drawFrame(long timeMs);

    /**
     * 引擎是否自带渲染线程。
     *
     * <p>true 表示画面由引擎自己的线程推进，{@link #drawFrame} 是空实现 —— 宿主<b>不要</b>再起
     * 一条循环去逐帧调它，那是纯粹的空转（VK 插件就是这样：它有 {@code GalaxyVKThread} 之类的
     * 线程，宿主那条循环每帧做的事只有 {@code drawFrame} 一个空调用）。
     *
     * <p>默认 false：由宿主每帧驱动，{@link #drawFrame} 里完成绘制。
     */
    default boolean isSelfDriven() {
        return false;
    }

    /** Release all GPU and engine resources. Called before plugin unload. */
    void release();

    /** Set whether the engine is running in system preview mode. */
    void setPreview(boolean isPreview);
}
