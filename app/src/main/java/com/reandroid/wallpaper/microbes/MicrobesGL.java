package com.reandroid.wallpaper.microbes;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.opengl.GLES20;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.R;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

public class MicrobesGL extends GLESScene {
    private static final int MICROBE_COUNT = 300;
    private static final int FOOD_COUNT = 140;
    private static final int DECOR_COUNT = 120;
    private static final int DEAD_COUNT = 600;

    private static final float INVALID_POS = -10000.0f;
    private static final float TOUCH_FORCE = 35.0f;
    private static final float TOUCH_DRAG_THRESHOLD_PX = 30.0f;
    private static final int TAP_SPAWN_FOOD_COUNT = 12;
    private static final float AUTO_FOOD_RESPAWN_PER_SEC = 12.0f;
    private static final float MOTION_INFLUENCE_RADIUS2 = 320.0f * 320.0f;
    private static final int MOTION_MAX_AFFECTED = 36;
    private static final float TAP_REPEL_RADIUS2 = 180.0f * 180.0f;
    private static final float TAP_REPEL_FORCE = 240.0f;
    private static final float MICROBE_INTERACT_DIST2 = 900.0f;
    private static final float FOOD_ATTRACT_DIST2 = 6400.0f;
    private static final float REPRODUCE_ENERGY = 1.2f;
    private static final float ENERGY_DECAY_RATE = 0.012f;
    private static final float DEATH_ENERGY_THRESHOLD = 0.005f;
    private static final float REPRODUCE_CHANCE_PER_SEC = 0.035f;
    private static final float MAX_SPEED = 220.0f;
    private static final float CLUSTER_RADIUS2 = 60.0f * 60.0f;
    private static final int CLUSTER_NEIGHBOR_THRESHOLD = 5;
    private static final float CLUSTER_RELEASE_SECONDS = 3.2f;
    private static final float DEAD_VISIBLE_AGE_MAX = 4.6f;
    private static final float DEAD_AGE_ADVANCE_PER_SEC = 0.18f;
    private static final int DEAD_INITIAL_SPAWN_COUNT = 180;
    private static final float DEAD_AMBIENT_SPAWN_PER_SEC = 4.0f;
    private static final long PREF_POLL_INTERVAL_MS = 1000L;
    private static final float VIEW_CENTER_CROP_ZOOM = 2.0f;
    private static final float VIEW_SCROLL_PARALLAX = 0.32f;
    private static final float ROAM_RETARGET_MIN_S = 2.0f;
    private static final float ROAM_RETARGET_MAX_S = 5.2f;
    private static final float ROAM_PUSH = 85.0f;
    private static final float WANDER_NOISE_PUSH = 18.0f;

    private final Random rng = new Random();

    private final float[] microbeX = new float[MICROBE_COUNT];
    private final float[] microbeY = new float[MICROBE_COUNT];
    private final float[] microbeVx = new float[MICROBE_COUNT];
    private final float[] microbeVy = new float[MICROBE_COUNT];
    private final float[] microbeAngle = new float[MICROBE_COUNT];
    private final float[] microbeSize = new float[MICROBE_COUNT];
    private final float[] microbeEnergy = new float[MICROBE_COUNT];
    private final float[] microbePulseTime = new float[MICROBE_COUNT];
    private final float[] microbeTargetX = new float[MICROBE_COUNT];
    private final float[] microbeTargetY = new float[MICROBE_COUNT];
    private final int[] microbeNeighborCount = new int[MICROBE_COUNT];
    private final float[] microbeClusterTime = new float[MICROBE_COUNT];
    private final float[] microbeRoamRetargetTimer = new float[MICROBE_COUNT];
    private final float[] microbeRoamPhase = new float[MICROBE_COUNT];
    private final float[] microbeR = new float[MICROBE_COUNT];
    private final float[] microbeG = new float[MICROBE_COUNT];
    private final float[] microbeB = new float[MICROBE_COUNT];

    private final float[] foodX = new float[FOOD_COUNT];
    private final float[] foodY = new float[FOOD_COUNT];
    private final float[] foodPhase = new float[FOOD_COUNT];
    private final float[] foodVx = new float[FOOD_COUNT];
    private final float[] foodVy = new float[FOOD_COUNT];

    private final float[] decorX = new float[DECOR_COUNT];
    private final float[] decorY = new float[DECOR_COUNT];
    private final float[] decorAngle = new float[DECOR_COUNT];
    private final float[] decorSize = new float[DECOR_COUNT];

    private final float[] deadX = new float[DEAD_COUNT];
    private final float[] deadY = new float[DEAD_COUNT];
    private final float[] deadDrift = new float[DEAD_COUNT];
    private final float[] deadAge = new float[DEAD_COUNT];
    private int deadWriteCursor = 0;

