package com.reandroid.wallpaper.nightsky;

import android.os.SystemClock;
import android.view.MotionEvent;

final class NightSkyTouchTimeController {
    private static final long LONG_PRESS_MS = 350L;
    private static final float TIME_SCALE_NORMAL = 1.0f;
    private static final float DEFAULT_TIME_SCALE_ACCEL = 360.0f;

    private boolean pressing;
    private boolean autoAccelerating;
    private float accelerationScale = DEFAULT_TIME_SCALE_ACCEL;
    private long pressDownUptimeMs;
    private boolean accelerating;
    private long acceleratingSimElapsedMs;
    private long acceleratingRealElapsedMs;

    private long lastRealClockMs;
    private long astronomyTimeMs;

    static final class Step {
        final long simDeltaMs;
        final float timeScale;

        Step(long simDeltaMs, float timeScale) {
            this.simDeltaMs = simDeltaMs;
            this.timeScale = timeScale;
        }
    }

    void init() {
        astronomyTimeMs = System.currentTimeMillis();
        lastRealClockMs = astronomyTimeMs;
    }

    void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressing = true;
            pressDownUptimeMs = SystemClock.uptimeMillis();
            if (!autoAccelerating) {
                accelerating = false;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pressing = false;
            if (!autoAccelerating) {
                accelerating = false;
            }
        }
    }

    void setAutoAccelerating(boolean enabled) {
        autoAccelerating = enabled;
        if (enabled) {
            accelerating = true;
        } else if (!pressing) {
            accelerating = false;
        }
    }

    void setAccelerationScale(float scale) {
        accelerationScale = Math.max(1.0f, scale);
    }

    Step advance() {
        long nowUptime = SystemClock.uptimeMillis();
        long nowReal = System.currentTimeMillis();

        if (lastRealClockMs == 0L) {
            lastRealClockMs = nowReal;
        }

        if (!autoAccelerating && pressing && !accelerating && (nowUptime - pressDownUptimeMs) >= LONG_PRESS_MS) {
            accelerating = true;
        }
        if (!autoAccelerating && !pressing) {
            accelerating = false;
        }

        long realDelta = Math.max(0L, nowReal - lastRealClockMs);
        float timeScale = accelerating ? accelerationScale : TIME_SCALE_NORMAL;
        long simDelta = (long) (realDelta * timeScale);
        astronomyTimeMs += simDelta;
        if (accelerating) {
            acceleratingSimElapsedMs += simDelta;
            acceleratingRealElapsedMs += realDelta;
        } else {
            long decayDelta = (long) (realDelta * accelerationScale);
            acceleratingSimElapsedMs = Math.max(0L, acceleratingSimElapsedMs - decayDelta);
            long realDecay = realDelta * 2L;
            acceleratingRealElapsedMs = Math.max(0L, acceleratingRealElapsedMs - realDecay);
        }
        lastRealClockMs = nowReal;

        return new Step(simDelta, timeScale);
    }

    boolean isAccelerating() {
        return accelerating;
    }

    float getCurrentTimeScale() {
        return accelerating ? accelerationScale : TIME_SCALE_NORMAL;
    }

    long getAstronomyTimeMs() {
        return astronomyTimeMs;
    }

    long getAcceleratingSimElapsedMs() {
        return acceleratingSimElapsedMs;
    }

    long getAcceleratingRealElapsedMs() {
        return acceleratingRealElapsedMs;
    }
}
