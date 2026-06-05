package com.reandroid.wallpaper.bluesea;

import android.view.MotionEvent;

import java.util.Random;

/**
 * BlueSea physics/logic simulation -- pure Java, no GL dependencies.
 * Holds all jelly / particle state and exposes it to BlueSeaGL via
 * package-private fields and methods.
 */
final class BlueSeaScene {
    private static final int DESIGN_WIDTH = 480;
    private static final int DESIGN_HEIGHT = 800;

    private static final int PANE_COUNT = 5;
    private static final int JELLY_COUNT = 20;

    private static final long GLOW_DURATION_MS = 550L;

    private static final int[] JELLY_X = {
        200, 300, 20, 120, 140, 240, 60, 160, 80, 380,
        300, 400, 70, 170, 40, 340, 100, 250, 120, 220
    };

    private static final int[] JELLY_Y = {
        600, 100, 400, 600, 300, 500, 200, 500, 400, 300,
        400, 600, 300, 500, 100, 500, 500, 200, 500, 100
    };

    private static final int[] JELLY_PANE_OFFSET = {
        0, 0, 1, 1, 2, 2, 3, 3, 4, 4,
        0, 0, 1, 1, 2, 2, 0, 0, 1, 1
    };

    private static final JellyConfig[] JELLY_CONFIGS = new JellyConfig[] {
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 180, 1000, 5.8f, 9.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 162, 1300, 7.7f, 5.6f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 170, 1500, 6.5f, 7.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 200, 1100, 8.2f, 7.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 180, 1200, 5.2f, 9.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 160, 1600, 7.9f, 5.8f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 200, 1000, 5.8f, 9.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 160, 1300, 7.7f, 5.6f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1.png", "bluesea/drawable/bluesea_bubble_press.png", 170, 1500, 7.5f, 6.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4.png", "bluesea/drawable/bluesea_bubble_press.png", 190, 1100, 8.4f, 7.2f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1200, 8.2f, 5.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 140, 1600, 7.9f, 8.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 140, 1800, 6.2f, 5.4f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1600, 5.7f, 8.2f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 130, 1200, 8.3f, 6.9f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur1.png", "bluesea/drawable/bluesea_bubble_press_blur1.png", 150, 1500, 6.2f, 7.5f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 120, 1300, 7.1f, 5.8f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 110, 1700, 5.9f, 7.1f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_1_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 100, 1100, 8.0f, 6.7f),
        new JellyConfig("bluesea/drawable/bluesea_bubble_4_blur2.png", "bluesea/drawable/bluesea_bubble_press_blur2.png", 120, 1000, 6.8f, 5.8f)
    };

    private static final int PARTICLE_COUNT = 40;
    private static final long JELLY_TURN_INTERVAL_MIN_MS = 700L;
    private static final long JELLY_TURN_INTERVAL_MAX_MS = 1800L;
    private static final float JELLY_SPEED_MIN = 18.0f;
    private static final float JELLY_SPEED_MAX = 46.0f;

    // --- State ---

    final Random mRandom = new Random();

    final JellyState[] mJellies;
    final Particle[] mParticles;
    Texture mBackground;
    Texture mParticle;

    float mXOffset;
    float mScaleX = 1.0f;
    float mScaleY = 1.0f;
    float mScale = 1.0f;
    int mWidth;
    int mHeight;
    long mLastTimeMs;

    BlueSeaScene(int width, int height) {
        mJellies = new JellyState[JELLY_COUNT];
        mParticles = new Particle[PARTICLE_COUNT];
        resize(width, height);
        initSimulation();
    }