    private FloatBuffer microbePosBuffer;
    private FloatBuffer microbeMiscBuffer;
    private FloatBuffer microbeColorBuffer;
    private FloatBuffer foodPosBuffer;
    private FloatBuffer decorPosBuffer;
    private FloatBuffer deadPosBuffer;

    private int microbeProgram;
    private int foodProgram;
    private int deadProgram;
    private int decorProgram;

    private int microbeAPosition;
    private int microbeAMisc;
    private int microbeAColor;
    private int microbeUTrans;
    private int microbeUTime;

    private int foodAPosition;
    private int foodUTrans;
    private int foodUTime;

    private int deadAPos;
    private int deadUTrans;
    private int deadUTime;

    private int decorAPosition;
    private int decorUTrans;
    private int decorUTime;

    private float timeSec;
    private long lastFrameMs = -1L;

    private int scrollXPx;
    private float worldWidth;
    private float worldHeight;
    private float lifecycleSpeedScale = 1.0f;
    private float motionActivityScale = 1.0f;
    private boolean originalColorMode = true;
    private float foodRespawnAccumulator;
    private float deadAmbientAccumulator;
    private long lastPrefPollMs = Long.MIN_VALUE;

    private boolean touchActive;
    private float touchX;
    private float touchY;
    private float touchStartX;
    private float touchStartY;
    private boolean touchMoved;

    public MicrobesGL(int width, int height) {
        super(width, height);
    }

    @Override
    protected void onCreate() {
        ensureBuffers();
        initScene();
    }

