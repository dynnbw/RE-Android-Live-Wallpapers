package com.reandroid.wallpaper.galaxy;

import android.os.Build;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.vulkan.VKWallpaperEngine;

public class GalaxyVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new GalaxyVKEngine();
    }

    private final class GalaxyVKEngine extends VKWallpaperEngine<GalaxyScene> {
        private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

        private boolean mUseLight2;
        private long mLastLightSyncCheckMs;

        GalaxyVKEngine() {
            super(GalaxyVKWallpaper.this);
        }

        @Override
        protected void ensureScene() {
            if (mScene == null && mWidth > 0 && mHeight > 0) {
                mScene = new GalaxyScene(mWidth, mHeight, GalaxyVKWallpaper.this);
            }
        }

        @Override
        protected void ensureOrResizeScene() {
            if (mScene == null) {
                mScene = new GalaxyScene(mWidth, mHeight, GalaxyVKWallpaper.this);
            } else {
                mScene.resize(mWidth, mHeight);
            }
        }

        @Override
        protected void onSceneOffset(float xOffset) {
            mScene.setOffset(xOffset);
        }

        @Override
        protected void ensureRenderer() {
            if (mRendererHandle == 0L) {
                mRendererHandle = GalaxyVKNative.nCreateRenderer(getAssets());
                GalaxyVKNative.uploadBackgroundTexture(GalaxyVKWallpaper.this, mRendererHandle);
                GalaxyVKNative.uploadLightTexture(GalaxyVKWallpaper.this, mRendererHandle);
                mUseLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
            }
        }

        @Override
        protected void destroyRenderer() {
            GalaxyVKNative.nDestroyRenderer(mRendererHandle);
        }

        @Override
        protected void onSurfaceCreatedNative(Surface surface) {
            GalaxyVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
        }

        @Override
        protected void onSurfaceChangedNative(Surface surface) {
            GalaxyVKNative.nOnSurfaceChanged(mRendererHandle, surface, mWidth, mHeight);
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
            GalaxyVKNative.uploadLightTexture(GalaxyVKWallpaper.this, mRendererHandle);
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
        protected String getThreadName() { return "GalaxyVKWallpaperThread"; }

        @Override
        protected String getLogTag() { return "GalaxyVKWallpaper"; }
    }
}
