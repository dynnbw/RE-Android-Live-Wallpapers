package com.reandroid.wallpaper.galaxy;

import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import com.reandroid.plugin.BaseVKPluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.settings.WallpaperSettings;

public class GalaxyVKPluginEngine extends BaseVKPluginEngine {
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

    private GalaxyScene mScene;
    private boolean mUseLight2;
    private long mLastLightSyncCheckMs;

    public GalaxyVKPluginEngine(android.content.Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected String getLogTag() { return "GalaxyVK"; }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new GalaxyScene(mWidth, mHeight, mContext);
        }
    }

    @Override
    protected void ensureOrResizeScene() {
        if (mScene == null) {
            mScene = new GalaxyScene(mWidth, mHeight, mContext);
        } else {
            mScene.resize(mWidth, mHeight);
        }
    }

    @Override
    protected long createRenderer() {
        long handle = GalaxyVKNative.nCreateRenderer(mContext.getAssets());
        if (handle != 0L) {
            GalaxyVKNative.uploadBackgroundTexture(mContext, handle);
            GalaxyVKNative.uploadLightTexture(mContext, handle);
            mUseLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
        }
        return handle;
    }

    @Override
    protected void destroyRenderer() {
        GalaxyVKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        GalaxyVKNative.nOnSurfaceCreated(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        GalaxyVKNative.nOnSurfaceChanged(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceDestroyedNative() {
        GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
    }

    @Override
    protected void syncTexturesIfNeeded() {
        if (mRendererHandle == 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastLightSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastLightSyncCheckMs = now;
        boolean desired = WallpaperSettings.isGalaxyLight2Enabled(false);
        if (desired == mUseLight2) return;
        GalaxyVKNative.uploadLightTexture(mContext, mRendererHandle);
        mUseLight2 = desired;
    }

    @Override
    protected void renderFrame() {
        long now = SystemClock.uptimeMillis();
        mScene.update(now);
        GalaxyScene.SceneData sceneData = mScene.getSceneData();
        boolean colorsDirty = mScene.consumeParticleBufferRebuildRequested();
        GalaxyVKNative.nRenderFrame(mRendererHandle,
                sceneData.getMvpMatrix(),
                sceneData.getParticlePositions(),
                colorsDirty ? sceneData.getParticleColors() : null,
                sceneData.getParticleCount(),
                sceneData.getParticleAlphaMultiplier());
    }

    @Override
    protected void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    protected void onSceneTouch(float x, float y) {
        // Galaxy wallpaper has no touch interaction
    }
}
