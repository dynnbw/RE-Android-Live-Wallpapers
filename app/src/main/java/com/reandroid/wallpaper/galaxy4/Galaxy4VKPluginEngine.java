package com.reandroid.wallpaper.galaxy4;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.reandroid.plugin.BaseVKPluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class Galaxy4VKPluginEngine extends BaseVKPluginEngine {
    private static final String TAG = "Galaxy4VKPlugin";
    static { Log.i(TAG, "*** CLASS LOADED ***"); }

    private Galaxy4Scene mScene;

    public Galaxy4VKPluginEngine(android.content.Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override protected String getLogTag() { return "Galaxy4VK"; }

    @Override protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new Galaxy4Scene(mWidth, mHeight, mContext);
            mScene.setPluginPrefs(mHost.getSharedPreferences());
        }
    }

    @Override protected void ensureOrResizeScene() {
        if (mScene == null) {
            mScene = new Galaxy4Scene(mWidth, mHeight, mContext);
            mScene.setPluginPrefs(mHost.getSharedPreferences());
        } else {
            mScene.resize(mWidth, mHeight);
        }
    }

    @Override protected void onPluginPrefsChanged() {
        if (mScene != null) mScene.setPluginPrefs(mHost.getSharedPreferences());
    }

    @Override protected long createRenderer() {
        long handle = Galaxy4VKNative.nCreateRenderer(mContext.getAssets());
        if (handle != 0L) Galaxy4VKNative.uploadTextures(mContext, handle);
        return handle;
    }

    @Override protected void destroyRenderer() { Galaxy4VKNative.nDestroyRenderer(mRendererHandle); }

    @Override protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        Galaxy4VKNative.nOnSurfaceCreated(mRendererHandle, surface, w, h);
    }

    @Override protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        Galaxy4VKNative.nOnSurfaceChanged(mRendererHandle, surface, w, h);
    }

    @Override protected void onSurfaceDestroyedNative() {
        Galaxy4VKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    @Override protected void syncTexturesIfNeeded() {}

    @Override public void onVisibilityChanged(boolean visible) {
        mVisible = visible;
        Log.i(TAG, "onVisibilityChanged visible=" + visible);
        if (visible) startRendererDeferred();
        else { mRunning = false; if (mThread != null) { try { mThread.join(1000); } catch (Exception e) {} mThread = null; } }
    }

    // Override surface lifecycle to ensure render thread starts even
    // when surface is initially invalid (BaseVKPluginEngine.startRenderer
    // requires valid surface, but wallpaper surfaces may not be valid yet).
    @Override
    public void onSurfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
        mHolder = holder; mWidth = w; mHeight = h;
        ensureOrResizeScene(); ensureRenderer();
        startRendererDeferred();
    }

    private void startRendererDeferred() {
        if (mThread != null || !mVisible) return;
        mRunning = true;
        mThread = new Thread(this, "Galaxy4VKThread");
        mThread.start();
        Log.i(TAG, "thread started (deferred), visible=" + mVisible);
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Log.i(TAG, "render thread loop started");
        while (mRunning) {
            if (!mSurfaceCreated && mHolder != null) {
                Surface s = mHolder.getSurface();
                if (s != null && s.isValid()) {
                    Log.i(TAG, "deferred surface creation");
                    mSurfaceCreated = true;
                    onSurfaceCreatedNative(s, mWidth, mHeight);
                }
            }
            if (mRendererHandle != 0L && mScene != null && mSurfaceCreated) {
                syncTexturesIfNeeded();
                renderFrame();
            }
            try { Thread.sleep(16); } catch (InterruptedException e) { return; }
        }
    }

    @Override protected void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        Galaxy4Scene.SceneData data = mScene.getSceneData();
        Galaxy4VKNative.nRenderFrame(mRendererHandle,
                data.getMvpMatrix(),
                data.getSpaceClouds(), data.getBgStars(), data.getStaticStars(),
                data.getSpaceCloudCount(), data.getBgStarCount(),
                data.getTimeSeconds(),
                data.getParticleSize(), data.getParticleOpacity());
    }

    @Override protected void onSceneOffset(float xOffset) {}

    @Override protected void onSceneTouch(float x, float y) {}
}
