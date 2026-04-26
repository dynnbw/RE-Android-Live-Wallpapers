package com.reandroid.wallpaper.nexus;

import java.util.Random;

class NexusPulseController {
    static final int PULSE_NORMAL = 0;
    static final int PULSE_EXTRA = 1;

    static final class Pulse {
        int pulseType;
        float originX;
        float originY;
        int color;
        int startTime;
        float dx;
        float dy;
        float scale;
        int active;
    }

    private final Random random = new Random();
    private Pulse[] pulses;
    private Pulse[] extras;

    void ensureCapacity(int maxPulses, int maxExtras) {
        if (pulses == null || pulses.length != maxPulses) {
            pulses = new Pulse[maxPulses];
        }
        if (extras == null || extras.length != maxExtras) {
            extras = new Pulse[maxExtras];
        }
    }

    Pulse[] getPulses() {
        return pulses;
    }

    Pulse[] getExtras() {
        return extras;
    }

    void initPulses(int maxPulses,
                    int maxExtras,
                    int width,
                    int height,
                    int pulseSize,
                    float speedDeltaMin,
                    float speedDeltaMax,
                    int maxDelay,
                    long nowMs) {
        ensureCapacity(maxPulses, maxExtras);

        for (int i = 0; i < maxPulses; i++) {
            pulses[i] = new Pulse();
            resetPulse(pulses[i], PULSE_NORMAL, width, height, pulseSize, speedDeltaMin, speedDeltaMax, maxDelay, nowMs);
        }

        for (int i = 0; i < maxExtras; i++) {
            extras[i] = new Pulse();
            extras[i].pulseType = PULSE_EXTRA;
            extras[i].active = 0;
        }
    }

    void resetPulse(Pulse pulse,
                    int pulseType,
                    int width,
                    int height,
                    int pulseSize,
                    float speedDeltaMin,
                    float speedDeltaMax,
                    int maxDelay,
                    long nowMs) {
        float scale = rand(speedDeltaMin, speedDeltaMax);
        pulse.scale = scale;

        if (rand(1f) > 0.5f) {
            pulse.originX = rand(width * 2f / pulseSize) * pulseSize;
            pulse.dx = 0;
            if (rand(1f) > 0.5f) {
                pulse.originY = 0;
                pulse.dy = scale;
            } else {
                pulse.originY = height / scale;
                pulse.dy = -scale;
            }
        } else {
            pulse.originY = rand(height / (float) pulseSize) * pulseSize;
            pulse.dy = 0;
            if (rand(1f) > 0.5f) {
                pulse.originX = 0;
                pulse.dx = scale;
            } else {
                pulse.originX = width * 2f / scale;
                pulse.dx = -scale;
            }
        }

        pulse.startTime = (int) nowMs + (int) rand(maxDelay);
        pulse.color = (int) rand(7);
        pulse.pulseType = pulseType;
        pulse.active = pulseType == PULSE_EXTRA ? 0 : 1;
    }

    void addTap(int x, int y, int maxExtras, int pulseSize, long nowMs) {
        if (extras == null || pulseSize <= 0 || maxExtras <= 0) {
            return;
        }

        int count = 0;
        int color = (int) rand(4);
        float scale = rand(0.9f, 1.9f);

        x = (x / pulseSize) * pulseSize;
        y = (y / pulseSize) * pulseSize;

        for (int i = 0; i < maxExtras; i++) {
            Pulse p = extras[i];
            if (p == null) {
                p = new Pulse();
                p.pulseType = PULSE_EXTRA;
                extras[i] = p;
            }
            if (p.active == 0) {
                p.originX = x / scale;
                p.originY = y / scale;
                p.scale = scale;

                if (count == 0) {
                    p.dx = scale;
                    p.dy = 0.0f;
                } else if (count == 1) {
                    p.dx = -scale;
                    p.dy = 0.0f;
                } else if (count == 2) {
                    p.dx = 0.0f;
                    p.dy = scale;
                } else if (count == 3) {
                    p.dx = 0.0f;
                    p.dy = -scale;
                }

                p.active = 1;
                p.color = color;
                color++;
                if (color >= 7) {
                    color = 0;
                }
                p.startTime = (int) nowMs;
                p.pulseType = PULSE_EXTRA;
                count++;
                if (count == 4) {
                    break;
                }
            }
        }
    }

    private float rand(float max) {
        return random.nextFloat() * max;
    }

    private float rand(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }
}
