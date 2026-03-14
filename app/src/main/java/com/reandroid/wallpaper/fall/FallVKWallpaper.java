package com.reandroid.wallpaper.fall;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.wallpaper.settings.WallpaperSettings;

public class FallVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new FallVKEngine();
    }

    private final class FallVKEngine extends Engine implements Runnable {
        private static final int LEAF_STRIDE = 6;

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
            while (mRunning) {
                if (mRendererHandle != 0L && mScene != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
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
