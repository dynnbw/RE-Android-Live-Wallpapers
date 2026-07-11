package com.reandroid.vulkan;

import android.graphics.Rect;
import android.os.Process;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * Vulkan 壁纸 Engine 共享基类，封装线程管理、Surface 生命周期和帧率诊断。
 * 子类通过模板方法注入壁纸特定的 Scene 创建、纹理上传和渲染调用。
 *
 * @param <T> Scene 类型
 */
public abstract class VKWallpaperEngine<T> extends WallpaperService.Engine implements Runnable {

    protected Thread mThread;
    protected volatile boolean mRunning;
    protected boolean mVisible;
    protected SurfaceHolder mHolder;
    protected volatile T mScene;
    protected final Object mSceneLock = new Object();
    protected volatile long mRendererHandle;
    protected boolean mNativeSurfaceAlive;
    protected int mWidth, mHeight;

    // 共享帧率控制与诊断
    private final FrameRateManager mFrameRate = new FrameRateManager(getLogTag());

    protected VKWallpaperEngine(WallpaperService service) {
        service.super();
    }

    // ---- Engine 生命周期 ----

    @Override
    public void onDestroy() {
        stopRenderer();
        if (mRendererHandle != 0L) {
            if (mNativeSurfaceAlive) {
                onSurfaceDestroyedNative();
                mNativeSurfaceAlive = false;
            }
            destroyRenderer();
            mRendererHandle = 0L;
        }
        super.onDestroy();
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {
        super.onSurfaceCreated(holder);
        setTouchEventsEnabled(true);
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
            mNativeSurfaceAlive = true;
            onSurfaceCreatedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.onSurfaceChanged(holder, format, width, height);
        mHolder = holder;
        mWidth = width;
        mHeight = height;
        synchronized (mSceneLock) {
            ensureOrResizeScene();
        }
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            mNativeSurfaceAlive = true;
            onSurfaceChangedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mNativeSurfaceAlive && mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
            mNativeSurfaceAlive = false;
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
        synchronized (mSceneLock) {
            if (mScene != null) {
                onSceneOffset(xOffset);
            }
        }
        if (mVisible) {
            startRenderer();
        }
    }

    // ---- 渲染线程 ----

    @Override
    public void run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        while (mRunning) {
            long frameStart = SystemClock.uptimeMillis();
            mFrameRate.syncPerfSettingsIfNeeded(frameStart);

            if (mRendererHandle != 0L && mScene != null) {
                synchronized (mSceneLock) {
                    syncTexturesIfNeeded();
                    renderFrame();
                }
            }

            long frameCost = SystemClock.uptimeMillis() - frameStart;
            mFrameRate.recordFrameCost(frameCost);

            try {
                long sleepMs = Math.max(1L, mFrameRate.getTargetFrameMs() - frameCost);
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    protected void startRenderer() {
        if (mRunning || mWidth <= 0 || mHeight <= 0 || mHolder == null) return;
        Surface surface = mHolder.getSurface();
        if (surface == null || !surface.isValid()) return;
        mRunning = true;
        mThread = new Thread(this, getThreadName());
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
            if (mThread.isAlive()) {
                android.util.Log.e(getLogTag(), "Render thread did not exit within 2s");
            }
            mThread = null;
        }
    }

    // ---- 模板方法：子类实现 ----

    /** 首次创建 Scene */
    protected abstract void ensureScene();

    /** Scene resize 或首次创建 */
    protected abstract void ensureOrResizeScene();

    /** 创建 Vulkan 渲染器并上传纹理 */
    protected abstract void ensureRenderer();

    /** 销毁 Vulkan 渲染器 */
    protected abstract void destroyRenderer();

    /** 调用 VKNative.nOnSurfaceCreated */
    protected abstract void onSurfaceCreatedNative(Surface surface);

    /** 调用 VKNative.nOnSurfaceChanged */
    protected abstract void onSurfaceChangedNative(Surface surface);

    /** 调用 VKNative.nOnSurfaceDestroyed */
    protected abstract void onSurfaceDestroyedNative();

    /** offset 变化回调（默认：Scene.setOffset） */
    protected void onSceneOffset(float xOffset) {}

    /** 运行时纹理热切换（每帧检查） */
    protected void syncTexturesIfNeeded() {}

    /** 执行 Scene.update + VKNative.nRenderFrame */
    protected abstract void renderFrame();

    /** 渲染线程名称 */
    protected abstract String getThreadName();

    /** 日志 TAG */
    protected abstract String getLogTag();
}
