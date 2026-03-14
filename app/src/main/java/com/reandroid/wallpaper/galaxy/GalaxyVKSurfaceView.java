package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.reandroid.wallpaper.settings.WallpaperSettings;

class GalaxyVKSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private Thread mThread;
    private volatile boolean mRunning;
    private long mRendererHandle;
    private GalaxyScene mScene;
    private int mWidth;
    private int mHeight;
    private boolean mUseLight2;

    GalaxyVKSurfaceView(Context context) {
        super(context);
        init();
    }

    GalaxyVKSurfaceView(Context context, AttributeSet attrs) {
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
            GalaxyVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
        }
        startRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;
        if (mScene == null) {
            mScene = new GalaxyScene(width, height, getContext());
        } else {
            mScene.resize(width, height);
        }

        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            GalaxyVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mRendererHandle != 0L) {
            GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
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
            GalaxyVKNative.nOnSurfaceDestroyed(mRendererHandle);
            GalaxyVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
        }
    }

    @Override
    public void run() {
        while (mRunning) {
            if (mRendererHandle != 0L && mScene != null) {
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
        if (mRendererHandle == 0L) {
            mRendererHandle = GalaxyVKNative.nCreateRenderer(getContext().getAssets());
            GalaxyVKNative.uploadBackgroundTexture(getContext(), mRendererHandle);
            GalaxyVKNative.uploadLightTexture(getContext(), mRendererHandle);
            mUseLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
        }
    }

    private void syncLightTextureIfNeeded() {
        if (mRendererHandle == 0L) {
            return;
        }

        boolean desired = WallpaperSettings.isGalaxyLight2Enabled(false);
        if (desired == mUseLight2) {
            return;
        }

        GalaxyVKNative.uploadLightTexture(getContext(), mRendererHandle);
        mUseLight2 = desired;
    }

    private void startRenderer() {
        if (mRunning || mWidth <= 0 || mHeight <= 0) {
            return;
        }
        mRunning = true;
        mThread = new Thread(this, "GalaxyVKPreviewThread");
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