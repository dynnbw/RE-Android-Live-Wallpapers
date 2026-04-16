package com.reandroid.wallpaper.grass;

import android.graphics.Rect;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.settings.WallpaperSettings;

public class GrassVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new GrassVKEngine();
    }

    private final class GrassVKEngine extends Engine implements Runnable {
        private static final String TAG = "GrassVKWallpaper";
        private static final int VERTEX_STRIDE = 8;
        private static final long PERF_SYNC_INTERVAL_MS = 1000L;
        private static final long ANR_FRAME_THRESHOLD_MS = 200L;

        private Thread mThread;
        private volatile boolean mRunning;
        private boolean mVisible;
        private SurfaceHolder mHolder;
        private GrassScene mScene;
        private long mRendererHandle;
        private int mWidth;
        private int mHeight;
        private short[] mCachedIndices = new short[0];
        private int mTargetFps = 30;
        private long mTargetFrameMs = 33L;
        private boolean mAnrDiagEnabled = false;
        private long mLastPerfSyncMs = 0L;
        private long mDiagFrameCount = 0L;
        private long mDiagAccumulatedMs = 0L;
        private long mDiagMaxMs = 0L;

        @Override
        public void onDestroy() {
            stopRenderer();
            if (mRendererHandle != 0L) {
                GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
                GrassVKNative.nDestroyRenderer(mRendererHandle);
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

            ensureScene();
            ensureRenderer();
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
                GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
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
                mScene = new GrassScene(width, height);
                mScene.init(isPreview());
            } else {
                mScene.resize(width, height);
            }

            ensureRenderer();
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
            }
            startRenderer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            stopRenderer();
            if (mRendererHandle != 0L) {
                GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
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
        public void run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }

            while (mRunning) {
                long frameStart = SystemClock.uptimeMillis();
                syncPerfSettingsIfNeeded(frameStart);

                if (mRendererHandle != 0L && mScene != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    long now = SystemClock.uptimeMillis();
                    mScene.update(now);
                    SceneData sd = mScene.getSceneData();

                    if (sd.bladeIndexRebuildNeeded || mCachedIndices.length == 0) {
                        mCachedIndices = mScene.buildGrassIndexArray();
                    }

                    float[] sky = mScene.computeSkyParams(sd);
                    float[] verts = mScene.buildGrassVertexArray(sd);
                    int vertCount = mScene.getGrassVertexCount();
                        float[] sunVerts = mScene.buildSunSpriteVertices(sd);
                        float[] dandelionVerts = mScene.buildDandelionSpriteVertices(sd);
                        float[] fireflyVerts = mScene.buildFireflySpriteVertices(sd);
                        float[] fireflyFlareVerts = mScene.buildFireflyFlareSpriteVertices(sd);
                        float[] moonVerts = mScene.buildMoonSpriteVertices(sd);
                        float[] moonParams = mScene.buildMoonParams(sd);

                    GrassVKNative.nRenderFrame(mRendererHandle,
                            sky,
                            sd.projectionMatrix,
                            verts,
                            vertCount,
                            mCachedIndices,
                            mCachedIndices.length,
                            sunVerts,
                            mScene.getSunVertexCount(),
                            dandelionVerts,
                            mScene.getDandelionVertexCount(),
                            fireflyVerts,
                            mScene.getFireflyVertexCount(),
                            fireflyFlareVerts,
                            mScene.getFireflyFlareVertexCount(),
                            moonVerts,
                            mScene.getMoonVertexCount(),
                            moonParams);
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

        private void ensureScene() {
            if (mScene == null && mWidth > 0 && mHeight > 0) {
                mScene = new GrassScene(mWidth, mHeight);
                mScene.init(isPreview());
            }
        }

        private void ensureRenderer() {
            if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRendererHandle = GrassVKNative.nCreateRenderer(getAssets());
                GrassVKNative.uploadSkyTextures(GrassVKWallpaper.this, mRendererHandle);
                GrassVKNative.uploadAATexture(mRendererHandle);
                GrassVKNative.uploadSpriteTextures(GrassVKWallpaper.this, mRendererHandle);
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
            mThread = new Thread(this, "GrassVKWallpaperThread");
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
    }
}
