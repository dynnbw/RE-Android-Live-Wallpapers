package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.reandroid.wallpaper.settings.WallpaperSettings;

class FallVKSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final int LEAF_STRIDE = 6;

    private Thread mThread;
    private volatile boolean mRunning;
    private long mRendererHandle;
    private FallScene mScene;
    private int mWidth;
    private int mHeight;
    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;

    FallVKSurfaceView(Context context) {
        super(context);
        init();
    }

    FallVKSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
            FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
        }
        startRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new FallScene(width, height);
            mScene.setLeafTextureCount(mLeafTextureCount);
        } else {
            mScene.resize(width, height);
        }

        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            FallVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mRendererHandle != 0L) {
            FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
        }
    }

    void resumeRenderer() {
        startRenderer();
    }

    void pauseRenderer() {
        stopRenderer();
    }

    void releaseRenderer() {
        stopRenderer();
        if (mRendererHandle != 0L) {
            FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
            FallVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void run() {
        while (mRunning) {
            if (mRendererHandle != 0L && mScene != null) {
                syncLeafAtlasIfNeeded();

                long now = SystemClock.uptimeMillis();
                mScene.update(now);
                FallScene.SceneData data = mScene.getSceneData();
                FallScene.Leaf[] leaves = data.getLeaves();
                int leafCount = leaves != null ? leaves.length : 0;
                float[] leafData = new float[leafCount * LEAF_STRIDE];
                for (int i = 0; i < leafCount; i++) {
                    FallScene.Leaf leaf = leaves[i];
                    int base = i * LEAF_STRIDE;
                    leafData[base] = leaf.x;
                    leafData[base + 1] = leaf.y;
                    leafData[base + 2] = leaf.scale;
                    leafData[base + 3] = leaf.angle;
                    leafData[base + 4] = leaf.altitude;
                    leafData[base + 5] = leaf.leafTextureIndex;
                }

                FallVKNative.nRenderFrame(mRendererHandle,
                        data.getProjectionMatrix(),
                        data.getViewMatrix(),
                        leafData,
                        leafCount,
                    data.getXOffset(),
                    data.getWaterMeshVertices(),
                    data.getWaterMeshTexCoords(),
                    data.getWaterMeshIndices(),
                    data.getWaterMeshVertexCount(),
                    data.getWaterMeshIndexCount());
            }

            try {
                Thread.sleep(16L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void ensureRenderer() {
        if (mRendererHandle == 0L) {
            mRendererHandle = FallVKNative.nCreateRenderer(getContext().getAssets());
            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(getContext(), mRendererHandle));
            mGreenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
            if (mScene != null) {
                mScene.setLeafTextureCount(mLeafTextureCount);
            }
        }
    }

    private void syncLeafAtlasIfNeeded() {
        if (mRendererHandle == 0L) {
            return;
        }
        boolean enabled = WallpaperSettings.isGreenLeavesEnabled(false);
        if (enabled == mGreenLeavesEnabled) {
            return;
        }

        mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(getContext(), mRendererHandle));
        mGreenLeavesEnabled = enabled;
        if (mScene != null) {
            mScene.setLeafTextureCount(mLeafTextureCount);
        }
    }

    private void startRenderer() {
        if (mRunning || mWidth <= 0 || mHeight <= 0) {
            return;
        }
        mRunning = true;
        mThread = new Thread(this, "FallVKPreviewThread");
        mThread.start();
    }

    private void stopRenderer() {
        mRunning = false;
        if (mThread != null) {
            try {
                mThread.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mThread = null;
        }
    }
}
