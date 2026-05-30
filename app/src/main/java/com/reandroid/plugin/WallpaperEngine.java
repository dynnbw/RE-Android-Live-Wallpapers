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
    void onOffsetsChanged(float xOffset, float yOffset,
                          float xStep, float yStep,
                          int xPixels, int yPixels);

    /** Touch event forwarded from the wallpaper surface. */
    void onTouchEvent(MotionEvent event);

    /** System command (e.g. android.wallpaper.tap). */
    void onCommand(String action, int x, int y, int z, Bundle extras);

    /** Render one frame. Called from a dedicated render thread. */
    void drawFrame(long timeMs);

    /** Release all GPU and engine resources. Called before plugin unload. */
    void release();

    /** Set whether the engine is running in system preview mode. */
    void setPreview(boolean isPreview);
}
