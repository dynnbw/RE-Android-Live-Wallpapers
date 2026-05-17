package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.vulkan.VKSurfaceView;

class FallVKSurfaceView extends VKSurfaceView<FallScene> {
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;

    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;
    private long mLastAtlasSyncCheckMs;

    FallVKSurfaceView(Context context) {
        super(context);
    }

    FallVKSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void ensureScene() {
        if (mScene == null && mWidth > 0 && mHeight > 0) {
            mScene = new FallScene(mWidth, mHeight);
            mScene.setLeafTextureCount(mLeafTextureCount);
        }
    }

    @Override
    protected void onSceneResize(int width, int height) {
        if (mScene != null) mScene.resize(width, height);
    }

    @Override
    protected void ensureRenderer() {
        if (mRendererHandle == 0L) {
            mRendererHandle = FallVKNative.nCreateRenderer(getContext().getAssets());
            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(getContext(), mRendererHandle));
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
        if (mRendererHandle == 0L) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastAtlasSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) return;
        mLastAtlasSyncCheckMs = now;
        boolean enabled = WallpaperSettings.isGreenLeavesEnabled(false);
        if (enabled == mGreenLeavesEnabled) return;
        mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(getContext(), mRendererHandle));
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
        float[] waterVertices = meshRebuild ? data.getWaterMeshVertices() : null;
        float[] waterTexCoords = texDirty ? data.getWaterMeshTexCoords() : null;
        short[] waterIndices = meshRebuild ? data.getWaterMeshIndices() : null;
        int waterVertexCount = texDirty ? data.getWaterMeshVertexCount() : 0;
        int waterIndexCount = meshRebuild ? data.getWaterMeshIndexCount() : 0;
        FallVKNative.nRenderFrame(mRendererHandle,
                data.getProjectionMatrix(), data.getViewMatrix(),
                leafData, leafCount, data.getXOffset(),
                waterVertices, waterTexCoords, waterIndices,
                waterVertexCount, waterIndexCount);
    }

    @Override
    protected String getThreadName() { return "FallVKPreviewThread"; }

    @Override
    protected String getLogTag() { return "FallVKSurfaceView"; }
}
