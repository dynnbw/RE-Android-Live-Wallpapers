package com.reandroid.wallpaper.grass;

import java.util.Random;

import static com.reandroid.wallpaper.grass.GrassConstants.*;

final class GrassParticleSystem {

    private GrassParticleSystem() {
    }

    static Dandelion[] initDandelions(Random random, int count, int width, int height) {
        Dandelion[] dandelions = new Dandelion[count];
        for (int i = 0; i < count; i++) {
            Dandelion d = new Dandelion();
            resetDandelion(random, d, true, width, height);
            dandelions[i] = d;
        }
        return dandelions;
    }

    static void resetDandelion(Random random, Dandelion d, boolean randomX, int width, int height) {
        float size = random(random, 32.0f, 72.0f) * DANDELION_SIZE_SCALE;
        d.size = size;
        d.y = random(random, 0.1f * height, 0.9f * height);
        d.speed = random(random, 20.0f, 60.0f);
        d.swayPhase = random(random, 0.0f, 6.28318f);
        d.swaySpeed = random(random, 0.6f, 1.4f);
        d.rotationDeg = random(random, -15.0f, 15.0f);
        if (randomX) d.x = random(random, -width, 0.0f) - size;
        else d.x = -size - random(random, 0.0f, width * 0.2f);
    }

    static Firefly[] initFireflies(Random random, int count, int width, int height) {
        Firefly[] fireflies = new Firefly[count];
        for (int i = 0; i < count; i++) {
            Firefly f = new Firefly();
            f.x = random(random, 0.0f, width);
            f.y = random(random, 0.0f, height);
            f.vx = random(random, -10.0f, 10.0f);
            f.vy = random(random, -8.0f, 8.0f);
            f.size = random(random, 6.0f, 12.0f) * FIREFLY_SIZE_SCALE;
            f.phase = random(random, 0.0f, 6.28318f);
            f.flickerSpeed = random(random, 0.8f, 1.6f);
            fireflies[i] = f;
        }
        return fireflies;
    }

    static void updateDandelionPositions(Random random, Dandelion[] dandelions,
            float dt, float dandelionSpeedScale, int width, int height) {
        if (dandelions == null) return;
        for (Dandelion d : dandelions) {
            d.x += d.speed * DANDELION_SPEED_SCALE * dandelionSpeedScale * dt;
            if (d.x > width + d.size) {
                resetDandelion(random, d, false, width, height);
            }
        }
    }

    static void updateFireflyPositions(Firefly[] fireflies, float dt, int width, int height) {
        if (fireflies == null) return;
        for (Firefly f : fireflies) {
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            if (f.x < 0) f.x = width;
            if (f.x > width) f.x = 0;
            if (f.y < 0) f.y = height;
            if (f.y > height) f.y = 0;
        }
    }

    static LegacyParticle createLegacyParticle(Random random, long legacyNow, int legacyDirection,
            int type, int width, int height) {
        LegacyParticle p = new LegacyParticle();
        p.type = type;
        p.startTime = legacyNow + (long) (Math.random() * LEGACY_MAX_DELAY);
        p.silentEndTime = legacyNow
            + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE)
            * LEGACY_MAX_INTERVAL);
        p.flareEndTime = legacyNow;
        p.stayEndTime = legacyNow;
        p.texture = (type == LEGACY_TYPE_DANDELION) ? 0 : 1;
        p.bladeNum = -1;
        p.sizeNum = -1;
        p.active = true;
        if (type == LEGACY_TYPE_DANDELION) {
            flyLegacyDandelion(random, p, true, legacyNow, legacyDirection, width, height);
            p.angle = (float) (Math.random() * 60.0 - 30.0);
        } else {
            flyLegacyFirefly(random, p, true, legacyNow, legacyDirection, width, height);
        }
        return p;
    }

    static void flyLegacyFirefly(Random random, LegacyParticle p, boolean isInit,
            long legacyNow, int legacyDirection, int width, int height) {
        long delta = legacyNow - p.startTime;
        if (isInit || legacyNow >= p.velocityRetargetTime) {
            if (Math.random() > 0.5) {
                p.dx = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            } else {
                p.dx = (float) ((1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            }
            p.dy = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            p.velocityRetargetTime = legacyNow + LEGACY_VECTOR_MIN_INTERVAL_MS
                    + (long) (Math.random() * (LEGACY_VECTOR_MAX_INTERVAL_MS - LEGACY_VECTOR_MIN_INTERVAL_MS));
        }
        if (isInit) {
            p.originX = (float) (Math.random() * width * 2);
            p.originY = height;
        } else {
            p.originX += p.dx * LEGACY_SPEED * delta;
            p.originY += p.dy * LEGACY_SPEED * LEGACY_VERTICAL_MOTION_SCALE * delta;
        }
    }

    static void flyLegacyDandelion(Random random, LegacyParticle p, boolean isInit,
            long legacyNow, int legacyDirection, int width, int height) {
        long delta = legacyNow - p.startTime;
        if (isInit || legacyNow >= p.velocityRetargetTime) {
            if (Math.random() > 0.5) {
                p.dy = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            } else {
                p.dy = (float) ((1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            }
            if (legacyDirection == 0) {
                p.dx = (float) (1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE);
            } else {
                p.dx = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
            }
            p.velocityRetargetTime = legacyNow + LEGACY_VECTOR_MIN_INTERVAL_MS
                    + (long) (Math.random() * (LEGACY_VECTOR_MAX_INTERVAL_MS - LEGACY_VECTOR_MIN_INTERVAL_MS));
        }
        if (isInit) {
            if (legacyDirection == 0) {
                p.originX = 5.0f;
                p.originY = (float) (Math.random() * height);
            } else if (legacyDirection == 1) {
                p.originX = width * 2.0f;
                p.originY = (float) (Math.random() * height);
            } else {
                p.originX = 0.0f;
                p.originY = (float) (Math.random() * height);
            }
        } else {
            p.originX += p.dx * LEGACY_SPEED * delta;
            p.originY += p.dy * LEGACY_SPEED * LEGACY_VERTICAL_MOTION_SCALE * delta;
        }
    }

    private static float random(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }
}
