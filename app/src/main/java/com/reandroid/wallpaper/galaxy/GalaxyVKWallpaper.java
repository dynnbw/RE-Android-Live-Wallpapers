package com.reandroid.wallpaper.galaxy;

import android.os.Build;
import android.os.SystemClock;
import android.graphics.Rect;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.wallpaper.settings.WallpaperSettings;

/**
 * Galaxy 的 Vulkan 壁纸服务实现。
 */
public class GalaxyVKWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new GalaxyVKEngine();
    }

    private final class GalaxyVKEngine extends Engine implements Runnable {
        private Thread mThread;
        private volatile boolean mRunning;
        private boolean mVisible;
        private SurfaceHolder mHolder;
        private GalaxyScene mScene;
        private long mRendererHandle;
        private int mWidth;
        private int mHeight;
        private boolean mUseLight2;

        @Override
        public void onDestroy() {
            stopRenderer();
            if (mRendererHandle != 0L) {
                GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
                GalaxyVKNative.nDestroyRenderer(mRendererHandle);
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
                mScene = new GalaxyScene(mWidth, mHeight, GalaxyVKWallpaper.this);
            }

            ensureRenderer();
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
                GalaxyVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
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
                mScene = new GalaxyScene(width, height, GalaxyVKWallpaper.this);
            } else {
                mScene.resize(width, height);
            }
            ensureRenderer();
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                GalaxyVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
            }
            startRenderer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            stopRenderer();
            if (mRendererHandle != 0L) {
                GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
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
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels,
                int yPixels) {
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
                    syncLightTextureIfNeeded();

                    long now = SystemClock.uptimeMillis();
                    mScene.update(now);
                    GalaxyScene.SceneData sceneData = mScene.getSceneData();
                    GalaxyVKNative.nRenderFrame(mRendererHandle,
                            sceneData.getMvpMatrix(),
                            sceneData.getParticlePositions(),
                            sceneData.getParticleColors(),
                            sceneData.getParticleCount(),
                            sceneData.getParticleAlphaMultiplier());
                }
                try {
                    Thread.sleep(33L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void ensureRenderer() {
            if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRendererHandle = GalaxyVKNative.nCreateRenderer(getAssets());
                GalaxyVKNative.uploadBackgroundTexture(GalaxyVKWallpaper.this, mRendererHandle);
                GalaxyVKNative.uploadLightTexture(GalaxyVKWallpaper.this, mRendererHandle);
                mUseLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
            }
        }

        private void syncLightTextureIfNeeded() {
            if (mRendererHandle == 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return;
            }

            boolean desired = WallpaperSettings.isGalaxyLight2Enabled(false);
            if (desired == mUseLight2) {
                return;
            }

            GalaxyVKNative.uploadLightTexture(GalaxyVKWallpaper.this, mRendererHandle);
            mUseLight2 = desired;
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
            mThread = new Thread(this, "GalaxyVKWallpaperThread");
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