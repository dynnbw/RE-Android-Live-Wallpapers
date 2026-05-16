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
package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.reandroid.gles.GLESWallpaper;
import com.reandroid.settings.WallpaperSettings;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherState;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

import static com.reandroid.wallpaper.grass.GrassConstants.*;

/**
 * Grass 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责所有状态管理、天文计算、粒子/草叶动画逻辑，并通过 SceneData 向 GrassGL 暴露数据。
 */
class GrassScene {

    private static final float ONE_MINUTE_DAY_FRACTION = 1.0f / 1440.0f;
    private static final long CELESTIAL_CACHE_INTERVAL_MS = 60000L;

    // ---- Instance fields ----
    int mWidth, mHeight;
    private boolean mIsPreview;
    private boolean mInitialized = false;

    final Random mRandom = new Random(System.currentTimeMillis());
    private final GrassWindField mWindField = new GrassWindField();
    private final GrassBladeSystem mBladeSystem;
    private final GrassDayNightSystem mDayNightSystem = new GrassDayNightSystem();
    private final GrassRenderDataBuilder mRenderDataBuilder;

    // Sun state
    private float mXOffset = 0.0f;
    private int mSettingsHash = 0;

    // Settings
    private boolean mGrassEnabled = true;
    private boolean mNightInvert = false;
    private boolean mNightDesaturateGrass = false;
    private boolean mUseAccurateSun = false;
    private boolean mSunEnabled = false;
    private boolean mMoonEnabled = false;
    private boolean mProceduralSun = true;
    private float mGrassHeightScale = 1.0f;
    private float mGrassWidthScale = 1.0f;
    private float mGrassHardnessScale = 1.0f;
    private boolean mUseGrassTint = false;
    private float mGrassTintR = 1.0f, mGrassTintG = 1.0f, mGrassTintB = 1.0f;
    private float mGrassTintH = 0.0f, mGrassTintS = 0.0f, mGrassTintV = 1.0f;

    // Accurate sun / eclipse state
    private long mLastSolarEclipseUpdateMs = 0L;
    private float mSolarEclipseWeight = 0.0f;
    private long mLastCelestialComputeMs = 0L;
    private MoonCalculator.MoonData mCachedMoonData;

    int mBladeCount = DEFAULT_BLADE_COUNT;
    int mVertexCount, mIndexCount;

    // Dandelions / Fireflies
    Dandelion[] mDandelions;
    Firefly[] mFireflies;
    private int mDandelionCount = DEFAULT_DANDELION_COUNT;
    private int mFireflyCount = DEFAULT_FIREFLY_COUNT;
    private float mDandelionSpeedScale = 1.0f;
    private long mLastAnimTimeMs = 0L;
    private boolean mDandelionEnabled = false;
    private boolean mFireflyEnabled = false;

    // Weather-driven runtime overrides
    private WeatherCondition mWeatherCondition = WeatherCondition.D1_CLEAR;
    private boolean mHasWeatherNightOverride = false;
    private boolean mWeatherNightOverride = false;

    // Legacy particles
    private boolean mLegacyParticles = false;
    int legacyType = LEGACY_TYPE_DANDELION;
    int legacyDirection = 0;
    long legacyBlowTime = 0, legacyNow = 0;
    LegacyParticle[] legacyNormal = new LegacyParticle[LEGACY_MAX_NORMAL];
    LegacyParticle[] legacyExtras = new LegacyParticle[LEGACY_MAX_EXTRAS];

    // Rebuild signal
    private boolean mBladeIndexRebuildNeeded = false;
    private boolean mGrassGeometryDirty = true;

    // Cached SceneData (reused to avoid allocations)
    private final SceneData mSceneData = new SceneData();

    // Vulkan transient buffers (reused to avoid per-frame allocations)
    private final float[] mVKSkyParams = new float[6];
    private final float[] mVKMoonParams = new float[12];
    private float[] mVKGrassVertices = new float[0];
    private int mVKGrassFloatCount = 0;
    private final float[] mVKSunVerts = new float[30];
    private int mVKSunFloatCount = 0;
    private final float[] mVKMoonVerts = new float[30];
    private int mVKMoonFloatCount = 0;
    private float[] mVKDandelionVerts = new float[0];
    private int mVKDandelionFloatCount = 0;
    private float[] mVKFireflyVerts = new float[0];
    private int mVKFireflyFloatCount = 0;
    private float[] mVKFireflyFlareVerts = new float[0];
    private int mVKFireflyFlareFloatCount = 0;
    private int mVKTempSpriteFloatCount = 0;

