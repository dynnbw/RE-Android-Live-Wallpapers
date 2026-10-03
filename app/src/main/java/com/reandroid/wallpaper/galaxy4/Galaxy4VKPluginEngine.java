package com.reandroid.wallpaper.galaxy4;

import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.reandroid.plugin.BaseVKPluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class Galaxy4VKPluginEngine extends BaseVKPluginEngine {
    private static final String TAG = "Galaxy4VKPlugin";

    static {
        Log.i(TAG, "*** CLASS LOADED ***");
    }

    private Galaxy4Scene mScene;

    public Galaxy4VKPluginEngine(android.content.Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected String getLogTag() {
        return "Galaxy4VK";
    }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new Galaxy4Scene(mWidth, mHeight, mContext);
            mScene.setPluginPrefs(mHost.getSharedPreferences());
        }
    }

    @Override
    protected void ensureOrResizeScene() {
        if (mScene == null) {
            mScene = new Galaxy4Scene(mWidth, mHeight, mContext);
            mScene.setPluginPrefs(mHost.getSharedPreferences());
        } else {
            mScene.resize(mWidth, mHeight);
        }
    }

    @Override
    protected void onPluginPrefsChanged() {
        if (mScene != null) mScene.setPluginPrefs(mHost.getSharedPreferences());
    }

    @Override
    protected long createRenderer() {
        long handle = Galaxy4VKNative.nCreateRenderer(mContext.getAssets());
        if (handle != 0L) Galaxy4VKNative.uploadTextures(mContext, handle);
        return handle;
    }

    @Override
    protected void destroyRenderer() {
        Galaxy4VKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        Galaxy4VKNative.nOnSurfaceCreated(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        Galaxy4VKNative.nOnSurfaceChanged(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        Galaxy4VKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    @Override
    protected void syncTexturesIfNeeded() {}

    /**
     * 本插件的 surface 可能晚于渲染线程就绪 —— 每帧补一次创建（基类循环里的钩子）。
     *
     * <p>原来这段在 {@code Galaxy4VKPluginEngine.run()} 里，那份循环自带 {@code sleep(16)}，
     * 于是全局帧率设置对它完全无效。循环交给基类之后，配速与帧率策略就和其它插件一致了。
     */
    @Override
    protected void ensureSurfaceReady() {
        if (mSurfaceCreated || mHolder == null) return;
        Surface s = mHolder.getSurface();
        if (s == null || !s.isValid()) return;
        Log.i(TAG, "deferred surface creation");
        mSurfaceCreated = true;
        onSurfaceCreatedNative(s, mWidth, mHeight);
    }

    /** 场景也必须是就绪条件之一：它为 null 时 renderFrame 会取不到 SceneData。 */
    @Override
    protected boolean canRenderFrame() {
        return super.canRenderFrame() && mScene != null;
    }

    /**
     * 只覆盖尺寸与场景，然后把线程交给基类启动。
     *
     * <p>不调用基类那份「整个销毁重建」是有意的：本插件的 surface 可能此刻还无效，
     * 而基类那份会因此提前返回、连尺寸都记不下来（见 {@link #ensureSurfaceReady()}）。
     */
    @Override
    public void onSurfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
        mHolder = holder;
        mWidth = w;
        mHeight = h;
        ensureOrResizeScene();
        ensureRenderer();
        startRenderer();
    }

    @Override
    protected void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        Galaxy4Scene.SceneData data = mScene.getSceneData();
        Galaxy4VKNative.nRenderFrame(
                mRendererHandle,
                data.getMvpMatrix(),
                data.getSpaceClouds(),
                data.getBgStars(),
                data.getStaticStars(),
                data.getSpaceCloudCount(),
                data.getBgStarCount(),
                data.getTimeSeconds(),
                data.getParticleSize(),
                data.getParticleOpacity());
    }

    @Override
    protected void onSceneOffset(float xOffset) {}

    @Override
    protected void onSceneTouch(float x, float y) {}
}
