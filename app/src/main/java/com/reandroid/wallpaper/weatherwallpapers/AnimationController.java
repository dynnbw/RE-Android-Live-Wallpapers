package com.reandroid.wallpaper.weatherwallpapers;

public class AnimationController {
    private static final float DEFAULT_FRAME_DURATION_MS = 40.0f;

    private final int mMaxFrame;

    private int mFrameCount = 0;
    private float mFrameAccumulator = 0.0f;
    private long mLastTimeMs = 0L;
    private float mFrameDurationMs = DEFAULT_FRAME_DURATION_MS;

    public AnimationController(int maxFrame) {
        mMaxFrame = maxFrame;
    }

    public long updateDelta(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
            return 0L;
        }
        long deltaMs = timeMs - mLastTimeMs;
        mLastTimeMs = timeMs;
        return Math.max(0L, deltaMs);
    }

    public void setFrameDurationMs(float frameDurationMs) {
        if (frameDurationMs > 0.0f) {
            mFrameDurationMs = frameDurationMs;
        }
    }

    public boolean advanceFrame(long deltaMs) {
        mFrameAccumulator += deltaMs / mFrameDurationMs;
        int advance = (int) mFrameAccumulator;
        if (advance <= 0) {
            return false;
        }

        mFrameAccumulator -= advance;
        mFrameCount += advance;
        if (mFrameCount > mMaxFrame) {
            mFrameCount = 0;
        }
        return true;
    }

    public int getFrameCount() {
        return mFrameCount;
    }
}