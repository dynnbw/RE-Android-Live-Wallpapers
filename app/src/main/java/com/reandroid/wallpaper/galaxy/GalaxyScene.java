/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.Matrix;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.Random;

final class GalaxyScene {
    private static final String TAG = "GalaxyScene";

    private static final int DEFAULT_PARTICLE_COUNT = 12000;
    private static final int MIN_PARTICLE_COUNT = 1000;
    private static final int MAX_PARTICLE_COUNT = 20000;

    private static final int DEFAULT_PARTICLE_ALPHA_PERCENT = 100;
    private static final int MIN_PARTICLE_ALPHA_PERCENT = 10;
    private static final int MAX_PARTICLE_ALPHA_PERCENT = 100;

    private static final float ELLIPSE_RATIO = 0.892f;
    private static final float ELLIPSE_TWIST = 0.023333333f;
    private static final float PI = 3.1415f;
    private static final float TWO_PI = 6.283f;
    private static final int GALAXY_RADIUS = 300;

    private static final float PITCH_ANGLE_DEG = 6.7f;
    private static final float FORBIDDEN_RADIUS_KPC = 2.82f;
    private static final float GALAXY_RADIUS_KPC = 25.0f;
    private static final float PRECISE_PATTERN_SPEED = 0.0018f;

    private static final int DEFAULT_ARM_COUNT = 2;
    private static final float DEFAULT_ARM_OFFSET = PI;
    private static final float DEFAULT_PITCH_ANGLE_DEG = PITCH_ANGLE_DEG;
    private static final float DEFAULT_FORBIDDEN_RADIUS_KPC = FORBIDDEN_RADIUS_KPC;
    private static final float DEFAULT_PRECISE_INNER_SCATTER = 1.35f;
    private static final float DEFAULT_PRECISE_OUTER_SCATTER = 0.75f;
    private static final float DEFAULT_PRECISE_TURBULENCE = 0.28f;
    private static final float DEFAULT_ELLIPSE_RATIO = ELLIPSE_RATIO;
    private static final float DEFAULT_ELLIPSE_TWIST = ELLIPSE_TWIST;

    private static final int MIN_ARM_COUNT = 1;
    private static final int MAX_ARM_COUNT = 6;

    private static final float MIN_ARM_OFFSET = 0.0f;
    private static final float MAX_ARM_OFFSET = TWO_PI;
    private static final float MIN_PITCH_ANGLE_DEG = 0.0f;
    private static final float MAX_PITCH_ANGLE_DEG = 30.0f;
    private static final float MIN_FORBIDDEN_RADIUS_KPC = 0.0f;
    private static final float MAX_FORBIDDEN_RADIUS_KPC = 10.0f;
    private static final float MIN_SCATTER = 0.0f;
    private static final float MAX_SCATTER = 5.0f;
    private static final float MIN_TURBULENCE = 0.0f;
    private static final float MAX_TURBULENCE = 2.0f;
    private static final float MIN_ELLIPSE_RATIO = 0.5f;
    private static final float MAX_ELLIPSE_RATIO = 1.0f;
    private static final float MIN_ELLIPSE_TWIST = -0.1f;
    private static final float MAX_ELLIPSE_TWIST = 0.1f;

    private static final String KEY_ARM_COUNT = "galaxy_arm_count";
    private static final String KEY_ARM_OFFSET = "galaxy_arm_offset";
    private static final String KEY_PITCH_ANGLE_DEG = "galaxy_pitch_angle_deg";
    private static final String KEY_INNER_SCATTER = "galaxy_inner_scatter";
    private static final String KEY_OUTER_SCATTER = "galaxy_outer_scatter";
    private static final String KEY_TURBULENCE = "galaxy_turbulence";
    private static final String KEY_FORBIDDEN_RADIUS = "galaxy_forbidden_radius";
    private static final String KEY_ELLIPSE_RATIO = "galaxy_ellipse_ratio";
    private static final String KEY_ELLIPSE_TWIST = "galaxy_ellipse_twist";
    private static final int ELLIPSE_TWIST_STORAGE_OFFSET = 100;
    private static final long SETTINGS_SYNC_INTERVAL_MS = 500L;