    @Override
    public void release() {
        deleteProgram(microbeProgram);
        deleteProgram(foodProgram);
        deleteProgram(deadProgram);
        deleteProgram(decorProgram);
        microbeProgram = 0;
        foodProgram = 0;
        deadProgram = 0;
        decorProgram = 0;
        microbePosBuffer = null;
        microbeMiscBuffer = null;
        microbeColorBuffer = null;
        foodPosBuffer = null;
        decorPosBuffer = null;
        deadPosBuffer = null;
        lastFrameMs = -1L;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        worldWidth = Math.max(width, worldWidth);
        worldHeight = Math.max(1.0f, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        scrollXPx = xPixels;
        worldWidth = Math.max(worldWidth, mWidth + Math.abs(xPixels) + mWidth);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchActive = true;
            touchMoved = false;
            touchStartX = event.getX();
            touchStartY = event.getY();
            touchX = toWorldX(touchStartX);
            touchY = toWorldY(touchStartY);
            applyMotionTarget(touchX, touchY);
        } else if (action == MotionEvent.ACTION_MOVE) {
            touchX = toWorldX(event.getX());
            touchY = toWorldY(event.getY());
            if (!touchMoved) {
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                touchMoved = (dx * dx + dy * dy) > (TOUCH_DRAG_THRESHOLD_PX * TOUCH_DRAG_THRESHOLD_PX);
            }
            applyMotionTarget(touchX, touchY);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float upX = toWorldX(event.getX());
            float upY = toWorldY(event.getY());
            if (!touchMoved) {
                spawnFoodBurst(upX, upY);
                repelMicrobesOnTap(upX, upY);
            }
            touchActive = false;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        if (microbeProgram == 0) {
            initGL();
        }

        if (lastFrameMs < 0L) {
            lastFrameMs = timeMs;
        }
        float dt = Math.min(0.1f, Math.max(0.001f, (timeMs - lastFrameMs) / 1000.0f));
        lastFrameMs = timeMs;
        timeSec += dt;

        refreshRuntimeSettingsIfNeeded(timeMs);

        updateScene(dt);

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        float width = Math.max(1.0f, mWidth);
        float height = Math.max(1.0f, mHeight);
        float parallaxScrollX = scrollXPx * VIEW_SCROLL_PARALLAX;
        float sx = (2.0f * zoom) / width;
        float sy = (2.0f * zoom) / height;
        float tx = -zoom + (2.0f * zoom * parallaxScrollX) / width;
        float ty = -zoom;

        drawDecor(sx, sy, tx, ty);
        drawFood(sx, sy, tx, ty);
        drawDead(sx, sy, tx, ty);
        drawMicrobes(sx, sy, tx, ty);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void initScene() {
        worldWidth = Math.max(1.0f, mWidth * 2.0f);
        worldHeight = Math.max(1.0f, mHeight);

        for (int i = 0; i < MICROBE_COUNT; i++) {
            microbeX[i] = rand(0.0f, worldWidth);
            microbeY[i] = rand(0.0f, worldHeight);
            microbeVx[i] = rand(-40.0f, 40.0f);
            microbeVy[i] = rand(-40.0f, 40.0f);
            microbeAngle[i] = rand(0.0f, (float) (Math.PI * 2.0));
            microbeSize[i] = rand(0.55f, 1.20f);
            microbeEnergy[i] = rand(0.7f, 1.2f);
            microbePulseTime[i] = -1000.0f;
            microbeTargetX[i] = microbeX[i];
            microbeTargetY[i] = microbeY[i];
            microbeRoamRetargetTimer[i] = rand(0.0f, ROAM_RETARGET_MAX_S);
            microbeRoamPhase[i] = rand(0.0f, (float) (Math.PI * 2.0));
            assignMicrobeColor(i);
        }

        for (int i = 0; i < FOOD_COUNT; i++) {
            spawnFood(i);
        }

        for (int i = 0; i < DECOR_COUNT; i++) {
            decorX[i] = rand(0.0f, worldWidth);
            decorY[i] = rand(0.0f, worldHeight);
            decorAngle[i] = rand(0.0f, (float) (Math.PI * 2.0));
            decorSize[i] = rand(0.15f, 0.45f);
        }

        for (int i = 0; i < DEAD_COUNT; i++) {
            deadAge[i] = DEAD_VISIBLE_AGE_MAX;
            deadX[i] = INVALID_POS;
            deadY[i] = INVALID_POS;
            deadDrift[i] = 0.0f;
        }
        deadWriteCursor = 0;
        for (int i = 0; i < Math.min(DEAD_INITIAL_SPAWN_COUNT, DEAD_COUNT); i++) {
            emitAmbientDead(rand(0.0f, DEAD_VISIBLE_AGE_MAX * 0.45f));
        }
        foodRespawnAccumulator = 0.0f;
        deadAmbientAccumulator = 0.0f;
    }

    private void ensureBuffers() {
        microbePosBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        microbeMiscBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        microbeColorBuffer = newFloatBuffer(MICROBE_COUNT * 3);
        foodPosBuffer = newFloatBuffer(FOOD_COUNT * 3);
        decorPosBuffer = newFloatBuffer(DECOR_COUNT * 4);
        deadPosBuffer = newFloatBuffer(DEAD_COUNT * 4);
    }

    private void initGL() {
        Resources res = getResources();
        if (res == null) {
            return;
        }
        microbeProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_microbe_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_microbe_fs)
        );
        foodProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_food_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_food_fs)
        );
        deadProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_dead_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_dead_fs)
        );
        decorProgram = createProgram(
            RawResourceLoader.readRawText(res, R.raw.microbes_decor_vs),
            RawResourceLoader.readRawText(res, R.raw.microbes_decor_fs)
        );

        microbeAPosition = GLES20.glGetAttribLocation(microbeProgram, "aPosition");
        microbeAMisc = GLES20.glGetAttribLocation(microbeProgram, "miscInfo");
        microbeAColor = GLES20.glGetAttribLocation(microbeProgram, "aColor");
        microbeUTrans = GLES20.glGetUniformLocation(microbeProgram, "uTrans");
        microbeUTime = GLES20.glGetUniformLocation(microbeProgram, "time");

        foodAPosition = GLES20.glGetAttribLocation(foodProgram, "aPosition");
        foodUTrans = GLES20.glGetUniformLocation(foodProgram, "uTrans");
        foodUTime = GLES20.glGetUniformLocation(foodProgram, "time");

        deadAPos = GLES20.glGetAttribLocation(deadProgram, "pos");
        deadUTrans = GLES20.glGetUniformLocation(deadProgram, "uTrans");
        deadUTime = GLES20.glGetUniformLocation(deadProgram, "time");

        decorAPosition = GLES20.glGetAttribLocation(decorProgram, "aPosition");
        decorUTrans = GLES20.glGetUniformLocation(decorProgram, "uTrans");
        decorUTime = GLES20.glGetUniformLocation(decorProgram, "time");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
    }

    private void updateScene(float dt) {
        float lifeScale = lifecycleSpeedScale;
        float moveScale = motionActivityScale;
        float roamPush = ROAM_PUSH * moveScale;
        float wanderPush = WANDER_NOISE_PUSH * moveScale;
        float targetDrivePush = (35.0f + 70.0f * moveScale) * moveScale;
        float noisePush = 32.0f * (0.55f + 0.45f * moveScale);
        float roamMin = Math.max(0.6f, ROAM_RETARGET_MIN_S / Math.max(0.5f, moveScale));
        float roamMax = Math.max(roamMin + 0.3f, ROAM_RETARGET_MAX_S / Math.max(0.5f, moveScale));
        for (int i = 0; i < MICROBE_COUNT; i++) {
            microbeNeighborCount[i] = 0;

            microbeRoamRetargetTimer[i] -= dt;
            if (microbeRoamRetargetTimer[i] <= 0.0f) {
                microbeTargetX[i] = microbeX[i] + rand(-220.0f, 220.0f);
                microbeTargetY[i] = microbeY[i] + rand(-180.0f, 180.0f);
                microbeRoamRetargetTimer[i] = rand(roamMin, roamMax);
            }

            float dxTarget = microbeTargetX[i] - microbeX[i];
            float dyTarget = microbeTargetY[i] - microbeY[i];
            float dTarget2 = dxTarget * dxTarget + dyTarget * dyTarget;
            if (dTarget2 > 1.0f) {
                float inv = invSqrt(dTarget2 + 64.0f);
                microbeVx[i] += dxTarget * inv * (targetDrivePush + roamPush) * dt;
                microbeVy[i] += dyTarget * inv * (targetDrivePush + roamPush) * dt;
            }

            microbeRoamPhase[i] += dt * rand(0.7f, 1.4f);
            float swimAx = (float) Math.cos(microbeRoamPhase[i] + i * 0.19f);
            float swimAy = (float) Math.sin(microbeRoamPhase[i] + i * 0.27f);
            microbeVx[i] += swimAx * wanderPush * dt;
            microbeVy[i] += swimAy * wanderPush * dt;
            microbeVx[i] += rand(-1.0f, 1.0f) * noisePush * dt;
            microbeVy[i] += rand(-1.0f, 1.0f) * noisePush * dt;
        }

        for (int i = 0; i < MICROBE_COUNT; i++) {
            for (int j = i + 1; j < MICROBE_COUNT; j++) {
                float dx = microbeX[i] - microbeX[j];
                float dy = microbeY[i] - microbeY[j];
                float dist2 = dx * dx + dy * dy;
                if (dist2 < CLUSTER_RADIUS2) {
                    microbeNeighborCount[i]++;
                    microbeNeighborCount[j]++;
                }
                if (dist2 < MICROBE_INTERACT_DIST2 && dist2 > 1e-4f) {
                    float force = (MICROBE_INTERACT_DIST2 - dist2) * 0.00045f;
                    float inv = invSqrt(dist2);
                    float ax = dx * inv * force;
                    float ay = dy * inv * force;
                    microbeVx[i] += ax;
                    microbeVy[i] += ay;
                    microbeVx[j] -= ax;
                    microbeVy[j] -= ay;
                }
            }
        }

        for (int i = 0; i < MICROBE_COUNT; i++) {
            microbeVx[i] *= Math.max(0.0f, 1.0f - 1.5f * dt);
            microbeVy[i] *= Math.max(0.0f, 1.0f - 1.5f * dt);

            float speed2 = microbeVx[i] * microbeVx[i] + microbeVy[i] * microbeVy[i];
            if (speed2 > MAX_SPEED * MAX_SPEED) {
                float inv = MAX_SPEED * invSqrt(speed2);
                microbeVx[i] *= inv;
                microbeVy[i] *= inv;
            }

            microbeX[i] += microbeVx[i] * dt;
            microbeY[i] += microbeVy[i] * dt;
            microbeAngle[i] = (float) Math.atan2(microbeVy[i], microbeVx[i]);

            if (microbeX[i] < 0.0f) {
                microbeX[i] += worldWidth;
            } else if (microbeX[i] > worldWidth) {
                microbeX[i] -= worldWidth;
            }
            if (microbeY[i] < 0.0f) {
                microbeY[i] += worldHeight;
            } else if (microbeY[i] > worldHeight) {
                microbeY[i] -= worldHeight;
            }

            microbeEnergy[i] -= ENERGY_DECAY_RATE * dt * lifeScale;
            if (microbeEnergy[i] <= DEATH_ENERGY_THRESHOLD) {
                emitDead(i);
                respawnMicrobe(i);
            } else if (microbeEnergy[i] > REPRODUCE_ENERGY && rng.nextFloat() < REPRODUCE_CHANCE_PER_SEC * dt * lifeScale) {
                int child = findReproductionSlot(i);
                if (child >= 0) {
                    microbeX[child] = microbeX[i] + rand(-25.0f, 25.0f);
                    microbeY[child] = microbeY[i] + rand(-25.0f, 25.0f);
                    microbeVx[child] = -microbeVx[i] * 0.5f;
                    microbeVy[child] = -microbeVy[i] * 0.5f;
                    microbeEnergy[child] = 0.35f;
                    microbeSize[child] = rand(0.5f, 1.1f);
                    assignChildColor(child, i);
                    microbeEnergy[i] *= 0.70f;
                    microbePulseTime[i] = timeSec;
                    microbePulseTime[child] = timeSec;
                }
            }

            if (microbeNeighborCount[i] >= CLUSTER_NEIGHBOR_THRESHOLD) {
                microbeClusterTime[i] += dt;
                if (microbeClusterTime[i] >= CLUSTER_RELEASE_SECONDS) {
                    float a = rand(0.0f, (float) (Math.PI * 2.0));
                    float push = rand(150.0f, 240.0f);
                    float dx = (float) Math.cos(a);
                    float dy = (float) Math.sin(a);
                    microbeVx[i] += dx * push;
                    microbeVy[i] += dy * push;
                    microbeTargetX[i] = microbeX[i] + dx * rand(180.0f, 260.0f);
                    microbeTargetY[i] = microbeY[i] + dy * rand(180.0f, 260.0f);
                    microbePulseTime[i] = timeSec;
                    microbeClusterTime[i] = 0.0f;
                }
            } else {
                microbeClusterTime[i] = Math.max(0.0f, microbeClusterTime[i] - dt * 1.7f);
            }
        }

        foodRespawnAccumulator += dt * AUTO_FOOD_RESPAWN_PER_SEC;
        int autoRespawns = (int) foodRespawnAccumulator;
        if (autoRespawns > 0) {
            foodRespawnAccumulator -= autoRespawns;
            int base = rng.nextInt(FOOD_COUNT);
            int cap = Math.min(autoRespawns, FOOD_COUNT);
            for (int n = 0; n < cap; n++) {
                spawnFood((base + n) % FOOD_COUNT);
            }
        }

        for (int i = 0; i < FOOD_COUNT; i++) {
            foodVx[i] += rand(-1.0f, 1.0f) * 26.0f * dt;
            foodVy[i] += rand(-1.0f, 1.0f) * 26.0f * dt;

            float bestDx = 0.0f;
            float bestDy = 0.0f;
            float bestDist2 = FOOD_ATTRACT_DIST2;
            int nearestMicrobe = -1;
            for (int j = 0; j < MICROBE_COUNT; j++) {
                float dx = microbeX[j] - foodX[i];
                float dy = microbeY[j] - foodY[i];
                float d2 = dx * dx + dy * dy;
                if (d2 < bestDist2) {
                    bestDist2 = d2;
                    bestDx = dx;
                    bestDy = dy;
                    nearestMicrobe = j;
                }
            }

            if (bestDist2 < FOOD_ATTRACT_DIST2) {
                float inv = invSqrt(bestDist2 + 40.0f);
                foodVx[i] += bestDx * inv * 120.0f * dt;
                foodVy[i] += bestDy * inv * 120.0f * dt;
                if (bestDist2 < 196.0f) {
                    int eater = nearestMicrobe >= 0 ? nearestMicrobe : rng.nextInt(MICROBE_COUNT);
                    microbeEnergy[eater] = Math.min(1.8f, microbeEnergy[eater] + 0.18f);
                    microbePulseTime[eater] = timeSec;
                    spawnFood(i);
                }
            }

            foodVx[i] *= Math.max(0.0f, 1.0f - 1.2f * dt);
            foodVy[i] *= Math.max(0.0f, 1.0f - 1.2f * dt);
            foodX[i] += foodVx[i] * dt;
            foodY[i] += foodVy[i] * dt;

            wrapFood(i);
        }

        for (int i = 0; i < DECOR_COUNT; i++) {
            decorX[i] += (float) Math.sin(timeSec * 0.25f + decorY[i] * 0.01f) * 18.0f * dt;
            decorY[i] += (float) Math.cos(timeSec * 0.21f + decorX[i] * 0.01f) * 14.0f * dt;
            decorAngle[i] += 0.25f * dt;
            if (decorAngle[i] > Math.PI * 2.0f) {
                decorAngle[i] -= (float) (Math.PI * 2.0);
            }
            if (decorX[i] < -40.0f || decorX[i] > worldWidth + 40.0f || decorY[i] < -40.0f || decorY[i] > worldHeight + 40.0f) {
                decorX[i] = rand(0.0f, worldWidth);
                decorY[i] = rand(0.0f, worldHeight);
            }
        }

        for (int i = 0; i < DEAD_COUNT; i++) {
            if (deadAge[i] < DEAD_VISIBLE_AGE_MAX) {
                deadAge[i] += dt * DEAD_AGE_ADVANCE_PER_SEC;
            }
        }

        deadAmbientAccumulator += dt * DEAD_AMBIENT_SPAWN_PER_SEC;
        int deadRespawns = (int) deadAmbientAccumulator;
        if (deadRespawns > 0) {
            deadAmbientAccumulator -= deadRespawns;
            for (int i = 0; i < deadRespawns; i++) {
                emitAmbientDead(0.0f);
            }
        }
    }

    private void refreshRuntimeSettingsIfNeeded(long nowMs) {
        if (lastPrefPollMs != Long.MIN_VALUE && (nowMs - lastPrefPollMs) < PREF_POLL_INTERVAL_MS) {
            return;
        }
        lastPrefPollMs = nowMs;

        try {
            if (GLESWallpaper.getAppContext() == null) {
                return;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(GLESWallpaper.getAppContext());
            int speedPercent = prefs.getInt("microbes_lifecycle_speed", 100);
            speedPercent = Math.max(50, Math.min(200, speedPercent));
            lifecycleSpeedScale = speedPercent / 100.0f;
            int motionPercent = prefs.getInt("microbes_motion_activity", 100);
            motionPercent = Math.max(10, Math.min(200, motionPercent));
            motionActivityScale = motionPercent / 100.0f;
            String mode = prefs.getString("microbes_color_mode", "original");
            boolean useOriginal = (mode == null) || "original".equals(mode);
            if (useOriginal != originalColorMode) {
                originalColorMode = useOriginal;
                recolorAllMicrobes();
            }
        } catch (Throwable ignored) {
            lifecycleSpeedScale = 1.0f;
            motionActivityScale = 1.0f;
            originalColorMode = true;
        }
    }

    private void drawMicrobes(float sx, float sy, float tx, float ty) {
        microbePosBuffer.clear();
        microbeMiscBuffer.clear();
        microbeColorBuffer.clear();

        for (int i = 0; i < MICROBE_COUNT; i++) {
            microbePosBuffer.put(microbeX[i]).put(microbeY[i]).put(microbeAngle[i]);
            microbeMiscBuffer.put(microbeSize[i]).put(clamp(microbeEnergy[i], 0.0f, 1.8f)).put(microbePulseTime[i]);
            microbeColorBuffer.put(microbeR[i]).put(microbeG[i]).put(microbeB[i]);
        }

        microbePosBuffer.position(0);
        microbeMiscBuffer.position(0);
        microbeColorBuffer.position(0);

        GLES20.glUseProgram(microbeProgram);
        GLES20.glUniform4f(microbeUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(microbeUTime, timeSec);

        GLES20.glEnableVertexAttribArray(microbeAPosition);
        GLES20.glVertexAttribPointer(microbeAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, microbePosBuffer);
        GLES20.glEnableVertexAttribArray(microbeAMisc);
        GLES20.glVertexAttribPointer(microbeAMisc, 3, GLES20.GL_FLOAT, false, 3 * 4, microbeMiscBuffer);
        GLES20.glEnableVertexAttribArray(microbeAColor);
        GLES20.glVertexAttribPointer(microbeAColor, 3, GLES20.GL_FLOAT, false, 3 * 4, microbeColorBuffer);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, MICROBE_COUNT);

        GLES20.glDisableVertexAttribArray(microbeAPosition);
        GLES20.glDisableVertexAttribArray(microbeAMisc);
        GLES20.glDisableVertexAttribArray(microbeAColor);
    }

    private void drawFood(float sx, float sy, float tx, float ty) {
        foodPosBuffer.clear();
        for (int i = 0; i < FOOD_COUNT; i++) {
            foodPosBuffer.put(foodX[i]).put(foodY[i]).put(foodPhase[i]);
        }
        foodPosBuffer.position(0);

        GLES20.glUseProgram(foodProgram);
        GLES20.glUniform4f(foodUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(foodUTime, timeSec);
        GLES20.glEnableVertexAttribArray(foodAPosition);
        GLES20.glVertexAttribPointer(foodAPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, foodPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, FOOD_COUNT);
        GLES20.glDisableVertexAttribArray(foodAPosition);
    }

    private void drawDecor(float sx, float sy, float tx, float ty) {
        decorPosBuffer.clear();
        for (int i = 0; i < DECOR_COUNT; i++) {
            decorPosBuffer.put(decorX[i]).put(decorY[i]).put(decorAngle[i]).put(decorSize[i]);
        }
        decorPosBuffer.position(0);

        GLES20.glUseProgram(decorProgram);
        GLES20.glUniform4f(decorUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(decorUTime, timeSec);
        GLES20.glEnableVertexAttribArray(decorAPosition);
        GLES20.glVertexAttribPointer(decorAPosition, 4, GLES20.GL_FLOAT, false, 4 * 4, decorPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DECOR_COUNT);
        GLES20.glDisableVertexAttribArray(decorAPosition);
    }

    private void drawDead(float sx, float sy, float tx, float ty) {
        deadPosBuffer.clear();
        int active = 0;
        for (int i = 0; i < DEAD_COUNT; i++) {
            if (deadAge[i] >= DEAD_VISIBLE_AGE_MAX) {
                continue;
            }
            deadPosBuffer.put(deadX[i]).put(deadY[i]).put(deadDrift[i]).put(0.0f);
            active++;
        }
        if (active <= 0) {
            return;
        }
        deadPosBuffer.position(0);

        GLES20.glUseProgram(deadProgram);
        GLES20.glUniform4f(deadUTrans, sx, sy, tx, ty);
        GLES20.glUniform1f(deadUTime, timeSec);
        GLES20.glEnableVertexAttribArray(deadAPos);
        GLES20.glVertexAttribPointer(deadAPos, 4, GLES20.GL_FLOAT, false, 4 * 4, deadPosBuffer);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, active);
        GLES20.glDisableVertexAttribArray(deadAPos);
    }

    private void triggerPulse(float wx, float wy) {
        float best = Float.MAX_VALUE;
        int index = -1;
        for (int i = 0; i < MICROBE_COUNT; i++) {
            float dx = microbeX[i] - wx;
            float dy = microbeY[i] - wy;
            float d2 = dx * dx + dy * dy;
            if (d2 < best) {
                best = d2;
                index = i;
            }
        }
        if (index >= 0) {
            microbePulseTime[index] = timeSec;
            microbeEnergy[index] = Math.min(1.8f, microbeEnergy[index] + 0.14f);
        }
    }

    private void repelMicrobesOnTap(float wx, float wy) {
        int bestIndex = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int i = 0; i < MICROBE_COUNT; i++) {
            float dx = microbeX[i] - wx;
            float dy = microbeY[i] - wy;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestIndex = i;
            }
            if (d2 < TAP_REPEL_RADIUS2) {
                float inv = invSqrt(d2 + 8.0f);
                microbeVx[i] += dx * inv * TAP_REPEL_FORCE;
                microbeVy[i] += dy * inv * TAP_REPEL_FORCE;
            }
        }

        if (bestIndex >= 0) {
            microbePulseTime[bestIndex] = timeSec;
            microbeEnergy[bestIndex] = Math.min(1.8f, microbeEnergy[bestIndex] + 0.1f);
        }
    }

    private void emitDead(int i) {
        deadX[deadWriteCursor] = microbeX[i];
        deadY[deadWriteCursor] = microbeY[i];
        deadDrift[deadWriteCursor] = rand(0.1f, 1.0f);
        deadAge[deadWriteCursor] = 0.0f;
        deadWriteCursor++;
        if (deadWriteCursor >= DEAD_COUNT) {
            deadWriteCursor = 0;
        }
    }

    private void emitAmbientDead(float startAge) {
        deadX[deadWriteCursor] = rand(0.0f, worldWidth);
        deadY[deadWriteCursor] = rand(0.0f, worldHeight);
        deadDrift[deadWriteCursor] = rand(0.1f, 1.0f);
        deadAge[deadWriteCursor] = clamp(startAge, 0.0f, DEAD_VISIBLE_AGE_MAX - 0.001f);
        deadWriteCursor++;
        if (deadWriteCursor >= DEAD_COUNT) {
            deadWriteCursor = 0;
        }
    }

    private void respawnMicrobe(int i) {
        microbeX[i] = rand(0.0f, worldWidth);
        microbeY[i] = rand(0.0f, worldHeight);
        microbeVx[i] = rand(-30.0f, 30.0f);
        microbeVy[i] = rand(-30.0f, 30.0f);
        microbeSize[i] = rand(0.55f, 1.2f);
        microbeEnergy[i] = rand(0.75f, 1.15f);
        assignMicrobeColor(i);
        microbeTargetX[i] = microbeX[i];
        microbeTargetY[i] = microbeY[i];
            microbeRoamRetargetTimer[i] = rand(0.4f, 1.6f);
            microbeRoamPhase[i] = rand(0.0f, (float) (Math.PI * 2.0));
        microbePulseTime[i] = timeSec;
    }

    private void assignMicrobeColor(int index) {
        if (originalColorMode) {
            setOriginalPaletteColor(index, weightedOriginalPaletteIndex());
            return;
        }
        microbeR[index] = rand(0.2f, 1.0f);
        microbeG[index] = rand(0.2f, 1.0f);
        microbeB[index] = rand(0.2f, 1.0f);
    }

    private void assignChildColor(int child, int parent) {
        if (originalColorMode) {
            setOriginalPaletteColor(child, nearestOriginalPaletteIndex(parent));
            return;
        }
        microbeR[child] = microbeR[parent] * 0.9f + 0.1f * rand(0.2f, 1.0f);
        microbeG[child] = microbeG[parent] * 0.9f + 0.1f * rand(0.2f, 1.0f);
        microbeB[child] = microbeB[parent] * 0.9f + 0.1f * rand(0.2f, 1.0f);
    }

    private int weightedOriginalPaletteIndex() {
        float r = rng.nextFloat();
        if (r < 0.20f) {
            return 0;
        }
        if (r < 0.60f) {
            return 1;
        }
        if (r < 0.80f) {
            return 2;
        }
        return 3;
    }

    private int nearestOriginalPaletteIndex(int i) {
        float r = microbeR[i];
        float g = microbeG[i];
        float b = microbeB[i];
        float[][] p = new float[][] {
                {220.0f / 255.0f, 74.0f / 255.0f, 55.0f / 255.0f},
                {78.0f / 255.0f, 167.0f / 255.0f, 79.0f / 255.0f},
                {73.0f / 255.0f, 116.0f / 255.0f, 246.0f / 255.0f},
                {246.0f / 255.0f, 200.0f / 255.0f, 76.0f / 255.0f}
        };
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int k = 0; k < p.length; k++) {
            float dr = r - p[k][0];
            float dg = g - p[k][1];
            float db = b - p[k][2];
            float d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    private void setOriginalPaletteColor(int index, int paletteIndex) {
        if (paletteIndex == 0) {
            microbeR[index] = 220.0f / 255.0f;
            microbeG[index] = 74.0f / 255.0f;
            microbeB[index] = 55.0f / 255.0f;
        } else if (paletteIndex == 1) {
            microbeR[index] = 78.0f / 255.0f;
            microbeG[index] = 167.0f / 255.0f;
            microbeB[index] = 79.0f / 255.0f;
        } else if (paletteIndex == 2) {
            microbeR[index] = 73.0f / 255.0f;
            microbeG[index] = 116.0f / 255.0f;
            microbeB[index] = 246.0f / 255.0f;
        } else {
            microbeR[index] = 246.0f / 255.0f;
            microbeG[index] = 200.0f / 255.0f;
            microbeB[index] = 76.0f / 255.0f;
        }
    }

    private void recolorAllMicrobes() {
        for (int i = 0; i < MICROBE_COUNT; i++) {
            assignMicrobeColor(i);
        }
    }

    private int findReproductionSlot(int parentIndex) {
        int best = -1;
        float minEnergy = Float.MAX_VALUE;
        for (int i = 0; i < MICROBE_COUNT; i++) {
            if (i == parentIndex) {
                continue;
            }
            if (microbeEnergy[i] < minEnergy) {
                minEnergy = microbeEnergy[i];
                best = i;
            }
        }
        if (best >= 0 && minEnergy < 0.18f) {
            return best;
        }
        return -1;
    }

    private void spawnFood(int i) {
        foodX[i] = rand(0.0f, worldWidth);
        foodY[i] = rand(0.0f, worldHeight);
        foodPhase[i] = rand(0.0f, 1.0f);
        foodVx[i] = rand(-24.0f, 24.0f);
        foodVy[i] = rand(-24.0f, 24.0f);
    }

    private void spawnFoodBurst(float wx, float wy) {
        int start = rng.nextInt(FOOD_COUNT);
        for (int k = 0; k < TAP_SPAWN_FOOD_COUNT; k++) {
            int i = (start + k) % FOOD_COUNT;
            float angle = rand(0.0f, (float) (Math.PI * 2.0));
            float radius = rand(0.0f, 24.0f);
            float speed = rand(25.0f, 95.0f);

            foodX[i] = wx + (float) Math.cos(angle) * radius;
            foodY[i] = wy + (float) Math.sin(angle) * radius;
            foodPhase[i] = rand(0.0f, 1.0f);
            foodVx[i] = (float) Math.cos(angle) * speed;
            foodVy[i] = (float) Math.sin(angle) * speed;
            wrapFood(i);
        }
    }

    private void applyMotionTarget(float wx, float wy) {
        int affected = 0;
        for (int i = 0; i < MICROBE_COUNT; i++) {
            float dx = wx - microbeX[i];
            float dy = wy - microbeY[i];
            float d2 = dx * dx + dy * dy;
            if (d2 > MOTION_INFLUENCE_RADIUS2) {
                continue;
            }
            microbeTargetX[i] = wx + rand(-12.0f, 12.0f);
            microbeTargetY[i] = wy + rand(-12.0f, 12.0f);
            affected++;
            if (affected >= MOTION_MAX_AFFECTED) {
                break;
            }
        }

        if (affected == 0) {
            triggerPulse(wx, wy);
        }
    }

    private void wrapFood(int i) {
        if (foodX[i] < 0.0f) {
            foodX[i] += worldWidth;
        } else if (foodX[i] > worldWidth) {
            foodX[i] -= worldWidth;
        }
        if (foodY[i] < 0.0f) {
            foodY[i] += worldHeight;
        } else if (foodY[i] > worldHeight) {
            foodY[i] -= worldHeight;
        }
    }

    private float toWorldX(float x) {
        float width = Math.max(1.0f, mWidth);
        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        float parallaxScrollX = scrollXPx * VIEW_SCROLL_PARALLAX;
        return x / zoom + width * (zoom - 1.0f) / (2.0f * zoom) - parallaxScrollX;
    }

    private float toWorldY(float y) {
        float height = Math.max(1.0f, mHeight);
        float zoom = Math.max(1.0f, VIEW_CENTER_CROP_ZOOM);
        return height * (zoom + 1.0f) / (2.0f * zoom) - y / zoom;
    }

    private FloatBuffer newFloatBuffer(int floatCount) {
        return ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    private float rand(float min, float max) {
        return min + rng.nextFloat() * (max - min);
    }

    private float invSqrt(float x) {
        return 1.0f / (float) Math.sqrt(Math.max(1e-6f, x));
    }

    private float clamp(float x, float min, float max) {
        return Math.max(min, Math.min(max, x));
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }
}
