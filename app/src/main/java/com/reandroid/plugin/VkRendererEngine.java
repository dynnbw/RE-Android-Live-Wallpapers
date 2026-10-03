package com.reandroid.plugin;

import android.content.Context;
import android.view.Surface;

/**
 * 壁纸路径上的 VK 引擎：线程、配速、surface 生命周期由 {@link BaseVKPluginEngine} 负责，
 * 这里只把每个钩子转给 {@link VkRendererDelegate}。
 *
 * <p>于是"加一个 VK 壁纸"不再需要继承引擎、实现十二个抽象方法，只需要写一个 delegate ——
 * 而同一个 delegate 也喂给预览（{@code VKSurfaceView}）。
 */
public class VkRendererEngine extends BaseVKPluginEngine {

    private final VkRendererDelegate mDelegate;

    public VkRendererEngine(
            Context context, WallpaperPluginHost host, VkRendererDelegate delegate) {
        super(context, host);
        mDelegate = delegate;
    }

    @Override
    protected String getLogTag() {
        return mDelegate.logTag();
    }

    @Override
    protected void ensureScene() {
        mDelegate.ensureScene(mWidth, mHeight);
    }

    @Override
    protected void ensureOrResizeScene() {
        mDelegate.ensureScene(mWidth, mHeight);
    }

    @Override
    protected void onPluginPrefsChanged() {
        mDelegate.onPluginPrefsChanged(mHost.getSharedPreferences());
        // 壁纸路径没有"每帧同步"的必要，但设置变了要立刻把纹理换过来
        mDelegate.syncTexturesIfNeeded();
    }

    @Override
    protected long createRenderer() {
        return mDelegate.createRenderer();
    }

    @Override
    protected void destroyRenderer() {
        mDelegate.destroyRenderer();
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        mDelegate.onSurfaceCreated(surface, w, h);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        mDelegate.onSurfaceChanged(surface, w, h);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        mDelegate.onSurfaceDestroyed();
    }

    @Override
    protected void syncTexturesIfNeeded() {
        mDelegate.syncTexturesIfNeeded();
    }

    @Override
    protected void renderFrame() {
        mDelegate.renderFrame();
    }

    @Override
    protected void onSceneOffset(float xOffset) {
        mDelegate.onSceneOffset(xOffset);
    }

    @Override
    protected void onSceneTouch(float x, float y) {
        mDelegate.onSceneTouch(x, y);
    }

    /*
     * 这两个是完整回调（基类只把按下转成 onSceneTouch），不再同时走基类那条路 ——
     * 否则 fall 的滑动波纹会一次触摸落两片叶子。
     */
    @Override
    public void onTouchEvent(android.view.MotionEvent event) {
        mDelegate.onTouchEvent(event);
    }

    @Override
    public void onCommand(String action, int x, int y, int z, android.os.Bundle extras) {
        mDelegate.onCommand(action, x, y, z, extras);
    }

    @Override
    public void onSurfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
        if (mDelegate.needsFullRecreateOnSurfaceChange()) {
            super.onSurfaceChanged(holder, format, w, h);
        } else {
            applySurfaceChangeLightweight(holder, w, h);
        }
    }

    /** 场景还没建时进去取不到 SceneData —— 这条由 delegate 说了算。 */
    @Override
    protected boolean canRenderFrame() {
        return super.canRenderFrame() && mDelegate.isReady();
    }
}
