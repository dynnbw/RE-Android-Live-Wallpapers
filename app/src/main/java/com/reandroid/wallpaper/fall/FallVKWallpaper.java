package com.reandroid.wallpaper.fall;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.Surface;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.vulkan.VKWallpaperEngine;

public class FallVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new FallVKEngine();
    }

    private final class FallVKEngine extends VKWallpaperEngine<FallScene> {
        private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;
        private static final float TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX = 42.0f;

        private int mLeafTextureCount = 1;
        private boolean mGreenLeavesEnabled;
        private long mLastAtlasSyncCheckMs;
        private float mLastTouchTriggerX = -1.0f;
        private float mLastTouchTriggerY = -1.0f;

        FallVKEngine() {
            super(FallVKWallpaper.this);
            setTouchEventsEnabled(true);
        }

        @Override
        protected void ensureScene() {
            if (mScene == null && mWidth > 0 && mHeight > 0) {
                mScene = new FallScene(mWidth, mHeight);
                mScene.setLeafTextureCount(mLeafTextureCount);
            }
        }

        @Override
        protected void ensureOrResizeScene() {
            if (mScene == null) {
                mScene = new FallScene(mWidth, mHeight);
                mScene.setLeafTextureCount(mLeafTextureCount);
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
            if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRendererHandle = FallVKNative.nCreateRenderer(getAssets());
                mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(FallVKWallpaper.this, mRendererHandle));
                mGreenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
                if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
            }
        }

        @Override
        protected void destroyRenderer() {
            FallVKNative.nDestroyRenderer(mRendererHandle);
        }

        @Override
        protected void onSurfaceCreatedNative(Surface surface) {
            FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
        }

        @Override
        protected void onSurfaceChangedNative(Surface surface) {
            FallVKNative.nOnSurfaceChanged(mRendererHandle, surface, mWidth, mHeight);
        }

        @Override
        protected void onSurfaceDestroyedNative() {
            FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
        }

        @Override
        protected void syncTexturesIfNeeded() {
            if (mRendererHandle == 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            long now = SystemClock.uptimeMillis();
            if (now - mLastAtlasSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
            mLastAtlasSyncCheckMs = now;
            boolean enabled = WallpaperSettings.isGreenLeavesEnabled(false);
            if (enabled == mGreenLeavesEnabled) return;
            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(FallVKWallpaper.this, mRendererHandle));
            mGreenLeavesEnabled = enabled;
            if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
        }

        @Override
        protected void renderFrame() {
            long now = SystemClock.uptimeMillis();
            mScene.update(now);
            FallScene.SceneData data = mScene.getSceneData();
            float[] leafData = mScene.buildLeafDataForVK();
            int leafCount = mScene.getVKLeafCount();
            boolean meshRebuild = mScene.consumeMeshBufferRebuildRequested();
            boolean texDirty = meshRebuild || mScene.consumeWaterTexCoordsDirty();
            FallVKNative.nRenderFrame(mRendererHandle,
                    data.getProjectionMatrix(), data.getViewMatrix(),
                    leafData, leafCount, data.getXOffset(),
                    meshRebuild ? data.getWaterMeshVertices() : null,
                    texDirty ? data.getWaterMeshTexCoords() : null,
                    meshRebuild ? data.getWaterMeshIndices() : null,
                    texDirty ? data.getWaterMeshVertexCount() : 0,
                    meshRebuild ? data.getWaterMeshIndexCount() : 0);
        }

        @Override
        protected String getThreadName() { return "FallVKWallpaperThread"; }

        @Override
        protected String getLogTag() { return "FallVKWallpaper"; }

        // ---- touch handling (Fall-specific) ----

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (action != null && action.toLowerCase().contains("tap") && mScene != null) {
                mScene.addDrop(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            if (event == null || mScene == null) return;
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mLastTouchTriggerX < 0.0f || mLastTouchTriggerY < 0.0f) {
                        mScene.addDrop((int) x, (int) y);
                        mLastTouchTriggerX = x;
                        mLastTouchTriggerY = y;
                        break;
                    }
                    float dx = x - mLastTouchTriggerX;
                    float dy = y - mLastTouchTriggerY;
                    if ((float) Math.sqrt(dx * dx + dy * dy) >= TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX) {
                        mScene.addDrop((int) x, (int) y);
                        mLastTouchTriggerX = x;
                        mLastTouchTriggerY = y;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mLastTouchTriggerX = -1.0f;
                    mLastTouchTriggerY = -1.0f;
                    break;
            }
        }
    }
}
