package com.reandroid.plugin;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.settings.WallpaperSettings;
import com.reandroid.vulkan.FrameRateManager;

public abstract class BaseVKPluginEngine implements WallpaperEngine, Runnable {
    protected final Context mContext;
    protected final WallpaperPluginHost mHost;
    protected volatile long mRendererHandle;
    protected int mWidth = 256, mHeight = 256;
    protected volatile boolean mRunning;
    protected boolean mVisible;
    protected boolean mPreview;
    protected boolean mSurfaceCreated;
    protected Surface mCurrentSurface;
    protected SurfaceHolder mHolder;
    protected Thread mThread;
    private FrameRateManager mFrameRate;

    public BaseVKPluginEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
    }

    /** Lazy-accessed to avoid calling the overridable abstract {@link #getLogTag()}
     *  from the field initializer (constructor trap). */
    private FrameRateManager frameRate() {
        if (mFrameRate == null) mFrameRate = new FrameRateManager(getLogTag());
        return mFrameRate;
    }

    // --- Abstract methods each VK wallpaper implements ---
    protected abstract String getLogTag();
    protected abstract void ensureScene();
    protected abstract void ensureOrResizeScene();
    protected abstract long createRenderer();
    protected abstract void destroyRenderer();
    protected abstract void onSurfaceCreatedNative(Surface surface, int w, int h);
    protected abstract void onSurfaceChangedNative(Surface surface, int w, int h);
    protected abstract void onSurfaceDestroyedNative();
    protected abstract void renderFrame();
    protected abstract void syncTexturesIfNeeded();
    protected abstract void onSceneOffset(float xOffset);
    protected abstract void onSceneTouch(float x, float y);

    // --- WallpaperEngine lifecycle ---
    @Override public void onCreate(SurfaceHolder holder) {
        mHolder = holder;
        WallpaperSettings.setSharedPreferences(mHost.getSharedPreferences());
    }
    @Override public void onDestroy() {
        stopRenderer(); releaseNative(); mSurfaceCreated = false;
        WallpaperSettings.clearSharedPreferences();
    }
    @Override public void onVisibilityChanged(boolean visible) {
        mVisible = visible; if (visible) startRenderer(); else stopRenderer();
    }
    @Override public void setPreview(boolean isPreview) { mPreview = isPreview; }
    protected boolean isPreview() { return mPreview; }
    @Override public void onOffsetsChanged(float xo, float yo, float xs, float ys, int xp, int yp) {
        onSceneOffset(xo);
    }
    @Override public void onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) onSceneTouch(e.getX(), e.getY());
    }
    @Override public void onCommand(String action, int x, int y, int z, Bundle extras) {}
    @Override public void drawFrame(long timeMs) {} // no-op: VK renders on its own thread
    @Override public void release() { stopRenderer(); releaseNative(); }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.d(getLogTag(), "onSurfaceChanged: " + w + "x" + h
                + " surfCreated=" + mSurfaceCreated + " oldSize=" + mWidth + "x" + mHeight
                + " wasRunning=" + (mThread != null));

        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;

        // Skip if nothing actually changed (now includes Surface identity —
        // a new Surface at the same dimensions still requires a full recreate).
        if (mSurfaceCreated && mCurrentSurface == surface && mWidth == w && mHeight == h) {
            Log.d(getLogTag(), "onSurfaceChanged: skipped (nothing changed)");
            return;
        }

        // Full destroy + recreate to avoid native swapchain-recreation bugs
        boolean wasRunning = (mThread != null);
        if (wasRunning) stopRenderer();
        if (mSurfaceCreated) {
            releaseNative();
            mSurfaceCreated = false;
        }

        mHolder = holder; mWidth = w; mHeight = h;
        ensureOrResizeScene(); ensureRenderer();
        mSurfaceCreated = true;
        mCurrentSurface = surface;
        onSurfaceCreatedNative(surface, w, h);
        if (wasRunning) startRenderer();
    }

    // --- Render thread ---
    protected void startRenderer() {
        if (mThread != null || !mVisible) return;
        Surface s = mHolder != null ? mHolder.getSurface() : null;
        if (s == null || !s.isValid()) return;
        mRunning = true;
        mThread = new Thread(this, getLogTag() + "Thread");
        mThread.start();
    }
    protected void stopRenderer() {
        mRunning = false;
        if (mThread != null) {
            long deadline = System.currentTimeMillis() + 2000L;
            while (mThread.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    mThread.join(Math.max(1L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (mThread.isAlive()) Log.e(getLogTag(), "Render thread did not exit within 2s");
            mThread = null;
        }
    }
    @Override public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        while (mRunning) {
            long now = SystemClock.uptimeMillis();
            frameRate().syncPerfSettingsIfNeeded(now);
            syncTexturesIfNeeded();
            renderFrame();
            long cost = SystemClock.uptimeMillis() - now;
            frameRate().recordFrameCost(cost);
            long sleep = Math.max(1, frameRate().getTargetFrameMs() - cost);
            try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
        }
    }

    // --- Native lifecycle ---
    protected void ensureRenderer() {
        if (mRendererHandle == 0L) {
            ensureScene();
            mRendererHandle = createRenderer();
        }
    }
    protected void releaseNative() {
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
            destroyRenderer();
            mRendererHandle = 0L;
        }
    }
}
