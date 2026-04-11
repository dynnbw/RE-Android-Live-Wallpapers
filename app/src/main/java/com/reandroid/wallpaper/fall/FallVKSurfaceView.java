package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.reandroid.wallpaper.settings.WallpaperSettings;

class FallVKSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final String TAG = "FallVKSurfaceView";
    private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;

    private Thread mThread;
    private volatile boolean mRunning;
    private long mRendererHandle;
    private FallScene mScene;
    private int mWidth;
    private int mHeight;
    private int mLeafTextureCount = 1;
    private boolean mGreenLeavesEnabled;
    private long mLastAtlasSyncCheckMs;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled = false;
    private long mLastPerfSyncMs;
    private long mDiagFrameCount;
    private long mDiagAccumulatedMs;
    private long mDiagMaxMs;

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
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        while (mRunning) {
            long frameStart = SystemClock.uptimeMillis();
            syncPerfSettingsIfNeeded(frameStart);

            if (mRendererHandle != 0L && mScene != null) {
                syncLeafAtlasIfNeeded();

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
                        data.getProjectionMatrix(),
                        data.getViewMatrix(),
                        leafData,
                        leafCount,
                        data.getXOffset(),
                        waterVertices,
                        waterTexCoords,
                        waterIndices,
                        waterVertexCount,
                        waterIndexCount);
            }

            long frameCost = SystemClock.uptimeMillis() - frameStart;
            recordFrameCost(frameCost);

            try {
                long sleepMs = Math.max(1L, mTargetFrameMs - frameCost);
                Thread.sleep(sleepMs);
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
        long now = SystemClock.uptimeMillis();
        if (now - mLastAtlasSyncCheckMs < SETTINGS_SYNC_INTERVAL_MS) {
            return;
        }
        mLastAtlasSyncCheckMs = now;

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

    private void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) {
            return;
        }
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    private void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) {
            return;
        }
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) {
            mDiagMaxMs = frameCostMs;
        }
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(TAG, "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
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
