package com.reandroid.vulkan;

import android.util.Log;

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
    private boolean mAnrDiagEnabled;
    private long mLastPerfSyncMs;
    private long mDiagFrameCount;
    private long mDiagAccumulatedMs;
    private long mDiagMaxMs;

    public FrameRateManager(String logTag) {
        mLogTag = logTag;
    }

    public long getTargetFrameMs() {
        return mTargetFrameMs;
    }

    /** 每帧开始时调用，更新 FPS 设置 */
    public void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) return;
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    /** 每帧结束时调用，记录帧耗时并定期输出统计 */
    public void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) return;
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(mLogTag, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) mDiagMaxMs = frameCostMs;
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(mLogTag, "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
        }
    }
}
