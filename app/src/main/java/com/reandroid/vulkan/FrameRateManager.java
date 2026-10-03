package com.reandroid.vulkan;

import android.os.SystemClock;
import android.util.Log;

import com.reandroid.settings.FrameRatePolicy;
import com.reandroid.settings.WallpaperSettings;

/**
 * 共享帧率控制与诊断逻辑，消除 VKSurfaceView 和 VKWallpaperEngine 之间的重复代码。
 */
public final class FrameRateManager {
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;

    private final String mLogTag;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mVsyncPaced;
    private boolean mAnrDiagEnabled;
    private long mLastPerfSyncMs;
    private long mDiagFrameCount;
    private long mDiagAccumulatedMs;
    private long mDiagMaxMs;
    private long mDiagIntervalSumMs;
    private long mDiagIntervalCount;
    private long mDiagMaxIntervalMs;
    private long mLastRecordMs;

    public FrameRateManager(String logTag) {
        mLogTag = logTag;
    }

    public long getTargetFrameMs() {
        return mTargetFrameMs;
    }

    /** 当前目标帧率（用于判断"变了没有"，好决定要不要重新下发 surface 提示）。 */
    public int getTargetFps() {
        return mTargetFps;
    }

    /**
     * 告诉系统这个 surface 打算跑多少帧（API 30+）。
     *
     * <p>这是给平台的**提示**，让它在可变刷新率的屏幕上挑一个合适的显示模式；它自己不丢帧，
     * 所以和循环那套 swap / sleep 配速并不冲突。必须从渲染线程调 —— 那个线程才持有当前
     * 有效的 surface。
     */
    public static void applySurfaceFrameRateHint(android.view.Surface surface, int fps) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;
        if (surface == null || !surface.isValid()) return;
        try {
            surface.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (Throwable ignored) {
            // 纯提示，失败不影响渲染
        }
    }

    /**
     * 呈现路径本身已经在配速 —— 调用方补 sleep 时下限放宽到 0（不是 1ms）。
     *
     * <p>见 {@code FrameRatePolicy}：实测的帧耗时里已经含了等垂直同步的时间，再补一个
     * {@code max(1, 帧时 − 耗时)} 会顶过 vsync 那条线，把 120 帧直接砍成 60。
     * <b>但下限是 0 不等于不睡</b>：surface 没被合成时 vsync 不阻塞，不睡就没有上限。
     * 所以别自己写这个分支，用 {@link #pacingSleepMs(long)}。
     */
    public boolean isVsyncPaced() {
        return mVsyncPaced;
    }

    /** 这一帧该睡多久。公式在 {@link FrameRatePolicy#pacingSleepMs} 里，这里只是带上本实例的状态。 */
    public long pacingSleepMs(long frameCostMs) {
        return FrameRatePolicy.pacingSleepMs(mTargetFrameMs, mVsyncPaced, frameCostMs);
    }

    /** 每帧开始时调用，更新 FPS 设置 */
    public void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) return;
        mLastPerfSyncMs = nowMs;
        FrameRatePolicy.Decision decision = WallpaperSettings.resolveFrameRateDecision(30);
        mTargetFps = Math.max(1, decision.fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mVsyncPaced = decision.vsyncPaced;
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    /**
     * 每帧结束时调用，记录帧耗时与**帧间隔**并定期输出统计。
     *
     * <p>为什么要单独记帧间隔：{@code frameCostMs} 是"draw + 呈现"的耗时，**不是帧率** ——
     * 它不含 sleep。要验"真的跑到了设定帧数"必须看相邻两帧起点的时间差。
     * （开发者选项那个"显示刷新率"浮层也不行：它反映的是**屏幕**的模式，壁纸只跑 40 帧
     * 它也照样显示 120。）与 GLES 侧同一套口径，两边日志里都 grep {@code FrameStats}。
     */
    public void recordFrameCost(long frameCostMs) {
        long now = SystemClock.uptimeMillis();
        long interval = mLastRecordMs == 0L ? 0L : now - mLastRecordMs;
        mLastRecordMs = now;

        if (!mAnrDiagEnabled) return;
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(mLogTag, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) mDiagMaxMs = frameCostMs;
        if (interval > 0L) {
            mDiagIntervalSumMs += interval;
            mDiagIntervalCount++;
            if (interval > mDiagMaxIntervalMs) mDiagMaxIntervalMs = interval;
        }
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            long avgInterval =
                    mDiagIntervalCount > 0L ? mDiagIntervalSumMs / mDiagIntervalCount : 0L;
            Log.i(
                    mLogTag,
                    "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fps="
                            + (avgInterval > 0L ? 1000L / avgInterval : 0L) + " target="
                            + mTargetFps + " vsyncPaced=" + mVsyncPaced);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
            mDiagIntervalSumMs = 0L;
            mDiagIntervalCount = 0L;
            mDiagMaxIntervalMs = 0L;
        }
    }
}
