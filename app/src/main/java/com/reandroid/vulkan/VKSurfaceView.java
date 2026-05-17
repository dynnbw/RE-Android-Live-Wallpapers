package com.reandroid.vulkan;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.reandroid.settings.WallpaperSettings;

/**
 * Vulkan 预览 SurfaceView 共享基类，封装线程管理、Surface 生命周期和帧率诊断。
 * 子类通过模板方法注入壁纸特定的 Scene 创建、纹理上传和渲染调用。
 *
 * @param <T> Scene 类型
 */
public abstract class VKSurfaceView<T> extends SurfaceView
        implements SurfaceHolder.Callback, Runnable {

    protected static final long PERF_SYNC_INTERVAL_MS = 1000L;
    protected static final long ANR_FRAME_THRESHOLD_MS = 200L;

    protected Thread mThread;
    protected volatile boolean mRunning;
    protected long mRendererHandle;
    protected T mScene;
    protected int mWidth, mHeight;

    // 帧率控制
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled;
    private long mLastPerfSyncMs;
    private long mDiagFrameCount;
    private long mDiagAccumulatedMs;
    private long mDiagMaxMs;

    // ---- 构造器 ----

    protected VKSurfaceView(Context context) {
        super(context);
        init();
    }

    protected VKSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ensureScene();
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid() && mWidth > 0 && mHeight > 0) {
            onSurfaceCreatedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width;
        mHeight = height;
        ensureScene();
        onSceneResize(width, height);
        ensureRenderer();
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            onSurfaceChangedNative(surface);
        }
        startRenderer();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
        }
    }

    // ---- 公共生命周期 ----

    public void resumeRenderer() {
        startRenderer();
    }

    public void pauseRenderer() {
        stopRenderer();
    }

    public void releaseRenderer() {
        stopRenderer();
        if (mRendererHandle != 0L) {
            onSurfaceDestroyedNative();
            destroyRenderer();
            mRendererHandle = 0L;
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
        if (mRunning || mWidth <= 0 || mHeight <= 0) return;
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

    /** 创建壁纸 Scene 实例 */
    protected abstract void ensureScene();

    /** Scene resize（如果 Scene 支持） */
    protected void onSceneResize(int width, int height) {}

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

    /** 运行时纹理热切换（每帧检查） */
    protected void syncTexturesIfNeeded() {}

    /** 执行 Scene.update + VKNative.nRenderFrame */
    protected abstract void renderFrame();

    /** 渲染线程名称 */
    protected abstract String getThreadName();

    /** 日志 TAG */
    protected abstract String getLogTag();
}
