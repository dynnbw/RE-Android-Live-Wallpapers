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

package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.Matrix;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.MathUtils;
import java.util.Random;

final class Galaxy4Scene {
    private static final String TAG = "Galaxy4Scene";

    private SharedPreferences mPluginPrefs;

    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
    }

    private SharedPreferences getPrefs() {
        if (mPluginPrefs != null) return mPluginPrefs;
        return PreferenceManager.getDefaultSharedPreferences(getAppContext());
    }

    private static final int DEFAULT_BG_STAR_COUNT = 11000;
    private static final int MIN_BG_STAR_COUNT = 1000;
    private static final int MAX_BG_STAR_COUNT = 20000;

    private static final int DEFAULT_SPACE_CLOUD_COUNT = 25;
    private static final int MIN_SPACE_CLOUD_COUNT = 5;
    private static final int MAX_SPACE_CLOUD_COUNT = 100;

    private static final int STATIC_STAR_COUNT = 50;
    private static final int GALAXY_RADIUS = 300;
    private static final long SETTINGS_SYNC_INTERVAL_MS = 500L;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float SPACE_CLOUD_ROTATION_DELTA = -0.065f;
    private static final float BG_STAR_ROTATION_DELTA = -0.007f;

    private final Context mContext;
    private final Random mRandom = new Random();
    private final float[] mProjMatrix = new float[16];
    private final SceneData mSceneData = new SceneData();

    private int mWidth;
    private int mHeight;
    private int mBgStarCount = DEFAULT_BG_STAR_COUNT;
    private int mSpaceCloudCount = DEFAULT_SPACE_CLOUD_COUNT;
    private long mLastSettingsSyncTime = 0L;
    private boolean mParticleDataDirty = true;
    private boolean mParticleBuffersDirty = true;
    private boolean mDynamicParticlesDirty = false;
    private boolean mSkipParticleAdvanceOnNextUpdate = true;

    Galaxy4Scene(int width, int height, Context context) {
        mWidth = width;
        mHeight = height;
        mContext = context;
        loadParticleCountsFromPreferences();
    }

    void update(long timeMs) {
        syncSettingsFromPreferencesIfNeeded(timeMs);

        if (mParticleDataDirty || mSceneData.spaceClouds == null) {
            rebuildParticleData();
        }

        mSceneData.timeSeconds = timeMs / 1000.0f;

        if (mSkipParticleAdvanceOnNextUpdate) {
            mSkipParticleAdvanceOnNextUpdate = false;
            return;
        }

        advanceParticles();
        mDynamicParticlesDirty = true;
    }

    void resize(int width, int height) {
        if (mWidth == width && mHeight == height) {
            return;
        }
        mWidth = width;
        mHeight = height;
        requestParticleRebuild();
    }

    void setBgStarCount(int count) {
        int clamped = MathUtils.clamp(count, MIN_BG_STAR_COUNT, MAX_BG_STAR_COUNT);
        if (clamped != mBgStarCount) {
            mBgStarCount = clamped;
            persistInt("galaxy4_bg_star_count", mBgStarCount);
            requestParticleRebuild();
        }
    }

    void setSpaceCloudCount(int count) {
        int clamped = MathUtils.clamp(count, MIN_SPACE_CLOUD_COUNT, MAX_SPACE_CLOUD_COUNT);
        if (clamped != mSpaceCloudCount) {
            mSpaceCloudCount = clamped;
            persistInt("galaxy4_space_cloud_count", mSpaceCloudCount);
            requestParticleRebuild();
        }
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    boolean consumeParticleBufferRebuildRequested() {
        boolean value = mParticleBuffersDirty;
        mParticleBuffersDirty = false;
        return value;
    }

    boolean consumeDynamicParticlesDirty() {
        boolean value = mDynamicParticlesDirty;
        mDynamicParticlesDirty = false;
        return value;
    }

    private void loadParticleCountsFromPreferences() {
        if (mContext == null) {
            return;
        }

        Context appContext = getAppContext();
        SharedPreferences defaultPrefs = getPrefs();
        SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);

        if (defaultPrefs.contains("galaxy4_bg_star_count")) {
            mBgStarCount = defaultPrefs.getInt("galaxy4_bg_star_count", DEFAULT_BG_STAR_COUNT);
        } else {
            mBgStarCount = legacyPrefs.getInt("galaxy4_bg_star_count", DEFAULT_BG_STAR_COUNT);
            if (legacyPrefs.contains("galaxy4_bg_star_count")) {
                defaultPrefs.edit().putInt("galaxy4_bg_star_count", mBgStarCount).apply();
            }
        }

        if (defaultPrefs.contains("galaxy4_space_cloud_count")) {
            mSpaceCloudCount = defaultPrefs.getInt("galaxy4_space_cloud_count", DEFAULT_SPACE_CLOUD_COUNT);
        } else {
            mSpaceCloudCount = legacyPrefs.getInt("galaxy4_space_cloud_count", DEFAULT_SPACE_CLOUD_COUNT);
            if (legacyPrefs.contains("galaxy4_space_cloud_count")) {
                defaultPrefs.edit().putInt("galaxy4_space_cloud_count", mSpaceCloudCount).apply();
            }
        }

        mBgStarCount = MathUtils.clamp(mBgStarCount, MIN_BG_STAR_COUNT, MAX_BG_STAR_COUNT);
        mSpaceCloudCount = MathUtils.clamp(mSpaceCloudCount, MIN_SPACE_CLOUD_COUNT, MAX_SPACE_CLOUD_COUNT);
    }

    private void syncSettingsFromPreferencesIfNeeded(long now) {
        if (mContext == null) {
            return;
        }
        if (mLastSettingsSyncTime != 0L && (now - mLastSettingsSyncTime) < SETTINGS_SYNC_INTERVAL_MS) {
            return;
        }
        mLastSettingsSyncTime = now;

        SharedPreferences defaultPrefs = getPrefs();
        int prefBgStarCount = MathUtils.clamp(defaultPrefs.getInt("galaxy4_bg_star_count", mBgStarCount),
                MIN_BG_STAR_COUNT, MAX_BG_STAR_COUNT);
        int prefSpaceCloudCount = MathUtils.clamp(defaultPrefs.getInt("galaxy4_space_cloud_count", mSpaceCloudCount),
                MIN_SPACE_CLOUD_COUNT, MAX_SPACE_CLOUD_COUNT);

        if (prefBgStarCount != mBgStarCount) {
            mBgStarCount = prefBgStarCount;
            requestParticleRebuild();
        }
        if (prefSpaceCloudCount != mSpaceCloudCount) {
            mSpaceCloudCount = prefSpaceCloudCount;
            requestParticleRebuild();
        }

        mSceneData.particleSize = defaultPrefs.getInt("galaxy4_particle_size", 100) / 100.0f;
        mSceneData.particleOpacity = defaultPrefs.getInt("galaxy4_particle_opacity", 100) / 100.0f;
    }

    private void requestParticleRebuild() {
        mParticleDataDirty = true;
        mParticleBuffersDirty = true;
        mDynamicParticlesDirty = false;
        mSkipParticleAdvanceOnNextUpdate = true;
    }

    private void rebuildParticleData() {
        float screenWidth = mWidth;
        float screenHeight = mHeight;
        float wRatio = 1.0f;
        float hRatio = 1.0f;

        if (screenWidth > screenHeight) {
            wRatio = screenWidth / Math.max(1.0f, screenHeight);
            screenHeight = screenWidth;
        } else {
            hRatio = screenHeight / Math.max(1.0f, screenWidth);
            screenWidth = screenHeight;
        }

        float scale = GALAXY_RADIUS / Math.max(1.0f, screenWidth * 0.5f);

        mSceneData.spaceClouds = buildOrbitParticles(mSpaceCloudCount, scale);
        mSceneData.bgStars = buildOrbitParticles(mBgStarCount, scale);
        mSceneData.staticStars = buildStaticStars(wRatio, hRatio);
        mSceneData.bgStarCount = mBgStarCount;
        mSceneData.spaceCloudCount = mSpaceCloudCount;

        updateProjectionMatrix();
        mParticleDataDirty = false;
        mParticleBuffersDirty = true;
        mDynamicParticlesDirty = false;
        mSkipParticleAdvanceOnNextUpdate = true;
        Log.d(TAG, "粒子数据已重建: bg=" + mBgStarCount + ", cloud=" + mSpaceCloudCount);
    }

    private float[] buildOrbitParticles(int count, float scale) {
        float[] particles = new float[count * 3];
        for (int i = 0; i < count; i++) {
            float distance = Math.abs(randomGauss()) * GALAXY_RADIUS * 0.5f + mRandom.nextFloat() * 64.0f;
            distance = mapf(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, distance);
            float normalizedDistance = distance / GALAXY_RADIUS;
            float z = randomGauss() * 0.4f * (1.0f - normalizedDistance);

            if (distance > GALAXY_RADIUS * 0.15f) {
                z *= 0.6f * (1.0f - normalizedDistance);
            } else {
                z *= 0.72f;
            }

            int index = i * 3;
            particles[index] = mRandom.nextFloat() * TWO_PI;
            particles[index + 1] = distance;
            particles[index + 2] = z / 5.0f;
        }
        return particles;
    }

    private float[] buildStaticStars(float wRatio, float hRatio) {
        float[] particles = new float[STATIC_STAR_COUNT * 3];
        for (int i = 0; i < STATIC_STAR_COUNT; i++) {
            int index = i * 3;
            particles[index] = (mRandom.nextFloat() * 2.0f - 1.0f) * wRatio;
            particles[index + 1] = (mRandom.nextFloat() * 2.0f - 1.0f) * hRatio;
            particles[index + 2] = mRandom.nextFloat() * 9.0f + 1.0f;
        }
        return particles;
    }

    private void updateProjectionMatrix() {
        float aspect = (float) mWidth / Math.max(1, mHeight);
        Matrix.frustumM(mProjMatrix, 0, -aspect, aspect, -1, 1, 1, 100);

        float[] temp = new float[16];
        float[] rotMatrix = new float[16];
        float[] scaleMatrix = new float[16];

        Matrix.setRotateM(rotMatrix, 0, 180, 0, 1, 0);
        Matrix.multiplyMM(temp, 0, mProjMatrix, 0, rotMatrix, 0);

        Matrix.setIdentityM(scaleMatrix, 0);
        Matrix.scaleM(scaleMatrix, 0, -1, 1, 1);
        Matrix.multiplyMM(rotMatrix, 0, temp, 0, scaleMatrix, 0);

        Matrix.translateM(mSceneData.mvpMatrix, 0, rotMatrix, 0, 0, 0, 1);
    }

    private void advanceParticles() {
        for (int i = 0; i < mSpaceCloudCount; i++) {
            mSceneData.spaceClouds[i * 3] += SPACE_CLOUD_ROTATION_DELTA;
        }
        for (int i = 0; i < mBgStarCount; i++) {
            mSceneData.bgStars[i * 3] += BG_STAR_ROTATION_DELTA;
        }
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


    private void persistInt(String key, int value) {
        if (mContext == null) {
            return;
        }
        getPrefs().edit().putInt(key, value).apply();
    }

    private Context getAppContext() {
        Context appContext = mContext.getApplicationContext();
        return appContext != null ? appContext : mContext;
    }

    static final class SceneData {
        private float[] spaceClouds;
        private float[] bgStars;
        private float[] staticStars;
        private final float[] mvpMatrix = new float[16];
        private int bgStarCount;
        private int spaceCloudCount;
        private float timeSeconds;
        private float particleSize = 1.0f;
        private float particleOpacity = 1.0f;

        float[] getSpaceClouds() {
            return spaceClouds;
        }

        float[] getBgStars() {
            return bgStars;
        }

        float[] getStaticStars() {
            return staticStars;
        }

        float[] getMvpMatrix() {
            return mvpMatrix;
        }

        int getBgStarCount() {
            return bgStarCount;
        }

        int getSpaceCloudCount() {
            return spaceCloudCount;
        }

        float getTimeSeconds() {
            return timeSeconds;
        }

        int getStaticStarCount() { return STATIC_STAR_COUNT; }
        float getParticleSize() { return particleSize; }
        float getParticleOpacity() { return particleOpacity; }
    }
}