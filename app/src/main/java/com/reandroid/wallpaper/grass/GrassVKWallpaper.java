package com.reandroid.wallpaper.grass;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;

public class GrassVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new GrassVKEngine();
    }

    private final class GrassVKEngine extends Engine implements Runnable {
        private static final int VERTEX_STRIDE = 8;

        private Thread mThread;
        private volatile boolean mRunning;
        private boolean mVisible;
        private SurfaceHolder mHolder;
        private GrassScene mScene;
        private long mRendererHandle;
        private int mWidth;
        private int mHeight;
        private short[] mCachedIndices = new short[0];

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
                mScene.init(false);
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
            while (mRunning) {
                if (mRendererHandle != 0L && mScene != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    long now = SystemClock.uptimeMillis();
                    mScene.update(now);
                    GrassScene.SceneData sd = mScene.getSceneData();

                    if (sd.bladeIndexRebuildNeeded || mCachedIndices.length == 0) {
                        mCachedIndices = mScene.buildGrassIndexArrayForVK();
                    }

                    float[] sky = mScene.computeVKSkyParams(sd);
                    float[] verts = mScene.buildGrassVertexArrayForVK(sd);
                    int vertCount = verts.length / VERTEX_STRIDE;
                        float[] sunVerts = mScene.buildSunSpriteVerticesForVK(sd);
                        float[] dandelionVerts = mScene.buildDandelionSpriteVerticesForVK(sd);
                        float[] fireflyVerts = mScene.buildFireflySpriteVerticesForVK(sd);
                        float[] moonVerts = mScene.buildMoonSpriteVerticesForVK(sd);
                        float[] moonParams = mScene.buildMoonParamsForVK(sd);

                    GrassVKNative.nRenderFrame(mRendererHandle,
                            sky,
                            sd.projectionMatrix,
                            verts,
                            vertCount,
                            mCachedIndices,
                            mCachedIndices.length,
                            sunVerts,
                            sunVerts.length / 5,
                            dandelionVerts,
                            dandelionVerts.length / 5,
                            fireflyVerts,
                            fireflyVerts.length / 5,
                            moonVerts,
                            moonVerts.length / 5,
                            moonParams);
                }

                try {
                    Thread.sleep(33L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void ensureScene() {
            if (mScene == null && mWidth > 0 && mHeight > 0) {
                mScene = new GrassScene(mWidth, mHeight);
                mScene.init(false);
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
    }
}
