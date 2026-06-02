package com.reandroid.wallpaper.fall;

import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;

import com.reandroid.plugin.BaseVKPluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;
import com.reandroid.settings.WallpaperSettings;

public class FallVKPluginEngine extends BaseVKPluginEngine {
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;
    private static final float TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX = 42.0f;

    private FallScene mScene;
    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;
    private long mLastAtlasSyncCheckMs;
    private float mLastTouchTriggerX = -1.0f;
    private float mLastTouchTriggerY = -1.0f;

    public FallVKPluginEngine(android.content.Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected String getLogTag() { return "FallVK"; }

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
    protected long createRenderer() {
        long handle = FallVKNative.nCreateRenderer(mContext.getAssets());
        if (handle != 0L) {
            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(mContext, handle));
            mGreenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
            if (mScene != null) mScene.setLeafTextureCount(mLeafTextureCount);
        }
        return handle;
    }

    @Override
    protected void destroyRenderer() {
        FallVKNative.nDestroyRenderer(mRendererHandle);
    }

    @Override
    protected void onSurfaceCreatedNative(Surface surface, int w, int h) {
        FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, w, h);
    }

    @Override
    protected void onSurfaceChangedNative(Surface surface, int w, int h) {
        FallVKNative.nOnSurfaceChanged(mRendererHandle, surface, w, h);
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
        mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(mContext, mRendererHandle));
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
    protected void onSceneOffset(float xOffset) {
        if (mScene != null) mScene.setOffset(xOffset);
    }

    @Override
    protected void onSceneTouch(float x, float y) {
        if (mScene != null) mScene.addDrop((int) x, (int) y);
    }

    // ---- touch handling (Fall-specific) ----

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (action != null && action.toLowerCase().contains("tap") && mScene != null) {
            mScene.addDrop(x, y);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
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
