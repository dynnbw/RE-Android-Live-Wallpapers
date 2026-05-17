package com.reandroid.vulkan;

import android.graphics.Rect;
import android.os.Process;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.settings.WallpaperSettings;

/**
 * Vulkan 壁纸 Engine 共享基类，封装线程管理、Surface 生命周期和帧率诊断。
 * 子类通过模板方法注入壁纸特定的 Scene 创建、纹理上传和渲染调用。
 *
 * @param <T> Scene 类型
 */
public abstract class VKWallpaperEngine<T> extends WallpaperService.Engine implements Runnable {

    protected static final long PERF_SYNC_INTERVAL_MS = 1000L;
    protected static final long ANR_FRAME_THRESHOLD_MS = 200L;

    protected Thread mThread;
    protected volatile boolean mRunning;
    protected boolean mVisible;
    protected SurfaceHolder mHolder;
    protected T mScene;
    protected long mRendererHandle;
    protected int mWidth, mHeight;

    // 帧率控制
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled;
    private long mLastPerfSyncMs;
    private long mDiagFrameCount;
    private long mDiagAccumulatedMs;
    private long mDiagMaxMs;

    protected VKWallpaperEngine(WallpaperService service) {
        service.super();
    }

    // ---- Engine 生命周期 ----

    @Override
    public void onDestroy() {
        stopRenderer();
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
            destroyRenderer();
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
        ensureOrResizeScene();
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            onSurfaceChangedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
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
            onSceneOffset(xOffset);
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
            syncPerfSettingsIfNeeded(frameStart);

            if (mRendererHandle != 0L && mScene != null) {
                syncTexturesIfNeeded();
                renderFrame();
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
            try {
                mThread.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mThread = null;
        }
    }

    // ---- 帧率诊断 ----

    private void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) return;
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    private void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) return;
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(getLogTag(), "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) mDiagMaxMs = frameCostMs;
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(getLogTag(), "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
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