    private final Context mContext;
    private final Random mRandom = new Random();
    private final float[] mProjMatrix = new float[16];
    private final SceneData mSceneData = new SceneData();

    private int mWidth;
    private int mHeight;
    private int mParticleCount = DEFAULT_PARTICLE_COUNT;
    private int mParticleAlphaPercent = DEFAULT_PARTICLE_ALPHA_PERCENT;
    private boolean mUsePreciseCalculation = false;
    private int mArmCount = DEFAULT_ARM_COUNT;
    private float mArmOffset = DEFAULT_ARM_OFFSET;
    private float mPitchAngleDeg = DEFAULT_PITCH_ANGLE_DEG;
    private float mPitchAngleRad = DEFAULT_PITCH_ANGLE_DEG * PI / 180.0f;
    private float mForbiddenRadiusKpc = DEFAULT_FORBIDDEN_RADIUS_KPC;
    private float mPreciseInnerScatter = DEFAULT_PRECISE_INNER_SCATTER;
    private float mPreciseOuterScatter = DEFAULT_PRECISE_OUTER_SCATTER;
    private float mPreciseTurbulence = DEFAULT_PRECISE_TURBULENCE;
    private float mEllipseRatio = DEFAULT_ELLIPSE_RATIO;
    private float mEllipseTwist = DEFAULT_ELLIPSE_TWIST;
    private float mXOffset = 0.5f;
    private long mLastSettingsSyncTime = 0L;
    private boolean mParticleDataDirty = true;
    private boolean mParticleBuffersDirty = true;
    private boolean mParticlePositionsDirty = false;
    private boolean mSkipParticleAdvanceOnNextUpdate = true;

    GalaxyScene(int width, int height, Context context) {
        mWidth = width;
        mHeight = height;
        mContext = context;
        loadSettingsFromPreferences();
    }

