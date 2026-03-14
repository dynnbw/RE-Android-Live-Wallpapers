package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

class GrassVKSurfaceView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final int VERTEX_STRIDE = 8;

    private Thread mThread;
    private volatile boolean mRunning;
    private long mRendererHandle;
    private GrassScene mScene;
    private int mWidth;
    private int mHeight;
    private short[] mCachedIndices = new short[0];

    GrassVKSurfaceView(Context context) {
        super(context);
        init();
    }

    GrassVKSurfaceView(Context context, AttributeSet attrs) {
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
        ensureScene();
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
            GrassVKNative.nOnSurfaceCreated(mRendererHandle, surface, mWidth, mHeight);
        }
        startRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;

        ensureScene();
        mScene.resize(width, height);

        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            GrassVKNative.nOnSurfaceChanged(mRendererHandle, surface, width, height);
        }
        startRenderer();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mRendererHandle != 0L) {
            GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
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
            GrassVKNative.nOnSurfaceDestroyed(mRendererHandle);
            GrassVKNative.nDestroyRenderer(mRendererHandle);
            mRendererHandle = 0L;
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

                GrassVKNative.nRenderFrame(mRendererHandle,
                        sky,
                        sd.projectionMatrix,
                        verts,
                        vertCount,
                        mCachedIndices,
                        mCachedIndices.length);
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
            mScene.init(true);
        }
    }

    private void ensureRenderer() {
        if (mRendererHandle == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mRendererHandle = GrassVKNative.nCreateRenderer(getContext().getAssets());
            GrassVKNative.uploadSkyTextures(getContext(), mRendererHandle);
            GrassVKNative.uploadAATexture(mRendererHandle);
        }
    }

    private void startRenderer() {
        if (mRunning || mWidth <= 0 || mHeight <= 0) {
            return;
        }
        mRunning = true;
        mThread = new Thread(this, "GrassVKPreviewThread");
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