    // ---- Constructor ----
    GrassScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBladeSystem = new GrassBladeSystem(mRandom, mWindField, width, height, mBladeCount);
        mRenderDataBuilder = new GrassRenderDataBuilder(new GrassRenderDataBuilder.LegacyParticleOps() {
            @Override
            public LegacyParticle createLegacyParticle(int type) {
                return GrassScene.this.createLegacyParticle(type);
            }

            @Override
            public void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
                GrassScene.this.flyLegacyFirefly(p, isInit);
            }

            @Override
            public void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
                GrassScene.this.flyLegacyDandelion(p, isInit);
            }
        });
    }

    // ---- Lifecycle ----

    void init(boolean isPreview) {
        if (mInitialized) return;
        mInitialized = true;
        mIsPreview = isPreview;

        mWindField.init(mRandom);
        updateSettingsFromPrefs();
        mBladeSystem.initBlades();
        syncBladeBuffersFromSystem(true);
        initDandelions();
        initFireflies();

        mDayNightSystem.initDefaultLocation();

        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        mXOffset = isPreview ? 0.5f : 0.0f;
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBladeSystem.setViewport(width, height);
        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, width, height, 0, -1.0f, 1.0f);
        mBladeSystem.updateBladePositionsForViewport();
        syncBladeBuffersFromSystem(false);
        mGrassGeometryDirty = true;
        initDandelions();
        initFireflies();
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    SceneData getSceneData() {
        return mSceneData;
    }

    boolean consumeBladeIndexRebuildNeeded() {
        boolean v = mBladeIndexRebuildNeeded;
        mBladeIndexRebuildNeeded = false;
        return v;
    }

    boolean isInitialized() {
        return mInitialized;
    }

    void setWeatherState(WeatherState state) {
        if (state == null || state.condition == null) {
            mWeatherCondition = WeatherCondition.D1_CLEAR;
            mHasWeatherNightOverride = false;
            return;
        }
        mWeatherCondition = state.condition;
        // Do not override local day/night from weather payload.
        // RS original uses local time / sun times for sky phase; weather only affects overlays.
        mHasWeatherNightOverride = false;
    }

    // ---- Per-frame master update ----

    void update(long animNowMs) {
        if (!mInitialized) return;

        updateSettingsFromPrefs();

        float dt = 0.016f;
        if (mLastAnimTimeMs > 0) {
            dt = (animNowMs - mLastAnimTimeMs) / 1000.0f;
            dt = clamp(dt, 0.0f, 0.05f);
        }
        mLastAnimTimeMs = animNowMs;

        long nowMs = System.currentTimeMillis();
        if (mDayNightSystem.getLastSunUpdateMs() == 0L || (nowMs - mDayNightSystem.getLastSunUpdateMs()) > 3600000L) {
            mDayNightSystem.updateSunTimes(nowMs);
        }

        float timeFrac = mDayNightSystem.timeFraction(mIsPreview, mUseAccurateSun);
        float newB;
        boolean isNight;

        if (mUseAccurateSun) {
            mDayNightSystem.updateAccurateWeights(nowMs);
            float[] accurate = mDayNightSystem.getAccurateWeights();
            newB = clamp(accurate[3] + 0.6f * (accurate[1] + accurate[2]), 0.0f, 1.0f);
            isNight = mDayNightSystem.getLastSunAltitude() < 0.0;

            // Compute moon data once, reuse for sun/moon/eclipse rendering
            Calendar now = Calendar.getInstance(mDayNightSystem.getTimeZone());
            MoonCalculator.MoonData moonData = getCachedMoonData(nowMs, now);
            updateSolarEclipseState(moonData);

            if (mSunEnabled) {
                computeSunPosition(now, moonData);
            } else {
                mSceneData.hasSunData = false;
                mSceneData.hasSolarEclipseOcclusion = false;
            }
            if (mMoonEnabled) {
                computeMoonData(now, moonData);
            } else {
                mSceneData.moonVisible = false;
            }
        } else {
            newB = mDayNightSystem.computeSimpleNewB(timeFrac);
            isNight = (timeFrac < mDayNightSystem.getDawn() || timeFrac > mDayNightSystem.getDusk());
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
            mSceneData.moonVisible = false;
        }

        newB = clamp(newB * GrassWeatherSystem.brightnessMultiplier(mWeatherCondition), 0.0f, 1.0f);
        if (mSceneData.hasSunData) {
            mSceneData.sunAlpha = clamp(mSceneData.sunAlpha * GrassWeatherSystem.sunAlphaScale(mWeatherCondition),
                0.0f, 1.0f);
        }
        if (mSceneData.moonVisible) {
            mSceneData.moonAlpha = clamp(mSceneData.moonAlpha * GrassWeatherSystem.moonAlphaScale(mWeatherCondition),
                0.0f, 1.0f);
            mSceneData.moonBrightness = clamp(
                mSceneData.moonBrightness * GrassWeatherSystem.moonBrightnessScale(mWeatherCondition),
                0.0f, 1.0f);
        }

        // Update blade angles using noise
        float dayNightWind = GrassWeatherSystem.windDayNightScale(mWeatherCondition, isNight);
        float noiseNow = SystemClock.uptimeMillis() * 0.00004f
            * GrassWeatherSystem.windTimeScale(mWeatherCondition) * dayNightWind;
        boolean bladeAnglesDirty = mBladeSystem.updateBladeAngles(noiseNow,
            GrassWeatherSystem.windAmplitudeScale(mWeatherCondition) * dayNightWind);

        boolean allowDandelion = GrassWeatherSystem.allowsDandelion(mWeatherCondition);
        boolean allowFirefly = GrassWeatherSystem.allowsFirefly(mWeatherCondition);
        float dandelionVisibility = (mDandelionEnabled && allowDandelion)
                ? computeDandelionVisibility(timeFrac) : 0.0f;
        float fireflyVisibility = (mFireflyEnabled && allowFirefly)
                ? computeFireflyVisibility(timeFrac) : 0.0f;
        float starVisibility = computeStarVisibility(timeFrac);

        // Update particle positions
        if (!mLegacyParticles) {
            if (dandelionVisibility > 0.001f && mDandelions != null) {
                GrassParticleSystem.updateDandelionPositions(mRandom, mDandelions, dt,
                        mDandelionSpeedScale, mWidth, mHeight);
            }
            if (fireflyVisibility > 0.001f && mFireflies != null) {
                GrassParticleSystem.updateFireflyPositions(mFireflies, dt, mWidth, mHeight);
            }
        } else {
            updateLegacyState(isNight, animNowMs);
        }

        // Populate SceneData
        mSceneData.grassEnabled = mGrassEnabled;
        mSceneData.nightInvert = mNightInvert;
        mSceneData.nightDesaturateGrass = mNightDesaturateGrass;
        mSceneData.useAccurateSun = mUseAccurateSun;
        mSceneData.sunEnabled = mSunEnabled;
        mSceneData.moonEnabled = mMoonEnabled;
        mSceneData.proceduralSunEnabled = mProceduralSun;
        mSceneData.grassHeightScale = mGrassHeightScale;
        mSceneData.grassWidthScale = mGrassWidthScale;
        mSceneData.grassHardnessScale = mGrassHardnessScale;
        mSceneData.useGrassTint = mUseGrassTint;
        mSceneData.grassTintH = mGrassTintH;
        mSceneData.grassTintS = mGrassTintS;
        mSceneData.grassTintV = mGrassTintV;
        mSceneData.dandelionEnabled = dandelionVisibility > 0.001f;
        mSceneData.fireflyEnabled = fireflyVisibility > 0.001f;
        mSceneData.dandelionVisibility = dandelionVisibility;
        mSceneData.fireflyVisibility = fireflyVisibility;
        mSceneData.starVisibility = starVisibility;
        mSceneData.legacyParticles = mLegacyParticles;
        mSceneData.blades = mBladeSystem.getBlades();
        mSceneData.dandelions = mDandelions;
        mSceneData.fireflies = mFireflies;
        mSceneData.legacyNormal = legacyNormal;
        mSceneData.legacyExtras = legacyExtras;
        mSceneData.legacyType = legacyType;
        mSceneData.legacyNow = legacyNow;
        mSceneData.timeFraction = timeFrac;
        mSceneData.dawn = mDayNightSystem.getDawn();
        mSceneData.morning = mDayNightSystem.getMorning();
        mSceneData.afternoon = mDayNightSystem.getAfternoon();
        mSceneData.dusk = mDayNightSystem.getDusk();
        mSceneData.newB = newB;
        mSceneData.isNight = isNight;
        mSceneData.weatherCondition = mWeatherCondition;
        System.arraycopy(mDayNightSystem.getAccurateWeights(), 0, mSceneData.accurateWeights, 0, 4);
        mSceneData.solarEclipseWeight = mSolarEclipseWeight;
        mSceneData.lastSunAltitude = mDayNightSystem.getLastSunAltitude();
        mSceneData.xDraw = mix(mWidth, 0.0f, mXOffset);
        mSceneData.dt = dt;
        mSceneData.animNowMs = animNowMs;
        mSceneData.bladeIndexRebuildNeeded = mBladeIndexRebuildNeeded;
        mSceneData.grassGeometryDirty = mGrassGeometryDirty || bladeAnglesDirty;
        mBladeIndexRebuildNeeded = false;
        mGrassGeometryDirty = false;
    }

    // ---- Private update helpers ----

    private void syncBladeBuffersFromSystem(boolean rebuildIndices) {
        mVertexCount = mBladeSystem.getVertexCount();
        mIndexCount = mBladeSystem.getIndexCount();
        mRenderDataBuilder.setGeometry(mWidth, mHeight, mVertexCount, mIndexCount, mBladeSystem.getBladeSizes());
        mGrassGeometryDirty = true;
        if (rebuildIndices) {
            mBladeIndexRebuildNeeded = true;
        }
    }

    private void updateLegacyState(boolean isNight, long animNowMs) {
        legacyNow = animNowMs;
        if (legacyBlowTime < legacyNow) {
            legacyDirection = (legacyDirection == 0) ? 1 : 0;
            legacyBlowTime = (long) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        }
        int newType = isNight ? LEGACY_TYPE_FIREFLY : LEGACY_TYPE_DANDELION;
        if (legacyType != newType) {
            legacyType = newType;
            for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
                legacyNormal[i] = createLegacyParticle(legacyType);
                legacyNormal[i].active = true;
            }
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                legacyExtras[i] = createLegacyParticle(legacyType);
                legacyExtras[i].active = true;
            }
        } else if (legacyType == LEGACY_TYPE_DANDELION) {
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                if (legacyExtras[i] != null) legacyExtras[i].active = true;
            }
        }
    }

    // ---- Sun / Moon position computation ----

    private void computeSunPosition(Calendar now, MoonCalculator.MoonData moonData) {
        SunCalculator sunCalculator = mDayNightSystem.getSunCalculator();
        if (sunCalculator == null) {
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
            return;
        }
        double hourAngleDeg = sunCalculator.computeHourAngle(now);
        float sunX = celestialXFromHourAngle(hourAngleDeg);
        float clampedAlt = clamp((float) mDayNightSystem.getLastSunAltitude(), 0.0f, 90.0f);
        float sunY = mHeight * (1.0f - clampedAlt / 90.0f);
        float sunSize = mWidth * 0.32f;
        float sunAlpha = clamp((float) ((mDayNightSystem.getLastSunAltitude() + 6.0) / 12.0), 0.0f, 1.0f);

        mSceneData.hasSunData = true;
        mSceneData.sunX = sunX;
        mSceneData.sunY = sunY;
        mSceneData.sunAlpha = sunAlpha;
        mSceneData.sunSize = sunSize;

        // Compute solar eclipse occlusion data
        if (moonData != null) {
            SolarEclipse eclipse = computeSolarEclipse(moonData, now);
            if (eclipse.fraction > 0.001f) {
                float moonX = moonXFromHourAngle(moonData.moonHourAngleDeg);
                float moonY = mHeight * (1.0f - clamp((float) moonData.moonAltitudeDeg, 0.0f, 90.0f) / 90.0f);
                mSceneData.hasSolarEclipseOcclusion = true;
                mSceneData.solarEclipseAtSun = eclipse;
                mSceneData.eclipseMoonX = moonX;
                mSceneData.eclipseMoonY = moonY;
                mSceneData.eclipseSunX = sunX;
                mSceneData.eclipseSunY = sunY;
                mSceneData.eclipseSunSize = sunSize;
                mSceneData.eclipseSunAlpha = sunAlpha;
                return;
            }
        }
        mSceneData.hasSolarEclipseOcclusion = false;
    }

    private void computeMoonData(Calendar now, MoonCalculator.MoonData data) {
        if (data == null || data.moonAltitudeDeg <= -2.0) {
            mSceneData.moonVisible = false;
            return;
        }
        double sunAlt = mUseAccurateSun ? mDayNightSystem.getLastSunAltitude() : data.sunAltitudeDeg;
        boolean isDaytime = sunAlt > 0.0;
        float moonX = moonXFromHourAngle(data.moonHourAngleDeg);
        float clampedAlt = clamp((float) data.moonAltitudeDeg, 0.0f, 90.0f);
        float moonY = mHeight * (1.0f - clampedAlt / 90.0f);
        float size = mWidth * 0.24f;
        float baseBrightness = clamp((float) ((data.moonAltitudeDeg + 2.0) / 30.0), 0.0f, 1.0f);
        MoonEclipse eclipse = computeMoonEclipse(data);

        mSceneData.moonVisible = true;
        mSceneData.moonPhaseAngle = (float) data.phaseAngleUtcDeg;
        mSceneData.moonX = moonX;
        mSceneData.moonY = moonY;
        mSceneData.moonSize = size;
        mSceneData.moonIsDaytime = isDaytime;
        mSceneData.moonBrightness = baseBrightness;
        mSceneData.moonAlpha = 1.0f;
        mSceneData.moonContrast = 1.0f;
        mSceneData.moonSaturation = 1.0f;
        mSceneData.moonBlueTint = 0.0f;
        mSceneData.moonEclipse = eclipse;
    }

    private float computeSimpleNewB(float now) {
        return mDayNightSystem.computeSimpleNewB(now);
    }

    private float computeDandelionVisibility(float now) {
        float dawn = mDayNightSystem.getDawn();
        float dusk = mDayNightSystem.getDusk();
        float dawnProgress = progressFromStart(now, dawn, ONE_MINUTE_DAY_FRACTION);
        if (dawnProgress >= 0.0f) {
            return clamp(dawnProgress, 0.0f, 1.0f);
        }
        float duskProgress = progressFromStart(now, dusk, ONE_MINUTE_DAY_FRACTION);
        if (duskProgress >= 0.0f) {
            return clamp(1.0f - duskProgress, 0.0f, 1.0f);
        }
        return isBetweenClock(now, dawn, dusk) ? 1.0f : 0.0f;
    }

    private float computeFireflyVisibility(float now) {
        float dawn = mDayNightSystem.getDawn();
        float dusk = mDayNightSystem.getDusk();
        float duskProgress = progressFromStart(now, dusk, ONE_MINUTE_DAY_FRACTION);
        if (duskProgress >= 0.0f) {
            return clamp(duskProgress, 0.0f, 1.0f);
        }
        float dawnProgress = progressFromStart(now, dawn, ONE_MINUTE_DAY_FRACTION);
        if (dawnProgress >= 0.0f) {
            return clamp(1.0f - dawnProgress, 0.0f, 1.0f);
        }
        return isBetweenClock(now, dusk, dawn) ? 1.0f : 0.0f;
    }

    private float computeStarVisibility(float now) {
        float dawn = mDayNightSystem.getDawn();
        float morning = mDayNightSystem.getMorning();
        float afternoon = mDayNightSystem.getAfternoon();
        float dusk = mDayNightSystem.getDusk();

        if (isBetweenClock(now, dawn, morning)) {
            return clamp(1.0f - clockProgress(now, dawn, morning), 0.0f, 1.0f);
        }
        if (isBetweenClock(now, morning, dusk)) {
            return 0.0f;
        }

        float nightFadeEnd = wrap01(dusk + ONE_MINUTE_DAY_FRACTION);
        if (isBetweenClock(now, dusk, nightFadeEnd)) {
            return clamp(clockProgress(now, dusk, nightFadeEnd), 0.0f, 1.0f);
        }

        if (isBetweenClock(now, afternoon, dusk)) {
            return 0.0f;
        }

        return 1.0f;
    }

    private float progressFromStart(float now, float start, float duration) {
        if (duration <= 0.0f) {
            return -1.0f;
        }
        float delta = now - start;
        if (delta < 0.0f) {
            delta += 1.0f;
        }
        if (delta < 0.0f || delta > duration) {
            return -1.0f;
        }
        return delta / duration;
    }

    private float wrap01(float value) {
        if (value >= 1.0f) {
            return value - 1.0f;
        }
        if (value < 0.0f) {
            return value + 1.0f;
        }
        return value;
    }

    private float celestialXFromHourAngle(double hourAngleDeg) {
        float ratio = (float) ((hourAngleDeg + 180.0) / 360.0);
        float ratioExtended = ratio * 1.4f - 0.1f;
        return mWidth * clamp(ratioExtended, -0.1f, 1.1f);
    }

    private float moonXFromHourAngle(double hourAngleDeg) {
        float ratio = (float) ((hourAngleDeg + 180.0) / 360.0);
        return mWidth * clamp(ratio, -0.1f, 1.1f);
    }

    // ---- Settings management ----

    private void updateSettingsFromPrefs() {
        boolean legacy = WallpaperSettings.getBoolean("pref_grass_legacy_particles", false);
        if (legacy != mLegacyParticles) {
            mLegacyParticles = legacy;
            if (mLegacyParticles) initLegacyParticles();
        }
        WallpaperSettings.GrassTint tint = WallpaperSettings.getGrassTint();
        int newBladeCount = WallpaperSettings.getGrassBladeCount(DEFAULT_BLADE_COUNT);
        boolean newEnabled = WallpaperSettings.isGrassEnabled(true);
        boolean newNightInvert = WallpaperSettings.isNightInvert(false);
        boolean newNightDesaturate = WallpaperSettings.isGrassNightDesaturateEnabled(false);
        boolean newAccurateSun = WallpaperSettings.isAccurateSunEnabled(false);
        boolean newSunEnabled = WallpaperSettings.isSunEnabled(true);
        boolean newMoonEnabled = WallpaperSettings.isMoonEnabled(true);
        boolean newProceduralSun = WallpaperSettings.isProceduralSunEnabled(true);
        float newHeightScale = WallpaperSettings.getGrassHeightScale(1.0f);
        float newWidthScale = WallpaperSettings.getGrassWidthScale(1.0f);
        float newHardnessScale = WallpaperSettings.getGrassHardnessScale(1.0f);
        boolean newDandelionEnabled = WallpaperSettings.isDandelionEnabled(false);
        boolean newFireflyEnabled = WallpaperSettings.isFireflyEnabled(false);
        int newDandelionCount = WallpaperSettings.getDandelionCount(DEFAULT_DANDELION_COUNT);
        int newFireflyCount = WallpaperSettings.getFireflyCount(DEFAULT_FIREFLY_COUNT);
        float newDandelionSpeedScale = WallpaperSettings.getDandelionSpeedScale(2.0f);

        int hash = 17;
        hash = 31 * hash + newBladeCount;
        hash = 31 * hash + (newEnabled ? 1 : 0);
        hash = 31 * hash + (newNightInvert ? 1 : 0);
        hash = 31 * hash + (newNightDesaturate ? 1 : 0);
        hash = 31 * hash + (newAccurateSun ? 1 : 0);
        hash = 31 * hash + (newSunEnabled ? 1 : 0);
        hash = 31 * hash + (newMoonEnabled ? 1 : 0);
        hash = 31 * hash + (newProceduralSun ? 1 : 0);
        hash = 31 * hash + Float.floatToIntBits(newHeightScale);
        hash = 31 * hash + Float.floatToIntBits(newWidthScale);
        hash = 31 * hash + Float.floatToIntBits(newHardnessScale);
        hash = 31 * hash + (newDandelionEnabled ? 1 : 0);
        hash = 31 * hash + (newFireflyEnabled ? 1 : 0);
        hash = 31 * hash + newDandelionCount;
        hash = 31 * hash + newFireflyCount;
        hash = 31 * hash + Float.floatToIntBits(newDandelionSpeedScale);
        hash = 31 * hash + (tint.enabled ? 1 : 0);
        hash = 31 * hash + tint.color;

        if (hash == mSettingsHash) return;
        mSettingsHash = hash;

        mGrassEnabled = newEnabled;
        mNightInvert = newNightInvert;
        mNightDesaturateGrass = newNightDesaturate;
        mUseAccurateSun = newAccurateSun;
        mSunEnabled = newSunEnabled;
        mMoonEnabled = newMoonEnabled;
        mProceduralSun = newProceduralSun;
        mGrassHeightScale = newHeightScale;
        mGrassWidthScale = newWidthScale;
        mGrassHardnessScale = newHardnessScale;
        mDandelionEnabled = newDandelionEnabled;
        mFireflyEnabled = newFireflyEnabled;
        mDandelionCount = Math.max(1, newDandelionCount);
        mFireflyCount = Math.max(1, newFireflyCount);
        mDandelionSpeedScale = newDandelionSpeedScale;
        mUseGrassTint = tint.enabled;
        mGrassTintR = ((tint.color >> 16) & 0xFF) / 255.0f;
        mGrassTintG = ((tint.color >> 8) & 0xFF) / 255.0f;
        mGrassTintB = (tint.color & 0xFF) / 255.0f;
        float[] hsv = rgbToHsb(mGrassTintR, mGrassTintG, mGrassTintB);
        mGrassTintH = hsv[0];
        mGrassTintS = hsv[1];
        mGrassTintV = hsv[2];

        if (newBladeCount > 0 && newBladeCount != mBladeCount) {
            mBladeCount = newBladeCount;
            mBladeSystem.setBladeCount(mBladeCount);
            mBladeSystem.initBlades();
            syncBladeBuffersFromSystem(true);
        }
        if (!mLegacyParticles) {
            if (mDandelions == null || mDandelions.length != mDandelionCount) initDandelions();
            if (mFireflies == null || mFireflies.length != mFireflyCount) initFireflies();
        }
    }

    // ---- Blade methods ----

    private void initBlades() {
        mBladeSystem.setBladeCount(mBladeCount);
        mBladeSystem.initBlades();
        syncBladeBuffersFromSystem(true);
    }

    private void computeBladeBufferCounts() {
        syncBladeBuffersFromSystem(false);
    }

    private void updateBlades() {
        mBladeSystem.setViewport(mWidth, mHeight);
        mBladeSystem.updateBladePositionsForViewport();
        syncBladeBuffersFromSystem(false);
    }

    private void createBlade(Blade blade) {
        // Moved to GrassBladeSystem.
    }

    // ---- Dandelion / Firefly methods ----

    private void initDandelions() {
        mDandelions = GrassParticleSystem.initDandelions(mRandom, mDandelionCount, mWidth, mHeight);
    }

    void resetDandelion(Dandelion d, boolean randomX) {
        GrassParticleSystem.resetDandelion(mRandom, d, randomX, mWidth, mHeight);
    }

    private void initFireflies() {
        mFireflies = GrassParticleSystem.initFireflies(mRandom, mFireflyCount, mWidth, mHeight);
    }

    // ---- Legacy particle methods ----

    private void initLegacyParticles() {
        legacyNow = SystemClock.uptimeMillis();
        legacyType = LEGACY_TYPE_DANDELION;
        legacyDirection = 0;
        legacyBlowTime = (long) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            legacyNormal[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
        }
        for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
            legacyExtras[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
            legacyExtras[i].active = true;
        }
    }

    LegacyParticle createLegacyParticle(int type) {
        return GrassParticleSystem.createLegacyParticle(mRandom, legacyNow, legacyDirection,
                type, mWidth, mHeight);
    }

    void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
        GrassParticleSystem.flyLegacyFirefly(mRandom, p, isInit, legacyNow, legacyDirection, mWidth, mHeight);
    }

    void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
        GrassParticleSystem.flyLegacyDandelion(mRandom, p, isInit, legacyNow, legacyDirection, mWidth, mHeight);
    }

    // ---- Sun time helpers ----

    private float timeFraction() {
        return mDayNightSystem.timeFraction(mIsPreview, mUseAccurateSun);
    }

    private void updateSunTimes() {
        mDayNightSystem.updateSunTimes(System.currentTimeMillis());
    }

    // ---- Accurate sun / sky weights ----

    private void updateAccurateWeights() {
        mDayNightSystem.updateAccurateWeights(System.currentTimeMillis());
    }

    private boolean isBetweenClock(float now, float start, float end) {
        return GrassAstronomyCalculator.isBetweenClock(now, start, end);
    }

    private float clockProgress(float now, float start, float end) {
        return GrassAstronomyCalculator.clockProgress(now, start, end);
    }

    private void updateLocationFromSystem(long nowMs) {
        mDayNightSystem.updateLocationFromSystem(nowMs);
    }

    private void updateSolarEclipseState(MoonCalculator.MoonData data) {
        if (!mUseAccurateSun) { mSolarEclipseWeight = 0.0f; return; }
        long nowMs = System.currentTimeMillis();
        if (mLastSolarEclipseUpdateMs != 0L && (nowMs - mLastSolarEclipseUpdateMs) < 15000L) return;
        if (data == null) {
            mSolarEclipseWeight = 0.0f;
            mLastSolarEclipseUpdateMs = nowMs;
            return;
        }
        Calendar now = Calendar.getInstance(mDayNightSystem.getTimeZone());
        SolarEclipse eclipse = computeSolarEclipse(data, now);
        float[] accurateWeights = mDayNightSystem.getAccurateWeights();
        float dayVisibility = clamp(accurateWeights[3] + 0.45f * (accurateWeights[1] + accurateWeights[2]),
                0.0f, 1.0f);
        float eased = smoothstep(0.0f, 1.0f, eclipse.fraction);
        mSolarEclipseWeight = clamp(eased * dayVisibility, 0.0f, 1.0f);
        mLastSolarEclipseUpdateMs = nowMs;
    }

    private MoonCalculator.MoonData getCachedMoonData(long nowMs, Calendar now) {
        if (mDayNightSystem.getSunCalculator() == null) {
            mCachedMoonData = null;
            return null;
        }

        if (mCachedMoonData != null && (nowMs - mLastCelestialComputeMs) < CELESTIAL_CACHE_INTERVAL_MS) {
            return mCachedMoonData;
        }

        Location location = mDayNightSystem.getLocation();
        mCachedMoonData = MoonCalculator.compute(now, location.getLatitude(), location.getLongitude());
        mLastCelestialComputeMs = nowMs;
        return mCachedMoonData;
    }

    // ---- Astronomical computation ----

    private SolarEclipse computeSolarEclipse(MoonCalculator.MoonData data, Calendar now) {
        return GrassAstronomyCalculator.computeSolarEclipse(data, now,
            SOLAR_MEAN_ANGULAR_RADIUS_DEG,
            LUNAR_MEAN_ANGULAR_RADIUS_DEG,
            SOLAR_ECLIPSE_MODEL_TOLERANCE_DEG);
    }

    private MoonEclipse computeMoonEclipse(MoonCalculator.MoonData data) {
        return GrassAstronomyCalculator.computeMoonEclipse(data);
    }

    // ---- Noise ----

    private void initNoise() {
        mWindField.init(mRandom);
    }

    // ---- Math utilities ----

    float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    float[] computeSkyParams(SceneData sd) {
        return computeVKSkyParams(sd);
    }

    float[] buildMoonParams(SceneData sd) {
        return buildMoonParamsForVK(sd);
    }

    float[] buildGrassVertexArray(SceneData sd) {
        return buildGrassVertexArrayForVK(sd);
    }

    boolean wasGrassVertexArrayUpdated() {
        return mRenderDataBuilder.wasGrassVertexArrayUpdated();
    }

    int getGrassVertexCount() {
        return getVKGrassVertexCount();
    }

    int getSunVertexCount() {
        return getVKSunVertexCount();
    }

    int getDandelionVertexCount() {
        return getVKDandelionVertexCount();
    }

    int getFireflyVertexCount() {
        return getVKFireflyVertexCount();
    }

    int getFireflyFlareVertexCount() {
        return getVKFireflyFlareVertexCount();
    }

    int getMoonVertexCount() {
        return getVKMoonVertexCount();
    }

    short[] buildGrassIndexArray() {
        return buildGrassIndexArrayForVK();
    }

    float[] buildSunSpriteVertices(SceneData sd) {
        return buildSunSpriteVerticesForVK(sd);
    }

    float[] buildMoonSpriteVertices(SceneData sd) {
        return buildMoonSpriteVerticesForVK(sd);
    }

    float[] buildDandelionSpriteVertices(SceneData sd) {
        return buildDandelionSpriteVerticesForVK(sd);
    }

    float[] buildFireflySpriteVertices(SceneData sd) {
        return buildFireflySpriteVerticesForVK(sd);
    }

    float[] buildFireflyFlareSpriteVertices(SceneData sd) {
        return buildFireflyFlareSpriteVerticesForVK(sd);
    }

    float[] computeVKSkyParams(SceneData sd) {
        return mRenderDataBuilder.computeSkyParams(sd);
    }

    float[] buildMoonParamsForVK(SceneData sd) {
        return mRenderDataBuilder.buildMoonParams(sd);
    }

    float[] buildGrassVertexArrayForVK(SceneData sd) {
        return mRenderDataBuilder.buildGrassVertexArray(sd);
    }

    int getVKGrassVertexCount() {
        return mRenderDataBuilder.getGrassVertexCount();
    }

    int getVKSunVertexCount() {
        return mRenderDataBuilder.getSunVertexCount();
    }

    int getVKDandelionVertexCount() {
        return mRenderDataBuilder.getDandelionVertexCount();
    }

    int getVKFireflyVertexCount() {
        return mRenderDataBuilder.getFireflyVertexCount();
    }

    int getVKFireflyFlareVertexCount() {
        return mRenderDataBuilder.getFireflyFlareVertexCount();
    }

    int getVKMoonVertexCount() {
        return mRenderDataBuilder.getMoonVertexCount();
    }

    short[] buildGrassIndexArrayForVK() {
        return mRenderDataBuilder.buildGrassIndexArray();
    }

    float[] buildSunSpriteVerticesForVK(SceneData sd) {
        return mRenderDataBuilder.buildSunSpriteVertices(sd);
    }

    float[] buildMoonSpriteVerticesForVK(SceneData sd) {
        return mRenderDataBuilder.buildMoonSpriteVertices(sd);
    }

    float[] buildDandelionSpriteVerticesForVK(SceneData sd) {
        return mRenderDataBuilder.buildDandelionSpriteVertices(sd);
    }

    float[] buildFireflySpriteVerticesForVK(SceneData sd) {
        return mRenderDataBuilder.buildFireflySpriteVertices(sd);
    }

    float[] buildFireflyFlareSpriteVerticesForVK(SceneData sd) {
        return mRenderDataBuilder.buildFireflyFlareSpriteVertices(sd);
    }

    int hsbToRgb(float h, float s, float b) {
        float red = 0.0f, green = 0.0f, blue = 0.0f;
        float hf = (h - (int) h) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = b * (1.0f - s);
        float qv = b * (1.0f - s * f);
        float tv = b * (1.0f - s * (1.0f - f));
        switch (ihf) {
            case 0: red = b; green = tv; blue = pv; break;
            case 1: red = qv; green = b; blue = pv; break;
            case 2: red = pv; green = b; blue = tv; break;
            case 3: red = pv; green = qv; blue = b; break;
            case 4: red = tv; green = pv; blue = b; break;
            case 5: red = b; green = pv; blue = qv; break;
        }
        return Color.argb(255, (int) (red * 255), (int) (green * 255), (int) (blue * 255));
    }

    float[] rgbToHsb(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta == 0.0f) { h = 0.0f; }
        else if (max == r) { h = ((g - b) / delta) % 6.0f; }
        else if (max == g) { h = ((b - r) / delta) + 2.0f; }
        else { h = ((r - g) / delta) + 4.0f; }
        h /= 6.0f;
        if (h < 0.0f) h += 1.0f;
        float s = max == 0.0f ? 0.0f : (delta / max);
        return new float[]{h, s, max};
    }

    // ---- Random helpers ----

    float random(float range) {
        return mRandom.nextFloat() * range;
    }

    float random(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }
}