    // --- Public / package-private API called by GL ---

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mScaleX = width / (float) DESIGN_WIDTH;
        mScaleY = height / (float) DESIGN_HEIGHT;
        mScale = (mScaleX + mScaleY) * 0.5f;
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    void onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            triggerNearestGlow(event.getX(), event.getY());
        }
    }

    void update(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
        }
        float dt = (timeMs - mLastTimeMs) * 0.001f;
        mLastTimeMs = timeMs;

        updateJellies(timeMs, dt);
        updateParticles(dt);
    }

    // --- Logic helpers called by GL draw methods ---

    int getJellyPlane(int index) {
        if (index >= 16) return 2;
        if (index >= 10) return 1;
        return 0;
    }

    float computeSwimScale(JellyState jelly, long timeMs) {
        float period = Math.max(300.0f, jelly.config.swimTimeMs);
        float phase = ((timeMs * 0.001f) / (period * 0.001f)) + jelly.swimPhase;
        return 1.0f + 0.1f * (float) Math.sin(phase * Math.PI * 2.0);
    }

    float computeDrift(float phaseOffset, float durationSeconds, long timeMs) {
        float driftSize = 20.0f * mScale;
        float period = Math.max(2.0f, durationSeconds);
        float phase = ((timeMs * 0.001f) / period) + phaseOffset;
        return (float) Math.sin(phase * Math.PI * 2.0) * driftSize;
    }

    float computeGlowAlpha(JellyState jelly, long timeMs) {
        if (jelly.glowStartMs == 0L) return 0.0f;
        float elapsed = (timeMs - jelly.glowStartMs) / (float) GLOW_DURATION_MS;
        if (elapsed >= 1.0f) return 0.0f;
        float value = 1.0f - Math.abs((elapsed * 2.0f) - 1.0f);
        return value * 0.8f;
    }

    // --- Touch handling ---

    void triggerNearestGlow(float x, float y) {
        float minDist = Float.MAX_VALUE;
        JellyState nearest = null;
        for (int i = 0; i < JELLY_COUNT; i++) {
            JellyState jelly = mJellies[i];
            float dx = jelly.x - x;
            float dy = jelly.y - y;
            float dist = dx * dx + dy * dy;
            if (dist < minDist) {
                minDist = dist;
                nearest = jelly;
            }
        }
        if (nearest != null) {
            nearest.glowStartMs = mLastTimeMs;
        }
    }

    // --- Simulation init and per-frame logic ---

    private void initSimulation() {
        for (int i = 0; i < JELLY_COUNT; i++) {
            JellyConfig config = JELLY_CONFIGS[i];
            JellyState jelly = new JellyState();
            jelly.config = config;
            jelly.x = JELLY_X[i] * mScaleX;
            jelly.y = JELLY_Y[i] * mScaleY;
            jelly.pane = JELLY_PANE_OFFSET[i];
            jelly.swimPhase = mRandom.nextFloat();
            jelly.driftPhaseX = mRandom.nextFloat();
            jelly.driftPhaseY = mRandom.nextFloat();
            resetJellyVelocity(jelly, 0L);
            mJellies[i] = jelly;
        }

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            mParticles[i] = createParticle();
        }
    }

    private void updateJellies(long timeMs, float dt) {
        for (JellyState jelly : mJellies) {
            if (timeMs >= jelly.nextTurnMs) {
                resetJellyVelocity(jelly, timeMs);
                jelly.glowStartMs = timeMs;
            }

            jelly.x += jelly.vx * dt;
            jelly.y += jelly.vy * dt;

            if (jelly.x < 0.0f) {
                jelly.x = 0.0f;
                jelly.vx = Math.abs(jelly.vx);
            } else if (jelly.x > mWidth) {
                jelly.x = mWidth;
                jelly.vx = -Math.abs(jelly.vx);
            }

            if (jelly.y < 0.0f) {
                jelly.y = 0.0f;
                jelly.vy = Math.abs(jelly.vy);
            } else if (jelly.y > mHeight) {
                jelly.y = mHeight;
                jelly.vy = -Math.abs(jelly.vy);
            }
        }
    }

    private void resetJellyVelocity(JellyState jelly, long timeMs) {
        float angle = mRandom.nextFloat() * (float) (Math.PI * 2.0);
        float speed = (JELLY_SPEED_MIN + mRandom.nextFloat() * (JELLY_SPEED_MAX - JELLY_SPEED_MIN)) * mScale;
        jelly.vx = (float) Math.cos(angle) * speed;
        jelly.vy = (float) Math.sin(angle) * speed;

        long interval = JELLY_TURN_INTERVAL_MIN_MS
            + mRandom.nextInt((int) (JELLY_TURN_INTERVAL_MAX_MS - JELLY_TURN_INTERVAL_MIN_MS + 1L));
        jelly.nextTurnMs = timeMs + interval;
    }

    private void updateParticles(float dt) {
        float totalWidth = mWidth * (float) PANE_COUNT;
        for (Particle particle : mParticles) {
            particle.y -= particle.speed * dt;
            if (particle.y < -particle.size) {
                particle.x = mRandom.nextFloat() * totalWidth;
                particle.y = mHeight + particle.size + mRandom.nextFloat() * mHeight;
                particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
                particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
                particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
            }
        }
    }

    private Particle createParticle() {
        Particle particle = new Particle();
        float totalWidth = mWidth * (float) PANE_COUNT;
        particle.x = mRandom.nextFloat() * totalWidth;
        particle.y = mRandom.nextFloat() * mHeight;
        particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
        particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
        particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
        return particle;
    }

    // --- Inner classes ---

    static final class JellyConfig {
        final String imageAsset;
        final String glowAsset;
        final float size;
        final float swimTimeMs;
        final float driftDurX;
        final float driftDurY;

        JellyConfig(String imageAsset, String glowAsset, float size, float swimTimeMs,
                float driftDurX, float driftDurY) {
            this.imageAsset = imageAsset;
            this.glowAsset = glowAsset;
            this.size = size;
            this.swimTimeMs = swimTimeMs;
            this.driftDurX = driftDurX;
            this.driftDurY = driftDurY;
        }
    }

    static final class JellyState {
        JellyConfig config;
        Texture image;
        Texture glow;
        float x;
        float y;
        float vx;
        float vy;
        int pane;
        float swimPhase;
        float driftPhaseX;
        float driftPhaseY;
        long nextTurnMs;
        long glowStartMs;
    }

    static final class Particle {
        float x;
        float y;
        float size;
        float speed;
        float alpha;
    }

    static final class Texture {
        int id;

        Texture(int id) {
            this.id = id;
        }
    }
}
