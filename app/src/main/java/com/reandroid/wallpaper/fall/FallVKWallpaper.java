package com.reandroid.wallpaper.fall;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.wallpaper.settings.WallpaperSettings;

public class FallVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new FallVKEngine();
    }

    private final class FallVKEngine extends Engine implements Runnable {
        private static final String TAG = "FallVKWallpaper";
        private static final long SETTINGS_SYNC_INTERVAL_MS = 1000L;
        private static final long PERF_SYNC_INTERVAL_MS = 1000L;
        private static final long ANR_FRAME_THRESHOLD_MS = 200L;

        private Thread mThread;
        private volatile boolean mRunning;
        private boolean mVisible;
        private SurfaceHolder mHolder;
        private FallScene mScene;
        private long mRendererHandle;
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

        @Override
        public void onDestroy() {
            stopRenderer();
            if (mRendererHandle != 0L) {
                FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
                FallVKNative.nDestroyRenderer(mRendererHandle);
                mRendererHandle = 0L;
            }
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            mHolder = holder;

            if (mWidth <= 0 || mHeight <= 0) {
                Rect frame = holder.getSurfaceFrame();
                if (frame != null) {
                    mWidth = Math.max(0, frame.width());
                    mHeight = Math.max(0, frame.height());
                }
            }

            if (mScene == null && mWidth > 0 && mHeight > 0) {
                mScene = new FallScene(mWidth, mHeight);
                mScene.setLeafTextureCount(mLeafTextureCount);
            }

            ensureRenderer();
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
                FallVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
            }
            startRenderer();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mHolder = holder;
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
            startRenderer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            stopRenderer();
            if (mRendererHandle != 0L) {
                FallVKNative.nOnSurfaceDestroyed(mRendererHandle);
            }
            mHolder = null;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            mVisible = visible;
            if (visible) {
                startRenderer();
            } else {
                stopRenderer();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                int xPixels, int yPixels) {
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels);
            if (mScene != null) {
                mScene.setOffset(xOffset);
            }
            if (mVisible) {
                startRenderer();
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (action != null && action.toLowerCase().contains("tap") && mScene != null) {
                mScene.addDrop(x, y);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
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

                if (mRendererHandle != 0L && mScene != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
            if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRendererHandle = FallVKNative.nCreateRenderer(getAssets());
                mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(FallVKWallpaper.this, mRendererHandle));
                mGreenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
                if (mScene != null) {
                    mScene.setLeafTextureCount(mLeafTextureCount);
                }
            }
        }

        private void syncLeafAtlasIfNeeded() {
            if (mRendererHandle == 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
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

            mLeafTextureCount = Math.max(1, FallVKNative.uploadTextures(FallVKWallpaper.this, mRendererHandle));
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
            if (mRunning || mWidth <= 0 || mHeight <= 0 || mHolder == null) {
                return;
            }
            Surface surface = mHolder.getSurface();
            if (surface == null || !surface.isValid()) {
                return;
            }
            mRunning = true;
            mThread = new Thread(this, "FallVKWallpaperThread");
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
}