    void update(long timeMs) {
        syncSettingsFromPreferencesIfNeeded(timeMs);

        if (mParticleDataDirty || mSceneData.particlePositions == null) {
            rebuildParticleData();
        }

        calcMatrix(mSceneData.mvpMatrix, mXOffset * 2.0f - 1.0f);

        if (mSkipParticleAdvanceOnNextUpdate) {
            mSkipParticleAdvanceOnNextUpdate = false;
            return;
        }

        updateParticles();
        mParticlePositionsDirty = true;
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    boolean consumeParticleBufferRebuildRequested() {
        boolean value = mParticleBuffersDirty;
        mParticleBuffersDirty = false;
        return value;
    }

    boolean consumeParticlePositionsDirty() {
        boolean value = mParticlePositionsDirty;
        mParticlePositionsDirty = false;
        return value;
    }

    void resize(int width, int height) {
        if (mWidth == width && mHeight == height) {
            return;
        }
        mWidth = width;
        mHeight = height;
        requestParticleRebuild();
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    void setParticleCount(int count) {
        int clamped = clampInt(count, MIN_PARTICLE_COUNT, MAX_PARTICLE_COUNT);
        if (clamped != mParticleCount) {
            mParticleCount = clamped;
            persistInt("galaxy_particle_count", mParticleCount);
            requestParticleRebuild();
        }
    }

    void setPreciseCalculation(boolean enabled) {
        if (enabled != mUsePreciseCalculation) {
            mUsePreciseCalculation = enabled;
            persistBoolean("galaxy_precise_calc", mUsePreciseCalculation);
            requestParticleRebuild();
        }
    }

    void setParticleAlphaPercent(int alphaPercent) {
        int clamped = clampInt(alphaPercent, MIN_PARTICLE_ALPHA_PERCENT, MAX_PARTICLE_ALPHA_PERCENT);
        if (clamped != mParticleAlphaPercent) {
            mParticleAlphaPercent = clamped;
            mSceneData.particleAlphaMultiplier = mParticleAlphaPercent / 100.0f;
            persistInt("galaxy_particle_alpha", mParticleAlphaPercent);
        }
    }

    void setArmCount(int armCount) {
        int clamped = clampInt(armCount, MIN_ARM_COUNT, MAX_ARM_COUNT);
        if (clamped != mArmCount) {
            mArmCount = clamped;
            persistInt(KEY_ARM_COUNT, mArmCount);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setArmOffset(float armOffset) {
        float clamped = clampFloat(armOffset, MIN_ARM_OFFSET, MAX_ARM_OFFSET);
        if (clamped != mArmOffset) {
            mArmOffset = clamped;
            persistScaledFloat(KEY_ARM_OFFSET, mArmOffset, 1000.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setPitchAngleDeg(float pitchAngleDeg) {
        float clamped = clampFloat(pitchAngleDeg, MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
        if (clamped != mPitchAngleDeg) {
            mPitchAngleDeg = clamped;
            mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;
            persistScaledFloat(KEY_PITCH_ANGLE_DEG, mPitchAngleDeg, 10.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setInnerScatter(float innerScatter) {
        float clamped = clampFloat(innerScatter, MIN_SCATTER, MAX_SCATTER);
        if (clamped != mPreciseInnerScatter) {
            mPreciseInnerScatter = clamped;
            persistScaledFloat(KEY_INNER_SCATTER, mPreciseInnerScatter, 100.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setOuterScatter(float outerScatter) {
        float clamped = clampFloat(outerScatter, MIN_SCATTER, MAX_SCATTER);
        if (clamped != mPreciseOuterScatter) {
            mPreciseOuterScatter = clamped;
            persistScaledFloat(KEY_OUTER_SCATTER, mPreciseOuterScatter, 100.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setTurbulence(float turbulence) {
        float clamped = clampFloat(turbulence, MIN_TURBULENCE, MAX_TURBULENCE);
        if (clamped != mPreciseTurbulence) {
            mPreciseTurbulence = clamped;
            persistScaledFloat(KEY_TURBULENCE, mPreciseTurbulence, 100.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setForbiddenRadius(float forbiddenRadius) {
        float clamped = clampFloat(forbiddenRadius, MIN_FORBIDDEN_RADIUS_KPC, MAX_FORBIDDEN_RADIUS_KPC);
        if (clamped != mForbiddenRadiusKpc) {
            mForbiddenRadiusKpc = clamped;
            persistScaledFloat(KEY_FORBIDDEN_RADIUS, mForbiddenRadiusKpc, 100.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setEllipseRatio(float ellipseRatio) {
        float clamped = clampFloat(ellipseRatio, MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
        if (clamped != mEllipseRatio) {
            mEllipseRatio = clamped;
            persistScaledFloat(KEY_ELLIPSE_RATIO, mEllipseRatio, 1000.0f);
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    void setEllipseTwist(float ellipseTwist) {
        float clamped = clampFloat(ellipseTwist, MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);
        if (clamped != mEllipseTwist) {
            mEllipseTwist = clamped;
            persistInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(mEllipseTwist));
            if (mUsePreciseCalculation) {
                requestParticleRebuild();
            }
        }
    }

    private void requestParticleRebuild() {
        mParticleDataDirty = true;
        mParticleBuffersDirty = true;
        mParticlePositionsDirty = false;
        mSkipParticleAdvanceOnNextUpdate = true;
    }

    private void rebuildParticleData() {
        float halfWidth = Math.max(1.0f, mWidth * 0.5f);
        float scale = GALAXY_RADIUS / halfWidth;
        float[] particlePositions = new float[mParticleCount * 3];
        float[] particleColors = new float[mParticleCount * 4];
        float[] particleSpeeds = new float[mParticleCount];

        for (int i = 0; i < mParticleCount; i++) {
            float d = Math.abs(randomGauss()) * GALAXY_RADIUS * 0.5f + mRandom.nextFloat() * 64.0f;
            float id = d / GALAXY_RADIUS;
            float z = randomGauss() * 0.4f * (1.0f - id);

            int colorIndex = i * 4;
            if (d < GALAXY_RADIUS * 0.33f) {
                particleColors[colorIndex] = 220 + id * 35;
                particleColors[colorIndex + 1] = 220;
                particleColors[colorIndex + 2] = 220;
            } else {
                particleColors[colorIndex] = 180;
                particleColors[colorIndex + 1] = 180;
                particleColors[colorIndex + 2] = Math.min(140.0f + id * 115.0f, 255.0f);
            }
            particleColors[colorIndex + 3] = (mRandom.nextFloat() * 0.9f + 1.2f) * 6.0f;

            if (d > GALAXY_RADIUS * 0.15f) {
                z *= 0.6f * (1.0f - id);
            } else {
                z *= 0.72f;
            }

            float mappedDistance = mapf(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, d);
            float angle = mUsePreciseCalculation ? calculatePreciseInitialAngle(d) : mRandom.nextFloat() * TWO_PI;

            int positionIndex = i * 3;
            particlePositions[positionIndex] = angle;
            particlePositions[positionIndex + 1] = mappedDistance;
            particlePositions[positionIndex + 2] = z / 5.0f;

            if (mUsePreciseCalculation) {
                particleSpeeds[i] = PRECISE_PATTERN_SPEED;
            } else {
                particleSpeeds[i] = (mRandom.nextFloat() * 0.001f + 0.0015f)
                        * (0.5f + (scale / mappedDistance)) * 0.8f;
            }
        }

        updateProjectionMatrix();
        mSceneData.particlePositions = particlePositions;
        mSceneData.particleColors = particleColors;
        mSceneData.particleSpeeds = particleSpeeds;
        mSceneData.particleCount = mParticleCount;
        mSceneData.particleAlphaMultiplier = mParticleAlphaPercent / 100.0f;
        mParticleDataDirty = false;
        mParticleBuffersDirty = true;
        mParticlePositionsDirty = false;
        mSkipParticleAdvanceOnNextUpdate = true;
        Log.d(TAG, "粒子数据已重建: " + mParticleCount);
    }

    private void updateProjectionMatrix() {
        if (mWidth > mHeight) {
            float aspect = (float) mWidth / Math.max(1, mHeight);
            Matrix.frustumM(mProjMatrix, 0, -aspect, aspect, -1, 1, 1, 100);
        } else {
            float aspect = (float) mHeight / Math.max(1, mWidth);
            Matrix.frustumM(mProjMatrix, 0, -1, 1, -aspect, aspect, 1, 100);
        }

        float[] transformMatrix = new float[16];
        float[] tempMatrix = new float[16];

        Matrix.setRotateM(transformMatrix, 0, 180, 0, 1, 0);
        Matrix.multiplyMM(tempMatrix, 0, mProjMatrix, 0, transformMatrix, 0);

        Matrix.setIdentityM(transformMatrix, 0);
        Matrix.scaleM(transformMatrix, 0, -2.0f, 2.0f, 1.0f);
        Matrix.multiplyMM(mProjMatrix, 0, tempMatrix, 0, transformMatrix, 0);
    }

    private void updateParticles() {
        if (mSceneData.particlePositions == null || mSceneData.particleSpeeds == null) {
            return;
        }
        for (int i = 0; i < mParticleCount; i++) {
            mSceneData.particlePositions[i * 3] += mSceneData.particleSpeeds[i];
        }
    }

    private void calcMatrix(float[] matrix, float offset) {
        float angle = 50.0f;
        float a = offset * angle;
        float absoluteAngle = Math.abs(a);

        Matrix.setIdentityM(matrix, 0);
        Matrix.translateM(matrix, 0, 0.0f, 0.0f, 10.0f - 6.0f * absoluteAngle / 50.0f);

        if (mHeight > mWidth) {
            Matrix.scaleM(matrix, 0, 6.6f, 6.0f, 1.0f);
        } else {
            Matrix.scaleM(matrix, 0, 12.6f, 12.0f, 1.0f);
        }

        Matrix.rotateM(matrix, 0, absoluteAngle, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(matrix, 0, a, 0.0f, 0.4f, 0.1f);

        float[] tempMatrix = new float[16];
        Matrix.multiplyMM(tempMatrix, 0, mProjMatrix, 0, matrix, 0);
        System.arraycopy(tempMatrix, 0, matrix, 0, 16);
    }

    private void loadSettingsFromPreferences() {
        if (mContext == null) {
            return;
        }
        Context appContext = getAppContext();
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);

        if (defaultPrefs.contains("galaxy_particle_count")) {
            mParticleCount = defaultPrefs.getInt("galaxy_particle_count", DEFAULT_PARTICLE_COUNT);
        } else {
            mParticleCount = legacyPrefs.getInt("galaxy_particle_count", DEFAULT_PARTICLE_COUNT);
            if (legacyPrefs.contains("galaxy_particle_count")) {
                defaultPrefs.edit().putInt("galaxy_particle_count", mParticleCount).apply();
            }
        }

        if (defaultPrefs.contains("galaxy_particle_alpha")) {
            mParticleAlphaPercent = defaultPrefs.getInt("galaxy_particle_alpha", DEFAULT_PARTICLE_ALPHA_PERCENT);
        } else {
            mParticleAlphaPercent = legacyPrefs.getInt("galaxy_particle_alpha", DEFAULT_PARTICLE_ALPHA_PERCENT);
            if (legacyPrefs.contains("galaxy_particle_alpha")) {
                defaultPrefs.edit().putInt("galaxy_particle_alpha", mParticleAlphaPercent).apply();
            }
        }

        if (defaultPrefs.contains("galaxy_precise_calc")) {
            mUsePreciseCalculation = defaultPrefs.getBoolean("galaxy_precise_calc", false);
        } else {
            mUsePreciseCalculation = legacyPrefs.getBoolean("galaxy_precise_calc", false);
            if (legacyPrefs.contains("galaxy_precise_calc")) {
                defaultPrefs.edit().putBoolean("galaxy_precise_calc", mUsePreciseCalculation).apply();
            }
        }

        loadPreciseShapeSettings(defaultPrefs, legacyPrefs);
        mParticleCount = clampInt(mParticleCount, MIN_PARTICLE_COUNT, MAX_PARTICLE_COUNT);
        mParticleAlphaPercent = clampInt(mParticleAlphaPercent, MIN_PARTICLE_ALPHA_PERCENT, MAX_PARTICLE_ALPHA_PERCENT);
        mSceneData.particleAlphaMultiplier = mParticleAlphaPercent / 100.0f;
    }

    private void loadPreciseShapeSettings(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs) {
        SharedPreferences.Editor migrateEditor = defaultPrefs.edit();

        mArmCount = clampInt(readIntWithLegacy(defaultPrefs, legacyPrefs, KEY_ARM_COUNT,
                DEFAULT_ARM_COUNT, migrateEditor), MIN_ARM_COUNT, MAX_ARM_COUNT);
        mArmOffset = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_ARM_OFFSET,
                DEFAULT_ARM_OFFSET, 1000.0f, migrateEditor), MIN_ARM_OFFSET, MAX_ARM_OFFSET);
        mPitchAngleDeg = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_PITCH_ANGLE_DEG,
                DEFAULT_PITCH_ANGLE_DEG, 10.0f, migrateEditor), MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
        mForbiddenRadiusKpc = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_FORBIDDEN_RADIUS,
                DEFAULT_FORBIDDEN_RADIUS_KPC, 100.0f, migrateEditor), MIN_FORBIDDEN_RADIUS_KPC,
                MAX_FORBIDDEN_RADIUS_KPC);
        mPreciseInnerScatter = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_INNER_SCATTER,
                DEFAULT_PRECISE_INNER_SCATTER, 100.0f, migrateEditor), MIN_SCATTER, MAX_SCATTER);
        mPreciseOuterScatter = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_OUTER_SCATTER,
                DEFAULT_PRECISE_OUTER_SCATTER, 100.0f, migrateEditor), MIN_SCATTER, MAX_SCATTER);
        mPreciseTurbulence = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_TURBULENCE,
                DEFAULT_PRECISE_TURBULENCE, 100.0f, migrateEditor), MIN_TURBULENCE, MAX_TURBULENCE);
        mEllipseRatio = clampFloat(readScaledFloatWithLegacy(defaultPrefs, legacyPrefs, KEY_ELLIPSE_RATIO,
                DEFAULT_ELLIPSE_RATIO, 1000.0f, migrateEditor), MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
        mEllipseTwist = clampFloat(readEllipseTwistWithLegacy(defaultPrefs, legacyPrefs, migrateEditor),
                MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);

        mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;
        migrateEditor.apply();
    }

    private int readIntWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs,
            String key, int fallback, SharedPreferences.Editor migrateEditor) {
        if (defaultPrefs.contains(key)) {
            return defaultPrefs.getInt(key, fallback);
        }
        if (legacyPrefs.contains(key)) {
            int value = legacyPrefs.getInt(key, fallback);
            migrateEditor.putInt(key, value);
            return value;
        }
        return fallback;
    }

    private float readScaledFloatWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs,
            String key, float fallback, float scale, SharedPreferences.Editor migrateEditor) {
        if (defaultPrefs.contains(key)) {
            return defaultPrefs.getInt(key, Math.round(fallback * scale)) / scale;
        }
        if (legacyPrefs.contains(key)) {
            Object legacyValue = legacyPrefs.getAll().get(key);
            float legacyFloat = legacyValue instanceof Number
                    ? ((Number) legacyValue).floatValue()
                    : fallback;
            migrateEditor.putInt(key, Math.round(legacyFloat * scale));
            return legacyFloat;
        }
        return fallback;
    }

    private float readEllipseTwistWithLegacy(SharedPreferences defaultPrefs, SharedPreferences legacyPrefs,
            SharedPreferences.Editor migrateEditor) {
        if (defaultPrefs.contains(KEY_ELLIPSE_TWIST)) {
            int raw = defaultPrefs.getInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(DEFAULT_ELLIPSE_TWIST));
            return decodeEllipseTwist(raw);
        }
        if (legacyPrefs.contains(KEY_ELLIPSE_TWIST)) {
            Object legacyValue = legacyPrefs.getAll().get(KEY_ELLIPSE_TWIST);
            float legacyFloat = legacyValue instanceof Number
                    ? ((Number) legacyValue).floatValue()
                    : DEFAULT_ELLIPSE_TWIST;
            migrateEditor.putInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(legacyFloat));
            return legacyFloat;
        }
        return DEFAULT_ELLIPSE_TWIST;
    }

    private void syncSettingsFromPreferencesIfNeeded(long now) {
        if (mContext == null) {
            return;
        }
        if (mLastSettingsSyncTime != 0L && (now - mLastSettingsSyncTime) < SETTINGS_SYNC_INTERVAL_MS) {
            return;
        }
        mLastSettingsSyncTime = now;

        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(getAppContext());
        int prefParticleCount = clampInt(defaultPrefs.getInt("galaxy_particle_count", mParticleCount),
                MIN_PARTICLE_COUNT, MAX_PARTICLE_COUNT);
        int prefParticleAlpha = clampInt(defaultPrefs.getInt("galaxy_particle_alpha", mParticleAlphaPercent),
                MIN_PARTICLE_ALPHA_PERCENT, MAX_PARTICLE_ALPHA_PERCENT);
        boolean prefPreciseCalc = defaultPrefs.getBoolean("galaxy_precise_calc", mUsePreciseCalculation);

        int prefArmCount = clampInt(defaultPrefs.getInt(KEY_ARM_COUNT, mArmCount), MIN_ARM_COUNT, MAX_ARM_COUNT);
        float prefArmOffset = clampFloat(defaultPrefs.getInt(KEY_ARM_OFFSET, Math.round(mArmOffset * 1000.0f)) / 1000.0f,
                MIN_ARM_OFFSET, MAX_ARM_OFFSET);
        float prefPitchAngleDeg = clampFloat(
                defaultPrefs.getInt(KEY_PITCH_ANGLE_DEG, Math.round(mPitchAngleDeg * 10.0f)) / 10.0f,
                MIN_PITCH_ANGLE_DEG, MAX_PITCH_ANGLE_DEG);
        float prefInnerScatter = clampFloat(
                defaultPrefs.getInt(KEY_INNER_SCATTER, Math.round(mPreciseInnerScatter * 100.0f)) / 100.0f,
                MIN_SCATTER, MAX_SCATTER);
        float prefOuterScatter = clampFloat(
                defaultPrefs.getInt(KEY_OUTER_SCATTER, Math.round(mPreciseOuterScatter * 100.0f)) / 100.0f,
                MIN_SCATTER, MAX_SCATTER);
        float prefTurbulence = clampFloat(
                defaultPrefs.getInt(KEY_TURBULENCE, Math.round(mPreciseTurbulence * 100.0f)) / 100.0f,
                MIN_TURBULENCE, MAX_TURBULENCE);
        float prefForbiddenRadius = clampFloat(
                defaultPrefs.getInt(KEY_FORBIDDEN_RADIUS, Math.round(mForbiddenRadiusKpc * 100.0f)) / 100.0f,
                MIN_FORBIDDEN_RADIUS_KPC, MAX_FORBIDDEN_RADIUS_KPC);
        float prefEllipseRatio = clampFloat(
                defaultPrefs.getInt(KEY_ELLIPSE_RATIO, Math.round(mEllipseRatio * 1000.0f)) / 1000.0f,
                MIN_ELLIPSE_RATIO, MAX_ELLIPSE_RATIO);
        float prefEllipseTwist = clampFloat(
                decodeEllipseTwist(defaultPrefs.getInt(KEY_ELLIPSE_TWIST, encodeEllipseTwist(mEllipseTwist))),
                MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST);

        if (prefParticleCount != mParticleCount) {
            mParticleCount = prefParticleCount;
            requestParticleRebuild();
        }
        if (prefPreciseCalc != mUsePreciseCalculation) {
            mUsePreciseCalculation = prefPreciseCalc;
            requestParticleRebuild();
        }
        if (prefArmCount != mArmCount) {
            mArmCount = prefArmCount;
            requestParticleRebuild();
        }
        if (prefArmOffset != mArmOffset) {
            mArmOffset = prefArmOffset;
            requestParticleRebuild();
        }
        if (prefPitchAngleDeg != mPitchAngleDeg) {
            mPitchAngleDeg = prefPitchAngleDeg;
            mPitchAngleRad = mPitchAngleDeg * PI / 180.0f;
            requestParticleRebuild();
        }
        if (prefInnerScatter != mPreciseInnerScatter) {
            mPreciseInnerScatter = prefInnerScatter;
            requestParticleRebuild();
        }
        if (prefOuterScatter != mPreciseOuterScatter) {
            mPreciseOuterScatter = prefOuterScatter;
            requestParticleRebuild();
        }
        if (prefTurbulence != mPreciseTurbulence) {
            mPreciseTurbulence = prefTurbulence;
            requestParticleRebuild();
        }
        if (prefForbiddenRadius != mForbiddenRadiusKpc) {
            mForbiddenRadiusKpc = prefForbiddenRadius;
            requestParticleRebuild();
        }
        if (prefEllipseRatio != mEllipseRatio) {
            mEllipseRatio = prefEllipseRatio;
            requestParticleRebuild();
        }
        if (prefEllipseTwist != mEllipseTwist) {
            mEllipseTwist = prefEllipseTwist;
            requestParticleRebuild();
        }
        if (prefParticleAlpha != mParticleAlphaPercent) {
            mParticleAlphaPercent = prefParticleAlpha;
            mSceneData.particleAlphaMultiplier = mParticleAlphaPercent / 100.0f;
        }
    }

    private float calculatePreciseInitialAngle(float radiusInScreenSpace) {
        float radiusKpc = radiusInScreenSpace * (GALAXY_RADIUS_KPC / GALAXY_RADIUS);
        float safeForbiddenRadius = Math.max(0.001f, mForbiddenRadiusKpc);
        float radiusRatio = radiusKpc / safeForbiddenRadius;
        float angle;

        if (radiusRatio > 1.0f) {
            float tanI = (float) Math.tan(mPitchAngleRad);
            if (Math.abs(tanI) < 1.0e-4f) {
                tanI = 1.0e-4f;
            }
            float baseAngle = (1.0f / tanI) * (float) Math.log(radiusRatio);

            int armIndex = mRandom.nextInt(Math.max(1, mArmCount));
            float armAngleOffset = armIndex * mArmOffset;

            float radiusLerp = (radiusKpc - safeForbiddenRadius) / (GALAXY_RADIUS_KPC - safeForbiddenRadius);
            radiusLerp = clampFloat(radiusLerp, 0.0f, 1.0f);
            float scatterAmp = mPreciseInnerScatter + (mPreciseOuterScatter - mPreciseInnerScatter) * radiusLerp;

            float uniformScatter = (mRandom.nextFloat() - 0.5f) * scatterAmp;
            float gaussianScatter = randomGauss() * (scatterAmp * 0.42f);
            float turbulence = (mRandom.nextFloat() - 0.5f) * mPreciseTurbulence * (1.0f - radiusLerp);
            armAngleOffset += (mRandom.nextFloat() - 0.5f) * 0.28f;

            float ellipseStrength = (1.0f - mEllipseRatio) * 1.3f;
            float twistTerm = mEllipseTwist * radiusKpc * 8.0f;
            armAngleOffset += ellipseStrength * (float) Math.sin(2.0f * (baseAngle + armAngleOffset) + twistTerm);
            baseAngle += mEllipseTwist * radiusLerp * 0.9f;

            if (radiusKpc < 5.0f) {
                float innerBlend = 1.0f - radiusKpc / 5.0f;
                float ellipticityFactor = 0.14f * innerBlend;
                float sin2theta = (float) Math.sin(2.0f * (baseAngle + armAngleOffset));
                armAngleOffset += ellipticityFactor * sin2theta;
                turbulence += randomGauss() * 0.36f * innerBlend;
            }

            angle = baseAngle + armAngleOffset + uniformScatter + gaussianScatter + turbulence;
        } else {
            angle = mRandom.nextFloat() * TWO_PI;
        }

        angle %= TWO_PI;
        if (angle < 0.0f) {
            angle += TWO_PI;
        }
        return angle;
    }

    private float randomGauss() {
        float x1;
        float x2;
        float w = 2.0f;

        do {
            x1 = mRandom.nextFloat() * 2.0f - 1.0f;
            x2 = mRandom.nextFloat() * 2.0f - 1.0f;
            w = x1 * x1 + x2 * x2;
        } while (w >= 1.0f);

        w = (float) Math.sqrt(-2.0f * Math.log(w) / w);
        return x1 * w;
    }

    private float mapf(float minStart, float minStop, float maxStart, float maxStop, float value) {
        return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int encodeEllipseTwist(float twist) {
        return Math.round(clampFloat(twist, MIN_ELLIPSE_TWIST, MAX_ELLIPSE_TWIST) * 1000.0f)
                + ELLIPSE_TWIST_STORAGE_OFFSET;
    }

    private float decodeEllipseTwist(int raw) {
        if (raw >= 0 && raw <= 200) {
            return (raw - ELLIPSE_TWIST_STORAGE_OFFSET) / 1000.0f;
        }
        return raw / 1000.0f;
    }

    private void persistInt(String key, int value) {
        if (mContext == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(getAppContext()).edit().putInt(key, value).apply();
    }

    private void persistBoolean(String key, boolean value) {
        if (mContext == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(getAppContext()).edit().putBoolean(key, value).apply();
    }

    private void persistScaledFloat(String key, float value, float scale) {
        persistInt(key, Math.round(value * scale));
    }

    private Context getAppContext() {
        Context appContext = mContext.getApplicationContext();
        return appContext != null ? appContext : mContext;
    }

    static final class SceneData {
        private float[] particlePositions;
        private float[] particleColors;
        private float[] particleSpeeds;
        private final float[] mvpMatrix = new float[16];
        private int particleCount;
        private float particleAlphaMultiplier = 1.0f;

        float[] getParticlePositions() {
            return particlePositions;
        }

        float[] getParticleColors() {
            return particleColors;
        }

        float[] getMvpMatrix() {
            return mvpMatrix;
        }

        int getParticleCount() {
            return particleCount;
        }

        float getParticleAlphaMultiplier() {
            return particleAlphaMultiplier;
        }
    }
}
