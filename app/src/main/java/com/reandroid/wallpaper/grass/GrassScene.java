package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.opengl.Matrix;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.settings.WallpaperSettings;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

/**
 * Grass 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责所有状态管理、天文计算、粒子/草叶动画逻辑，并通过 SceneData 向 GrassGL 暴露数据。
 */
class GrassScene {

    // ---- Constants ----
    static final int DEFAULT_BLADE_COUNT = 200;
    private static final float TESSELATION = 0.5f;
    static final float HALF_TESSELATION = 0.25f;
    static final float MAX_BEND = 0.09f;
    private static final float SECONDS_IN_DAY = 86400.0f;
    private static final float SOLAR_MEAN_ANGULAR_RADIUS_DEG = 0.2665f;
    private static final float LUNAR_MEAN_ANGULAR_RADIUS_DEG = 0.2727f;
    private static final float SOLAR_ECLIPSE_MODEL_TOLERANCE_DEG = 0.75f;
    static final float SUN_PHOTOSPHERE_SCALE = 0.88f;
    private static final int B = 0x100, BM = 0xff, N = 0x1000;

    // Legacy particle constants
    static final int LEGACY_MAX_NORMAL = 10;
    static final int LEGACY_MAX_EXTRAS = 50;
    static final float LEGACY_SPEED = 0.1f / 100f;
    private static final float LEGACY_SPEED_VARIANCE = 0.3f / 1000f;
    static final int LEGACY_MAX_DELAY = 5000;
    static final int LEGACY_MAX_STAY = 5000;
    static final int LEGACY_MAX_FLARE = 1000;
    static final int LEGACY_MAX_INTERVAL = 5000;
    static final float LEGACY_INTERVAL_VARIANCE = 0.3f;
    private static final int LEGACY_MAX_BLOW_INTERVAL = 180000;
    static final int LEGACY_TYPE_DANDELION = 0;
    static final int LEGACY_TYPE_FIREFLY = 1;

    static final int DEFAULT_DANDELION_COUNT = 10;
    static final int DEFAULT_FIREFLY_COUNT = 16;
    static final float DANDELION_SIZE_SCALE = 2.2f;
    static final float FIREFLY_SIZE_SCALE = 6.0f;
    private static final float DANDELION_SPEED_SCALE = 1.6f;

    // ---- Inner data classes ----

    static class Blade {
        float angle;
        int size;
        float xPos, yPos, offset, scale, lengthX, lengthY, hardness;
        float h, s, b;
        float turbulencex;
    }

    static class Dandelion {
        float x, y, speed, size, swayPhase, swaySpeed, rotationDeg;
    }

    static class Firefly {
        float x, y, vx, vy, size, phase, flickerSpeed;
    }

    static class LegacyParticle {
        int type;
        boolean active;
        float angle;
        int bladeNum, sizeNum, texture;
        long startTime, stayEndTime, silentEndTime, flareEndTime;
        float originX, originY, dx, dy;

        LegacyParticle() {}
    }

    static class MoonEclipse {
        final int type;
        final float fraction, phase, shadowOffsetX, shadowOffsetY;

        MoonEclipse(int t, float fr, float ph, float sox, float soy) {
            type = t; fraction = fr; phase = ph; shadowOffsetX = sox; shadowOffsetY = soy;
        }
    }

    static class SolarEclipse {
        final float fraction, phase, moonRadiusRatio;

        SolarEclipse(float fr, float ph, float mrr) {
            fraction = fr; phase = ph; moonRadiusRatio = mrr;
        }
    }

    // ---- SceneData: mutable snapshot read by GrassGL each frame ----
    static class SceneData {
        final float[] projectionMatrix = new float[16];

        // Settings
        boolean grassEnabled, nightInvert, nightDesaturateGrass;
        boolean useAccurateSun, sunEnabled, moonEnabled;
        float grassHeightScale, grassWidthScale, grassHardnessScale;
        boolean useGrassTint;
        float grassTintH, grassTintS, grassTintV;
        boolean dandelionEnabled, fireflyEnabled, legacyParticles;

        // Particle/blade arrays (GrassGL reads these; GrassScene owns/updates them)
        Blade[] blades;
        Dandelion[] dandelions;
        Firefly[] fireflies;
        LegacyParticle[] legacyNormal;
        LegacyParticle[] legacyExtras;
        int legacyType;
        long legacyNow;

        // Time-of-day (simple non-accurate mode)
        float timeFraction, dawn, morning, afternoon, dusk;
        float newB;
        boolean isNight;

        // Accurate sun/sky
        final float[] accurateWeights = new float[4];
        float solarEclipseWeight;
        double lastSunAltitude;

        // Sun position (accurate mode)
        boolean hasSunData;
        float sunX, sunY, sunAlpha, sunSize;

        // Solar eclipse occlusion overlay at sun disc
        boolean hasSolarEclipseOcclusion;
        SolarEclipse solarEclipseAtSun;
        float eclipseMoonX, eclipseMoonY, eclipseSunX, eclipseSunY, eclipseSunSize, eclipseSunAlpha;

        // Moon (accurate mode)
        boolean moonVisible;
        float moonPhaseAngle, moonX, moonY, moonSize;
        boolean moonIsDaytime;
        float moonBrightness, moonAlpha, moonContrast, moonSaturation, moonBlueTint;
        MoonEclipse moonEclipse;

        // Per-frame animation
        float xDraw, dt;
        long animNowMs;

        // Signals GrassGL to rebuild blade index/vertex buffers
        boolean bladeIndexRebuildNeeded;
    }

    // ---- Instance fields ----
    int mWidth, mHeight;
    private boolean mIsPreview;
    private boolean mInitialized = false;

    // Noise tables (Perlin 2D)
    private final int[] p = new int[B + B + 2];
    private final float[][] g2 = new float[B + B + 2][2];

    final Random mRandom = new Random(System.currentTimeMillis());

    // Location and sun state
    private final Location mLocation = new Location("grass_wallpaper");
    private TimeZone mTimeZone = TimeZone.getDefault();
    SunCalculator mSunCalculator;
    private float mDawn, mMorning, mAfternoon, mDusk;
    private long mLastSunUpdateMs = 0L;
    private float mXOffset = 0.0f;
    private int mSettingsHash = 0;

    // Settings
    private boolean mGrassEnabled = true;
    private boolean mNightInvert = false;
    private boolean mNightDesaturateGrass = false;
    private boolean mUseAccurateSun = false;
    private boolean mSunEnabled = false;
    private boolean mMoonEnabled = false;
    private float mGrassHeightScale = 1.0f;
    private float mGrassWidthScale = 1.0f;
    private float mGrassHardnessScale = 1.0f;
    private boolean mUseGrassTint = false;
    private float mGrassTintR = 1.0f, mGrassTintG = 1.0f, mGrassTintB = 1.0f;
    private float mGrassTintH = 0.0f, mGrassTintS = 0.0f, mGrassTintV = 1.0f;

    // Accurate sun weights / eclipse state
    private final float[] mAccurateWeights = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
    private long mLastWeightUpdateMs = 0L;
    private double mLastSunAltitude = 0.0;
    private double mLastSunriseHour = -1.0, mLastSunsetHour = -1.0;
    private double mLastSunriseOfficialHour = -1.0, mLastSunsetOfficialHour = -1.0;
    private long mLastLocationUpdateMs = 0L;
    private long mLastSolarEclipseUpdateMs = 0L;
    private float mSolarEclipseWeight = 0.0f;

    // Blades
    Blade[] mBlades;
    int[] mBladeSizes;
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

    // Legacy particles
    private boolean mLegacyParticles = false;
    int legacyType = LEGACY_TYPE_DANDELION;
    int legacyDirection = 0;
    long legacyBlowTime = 0, legacyNow = 0;
    LegacyParticle[] legacyNormal = new LegacyParticle[LEGACY_MAX_NORMAL];
    LegacyParticle[] legacyExtras = new LegacyParticle[LEGACY_MAX_EXTRAS];

    // Rebuild signal
    private boolean mBladeIndexRebuildNeeded = false;

    // Cached SceneData (reused to avoid allocations)
    private final SceneData mSceneData = new SceneData();

    // ---- Constructor ----
    GrassScene(int width, int height) {
        mWidth = width;
        mHeight = height;
        mLocation.setLatitude(37.7749f);
        mLocation.setLongitude(-122.4194f);
    }

    // ---- Lifecycle ----

    void init(boolean isPreview) {
        if (mInitialized) return;
        mInitialized = true;
        mIsPreview = isPreview;

        initNoise();
        updateSettingsFromPrefs();
        initBlades();
        initDandelions();
        initFireflies();

        mSunCalculator = new SunCalculator(mLocation, mTimeZone.getID());
        updateSunTimes();

        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        mXOffset = isPreview ? 0.5f : 0.0f;
    }

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        Matrix.orthoM(mSceneData.projectionMatrix, 0, 0, width, height, 0, -1.0f, 1.0f);
        updateBlades();
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

        if (mLastSunUpdateMs == 0L || (System.currentTimeMillis() - mLastSunUpdateMs) > 3600000L) {
            updateSunTimes();
        }

        float timeFrac = timeFraction();
        float newB;
        boolean isNight;

        if (mUseAccurateSun) {
            updateAccurateWeights();
            newB = clamp(mAccurateWeights[3] + 0.6f * (mAccurateWeights[1] + mAccurateWeights[2]), 0.0f, 1.0f);
            isNight = mLastSunAltitude < 0.0;

            // Compute moon data once, reuse for sun/moon/eclipse rendering
            Calendar now = Calendar.getInstance(mTimeZone);
            MoonCalculator.MoonData moonData = mSunCalculator != null
                    ? MoonCalculator.compute(now, mLocation.getLatitude(), mLocation.getLongitude())
                    : null;
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
            newB = computeSimpleNewB(timeFrac);
            isNight = (timeFrac < mDawn || timeFrac > mDusk);
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
            mSceneData.moonVisible = false;
        }

        // Update blade angles using noise
        float noiseNow = SystemClock.uptimeMillis() * 0.00004f;
        updateBladeAngles(noiseNow);

        // Update particle positions
        if (!mLegacyParticles) {
            if (!isNight && mDandelionEnabled && mDandelions != null) {
                updateDandelionPositions(dt);
            }
            if (isNight && mFireflyEnabled && mFireflies != null) {
                updateFireflyPositions(dt);
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
        mSceneData.grassHeightScale = mGrassHeightScale;
        mSceneData.grassWidthScale = mGrassWidthScale;
        mSceneData.grassHardnessScale = mGrassHardnessScale;
        mSceneData.useGrassTint = mUseGrassTint;
        mSceneData.grassTintH = mGrassTintH;
        mSceneData.grassTintS = mGrassTintS;
        mSceneData.grassTintV = mGrassTintV;
        mSceneData.dandelionEnabled = mDandelionEnabled;
        mSceneData.fireflyEnabled = mFireflyEnabled;
        mSceneData.legacyParticles = mLegacyParticles;
        mSceneData.blades = mBlades;
        mSceneData.dandelions = mDandelions;
        mSceneData.fireflies = mFireflies;
        mSceneData.legacyNormal = legacyNormal;
        mSceneData.legacyExtras = legacyExtras;
        mSceneData.legacyType = legacyType;
        mSceneData.legacyNow = legacyNow;
        mSceneData.timeFraction = timeFrac;
        mSceneData.dawn = mDawn;
        mSceneData.morning = mMorning;
        mSceneData.afternoon = mAfternoon;
        mSceneData.dusk = mDusk;
        mSceneData.newB = newB;
        mSceneData.isNight = isNight;
        System.arraycopy(mAccurateWeights, 0, mSceneData.accurateWeights, 0, 4);
        mSceneData.solarEclipseWeight = mSolarEclipseWeight;
        mSceneData.lastSunAltitude = mLastSunAltitude;
        mSceneData.xDraw = mix(mWidth, 0.0f, mXOffset);
        mSceneData.dt = dt;
        mSceneData.animNowMs = animNowMs;
        mSceneData.bladeIndexRebuildNeeded = mBladeIndexRebuildNeeded;
        mBladeIndexRebuildNeeded = false;
    }

    // ---- Private update helpers ----

    private void updateBladeAngles(float noiseNow) {
        if (mBlades == null) return;
        for (Blade blade : mBlades) {
            float newAngle = (turbulencef2(blade.turbulencex, noiseNow, 4.0f) - 0.5f) * 0.5f;
            blade.angle = clamp(blade.angle + (newAngle + blade.offset - blade.angle) * 0.15f,
                    -MAX_BEND, MAX_BEND);
        }
    }

    private void updateDandelionPositions(float dt) {
        for (Dandelion d : mDandelions) {
            d.x += d.speed * DANDELION_SPEED_SCALE * mDandelionSpeedScale * dt;
            if (d.x > mWidth + d.size) {
                resetDandelion(d, false);
            }
        }
    }

    private void updateFireflyPositions(float dt) {
        for (Firefly f : mFireflies) {
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            if (f.x < 0) f.x = mWidth;
            if (f.x > mWidth) f.x = 0;
            if (f.y < 0) f.y = mHeight;
            if (f.y > mHeight) f.y = 0;
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
        if (mSunCalculator == null) {
            mSceneData.hasSunData = false;
            mSceneData.hasSolarEclipseOcclusion = false;
            return;
        }
        double hourAngleDeg = mSunCalculator.computeHourAngle(now);
        float sunX = celestialXFromHourAngle(hourAngleDeg);
        float clampedAlt = clamp((float) mLastSunAltitude, 0.0f, 90.0f);
        float sunY = mHeight * (1.0f - clampedAlt / 90.0f);
        float sunSize = mWidth * 0.32f;
        float sunAlpha = clamp((float) ((mLastSunAltitude + 6.0) / 12.0), 0.0f, 1.0f);

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
        double sunAlt = mUseAccurateSun ? mLastSunAltitude : data.sunAltitudeDeg;
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
        if (now >= 0.0f && now < mDawn) return 0.0f;
        if (now >= mDawn && now <= mMorning) {
            float half = mDawn + (mMorning - mDawn) * 0.5f;
            return now <= half ? normf(mDawn, half, now) : 1.0f;
        }
        if (now > mMorning && now < mAfternoon) return 1.0f;
        if (now >= mAfternoon && now <= mDusk) {
            float half = mAfternoon + (mDusk - mAfternoon) * 0.5f;
            return now <= half ? (1.0f - normf(mAfternoon, half, now)) : 0.0f;
        }
        return 0.0f;
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
            initBlades();
        }
        if (!mLegacyParticles) {
            if (mDandelions == null || mDandelions.length != mDandelionCount) initDandelions();
            if (mFireflies == null || mFireflies.length != mFireflyCount) initFireflies();
        }
    }

    // ---- Blade methods ----

    private void initBlades() {
        mBlades = new Blade[mBladeCount];
        mBladeSizes = new int[mBladeCount];
        for (int i = 0; i < mBladeCount; i++) {
            Blade blade = new Blade();
            createBlade(blade);
            mBlades[i] = blade;
            mBladeSizes[i] = blade.size;
        }
        computeBladeBufferCounts();
        mBladeIndexRebuildNeeded = true;
    }

    private void computeBladeBufferCounts() {
        mVertexCount = 0;
        mIndexCount = 0;
        for (int size : mBladeSizes) {
            mIndexCount += size * 2 * 3;
            mVertexCount += size + 2;
        }
    }

    private void updateBlades() {
        if (mBlades == null) return;
        for (Blade blade : mBlades) {
            float xpos = random(-mWidth, mWidth);
            blade.xPos = xpos;
            blade.turbulencex = xpos * 0.006f;
            blade.yPos = mHeight;
        }
    }

    private void createBlade(Blade blade) {
        float size = random(4.0f) + 4.0f;
        float xpos = random(-mWidth, mWidth);
        blade.angle = 0.0f;
        blade.size = (int) (size / TESSELATION);
        blade.xPos = xpos;
        blade.yPos = mHeight;
        blade.offset = random(0.2f) - 0.1f;
        blade.scale = 4.0f / (size / TESSELATION) + (random(0.6f) + 0.2f) * TESSELATION;
        blade.lengthX = (random(4.5f) + 3.0f) * TESSELATION * size;
        blade.lengthY = (random(5.5f) + 2.0f) * TESSELATION * size;
        blade.hardness = (random(1.0f) + 0.2f) * TESSELATION;
        blade.h = random(0.02f) + 0.2f;
        blade.s = random(0.22f) + 0.78f;
        blade.b = random(0.65f) + 0.35f;
        blade.turbulencex = xpos * 0.006f;
    }

    // ---- Dandelion / Firefly methods ----

    private void initDandelions() {
        mDandelions = new Dandelion[mDandelionCount];
        for (int i = 0; i < mDandelionCount; i++) {
            Dandelion d = new Dandelion();
            resetDandelion(d, true);
            mDandelions[i] = d;
        }
    }

    void resetDandelion(Dandelion d, boolean randomX) {
        float size = random(32.0f, 72.0f) * DANDELION_SIZE_SCALE;
        d.size = size;
        d.y = random(0.1f * mHeight, 0.9f * mHeight);
        d.speed = random(20.0f, 60.0f);
        d.swayPhase = random(0.0f, 6.28318f);
        d.swaySpeed = random(0.6f, 1.4f);
        d.rotationDeg = random(-15.0f, 15.0f);
        if (randomX) d.x = random(-mWidth, 0.0f) - size;
        else d.x = -size - random(0.0f, mWidth * 0.2f);
    }

    private void initFireflies() {
        mFireflies = new Firefly[mFireflyCount];
        for (int i = 0; i < mFireflyCount; i++) {
            Firefly f = new Firefly();
            f.x = random(0.0f, mWidth);
            f.y = random(0.0f, mHeight);
            f.vx = random(-10.0f, 10.0f);
            f.vy = random(-8.0f, 8.0f);
            f.size = random(6.0f, 12.0f) * FIREFLY_SIZE_SCALE;
            f.phase = random(0.0f, 6.28318f);
            f.flickerSpeed = random(0.8f, 1.6f);
            mFireflies[i] = f;
        }
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
        LegacyParticle p = new LegacyParticle();
        p.type = type;
        p.startTime = legacyNow + (long) (Math.random() * LEGACY_MAX_DELAY);
        p.silentEndTime = legacyNow
                + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE) * LEGACY_MAX_INTERVAL);
        p.flareEndTime = legacyNow;
        p.stayEndTime = legacyNow;
        p.texture = (type == LEGACY_TYPE_DANDELION) ? 0 : 1;
        p.bladeNum = -1;
        p.sizeNum = -1;
        p.active = true;
        if (type == LEGACY_TYPE_DANDELION) {
            flyLegacyDandelion(p, true);
            p.angle = (float) (Math.random() * 60.0 - 30.0);
        } else {
            flyLegacyFirefly(p, true);
        }
        return p;
    }

    void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
        if (Math.random() > 0.5) {
            p.dx = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
        } else {
            p.dx = (float) ((1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
        }
        p.dy = (float) (-(1.0 - LEGACY_SPEED_VARIANCE + Math.random() * 2 * LEGACY_SPEED_VARIANCE));
        if (isInit) {
            p.originX = (float) (Math.random() * mWidth * 2);
            p.originY = mHeight;
        }
    }

    void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
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
        if (isInit) {
            if (legacyDirection == 0) {
                p.originX = 5.0f;
                p.originY = (float) (Math.random() * mHeight);
            } else if (legacyDirection == 1) {
                p.originX = mWidth * 2.0f;
                p.originY = (float) (Math.random() * mHeight);
            } else {
                p.originX = 0.0f;
                p.originY = (float) (Math.random() * mHeight);
            }
        }
    }

    // ---- Sun time helpers ----

    private float timeFraction() {
        if (!mIsPreview) {
            Calendar now = Calendar.getInstance(mTimeZone);
            return (now.get(Calendar.HOUR_OF_DAY) * 3600.0f
                    + now.get(Calendar.MINUTE) * 60.0f
                    + now.get(Calendar.SECOND)) / SECONDS_IN_DAY;
        }
        float t = (System.currentTimeMillis() % 30000L) / 30000.0f;
        return t - (int) t;
    }

    private void updateSunTimes() {
        float dawn = 0.3f, dusk = 0.75f;
        if (mSunCalculator != null) {
            mTimeZone = TimeZone.getDefault();
            mSunCalculator = new SunCalculator(mLocation, mTimeZone.getID());
            Calendar now = Calendar.getInstance(mTimeZone);
            double sunrise = mSunCalculator.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
            double sunset = mSunCalculator.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
            if (!Double.isNaN(sunrise) && !Double.isNaN(sunset)
                    && sunrise > 0.0 && sunrise < 24.0
                    && sunset > 0.0 && sunset < 24.0
                    && sunrise < sunset) {
                dawn = SunCalculator.timeToDayFraction(sunrise);
                dusk = SunCalculator.timeToDayFraction(sunset);
            }
        }
        mDawn = clamp(dawn, 0.0f, 1.0f);
        mDusk = clamp(dusk, 0.0f, 1.0f);
        mMorning = mDawn + 1.0f / 12.0f;
        mAfternoon = mDusk - 1.0f / 12.0f;
        mLastSunUpdateMs = System.currentTimeMillis();
    }

    // ---- Accurate sun / sky weights ----

    private void updateAccurateWeights() {
        long nowMs = System.currentTimeMillis();
        updateLocationFromSystem(nowMs);
        TimeZone tz = TimeZone.getDefault();
        Calendar now = Calendar.getInstance(tz);

        SunCalculator calc = mSunCalculator;
        if (calc == null || !tz.getID().equals(mTimeZone.getID())) {
            calc = new SunCalculator(mLocation, tz.getID());
        }

        double sunrise = calc.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
        double sunset = calc.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
        double sunriseOfficial = calc.computeSunriseTime(SunCalculator.ZENITH_OFFICIAL, now);
        double sunsetOfficial = calc.computeSunsetTime(SunCalculator.ZENITH_OFFICIAL, now);
        mLastSunriseHour = sunrise;
        mLastSunsetHour = sunset;
        mLastSunriseOfficialHour = sunriseOfficial;
        mLastSunsetOfficialHour = sunsetOfficial;

        Calendar noon = (Calendar) now.clone();
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0);
        noon.set(Calendar.MILLISECOND, 0);
        Calendar midnight = (Calendar) now.clone();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);

        double noonAlt = calc.computeSunAltitude(noon);
        double midnightAlt = calc.computeSunAltitude(midnight);

        if (noonAlt < 0.0 && midnightAlt < 0.0) {
            mAccurateWeights[0] = 1.0f; mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f; mAccurateWeights[3] = 0.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (noonAlt > 0.0 && midnightAlt > 0.0) {
            mAccurateWeights[0] = 0.0f; mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f; mAccurateWeights[3] = 1.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = calc.computeSunAltitude(now);
            return;
        }
        if (sunrise <= 0.0 && sunset <= 0.0) {
            mAccurateWeights[0] = 1.0f; mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f; mAccurateWeights[3] = 0.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = -90.0;
            return;
        }
        if (sunrise >= 24.0 && sunset >= 24.0) {
            mAccurateWeights[0] = 0.0f; mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f; mAccurateWeights[3] = 1.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = 90.0;
            return;
        }

        double altitude = calc.computeSunAltitude(now);
        boolean rising = calc.isSunRising(now);
        mLastSunAltitude = altitude;

        long intervalMs = (altitude >= -6.0 && altitude <= 5.0) ? 30000L : 60000L;
        if (mLastWeightUpdateMs != 0L && (nowMs - mLastWeightUpdateMs) < intervalMs) {
            return;
        }

        float wNight = 0.0f, wSunrise = 0.0f, wSunset = 0.0f, wSky = 0.0f;

        float nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60.0f
                + now.get(Calendar.MINUTE)
                + now.get(Calendar.SECOND) / 60.0f;
        float sunriseMin = (float) (sunrise * 60.0);
        float sunsetMin = (float) (sunset * 60.0);

        if (sunriseMin >= 0.0f && sunriseMin < 1440.0f && sunsetMin > 0.0f && sunsetMin <= 1440.0f
                && sunsetMin > sunriseMin) {
            float dawnStart = sunriseMin;
            float dawnToSunriseEnd = dawnStart + 20.0f;
            float dawnHoldEnd = dawnStart + 40.0f;
            float dawnToDayEnd = dawnStart + 60.0f;
            float duskStart = sunsetMin - 80.0f;
            float duskHoldStart = duskStart + 40.0f;
            float duskToNightStart = duskHoldStart + 20.0f;
            float duskEnd = duskToNightStart + 50.0f;

            if (isBetweenClock(nowMinutes, dawnStart, dawnToSunriseEnd)) {
                float t = clockProgress(nowMinutes, dawnStart, dawnToSunriseEnd);
                wNight = 1.0f - t; wSunrise = t;
            } else if (isBetweenClock(nowMinutes, dawnToSunriseEnd, dawnHoldEnd)) {
                wSunrise = 1.0f;
            } else if (isBetweenClock(nowMinutes, dawnHoldEnd, dawnToDayEnd)) {
                float t = clockProgress(nowMinutes, dawnHoldEnd, dawnToDayEnd);
                wSunrise = 1.0f - t; wSky = t;
            } else if (isBetweenClock(nowMinutes, duskStart, duskHoldStart)) {
                float t = clockProgress(nowMinutes, duskStart, duskHoldStart);
                wSky = 1.0f - t; wSunset = t;
            } else if (isBetweenClock(nowMinutes, duskHoldStart, duskToNightStart)) {
                wSunset = 1.0f;
            } else if (isBetweenClock(nowMinutes, duskToNightStart, duskEnd)) {
                float t = clockProgress(nowMinutes, duskToNightStart, duskEnd);
                wSunset = 1.0f - t; wNight = t;
            } else if (isBetweenClock(nowMinutes, dawnToDayEnd, duskStart)) {
                wSky = 1.0f;
            } else {
                wNight = 1.0f;
            }
        } else {
            if (rising) {
                if (altitude <= -6.0) { wNight = 1.0f; }
                else if (altitude <= 0.0) {
                    wNight = 1.0f - (float) ((altitude + 6.0) / 6.0); wSunrise = 1.0f - wNight;
                } else if (altitude <= 5.0) {
                    wSunrise = (float) ((5.0 - altitude) / 5.0); wSky = 1.0f - wSunrise;
                } else { wSky = 1.0f; }
            } else {
                if (altitude >= 5.0) { wSky = 1.0f; }
                else if (altitude >= 0.0) {
                    wSky = (float) (altitude / 5.0); wSunset = 1.0f - wSky;
                } else if (altitude >= -6.0) {
                    wSunset = (float) ((altitude + 6.0) / 6.0); wNight = 1.0f - wSunset;
                } else { wNight = 1.0f; }
            }
        }
        mAccurateWeights[0] = wNight;
        mAccurateWeights[1] = wSunrise;
        mAccurateWeights[2] = wSunset;
        mAccurateWeights[3] = wSky;
        mLastWeightUpdateMs = nowMs;
        mLastSunAltitude = altitude;
    }

    private boolean isBetweenClock(float now, float start, float end) {
        if (start <= end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private float clockProgress(float now, float start, float end) {
        float duration, elapsed;
        if (start <= end) {
            duration = end - start; elapsed = now - start;
        } else {
            duration = (1440.0f - start) + end;
            elapsed = now >= start ? (now - start) : (1440.0f - start + now);
        }
        if (duration <= 0.0f) return 0.0f;
        return clamp(elapsed / duration, 0.0f, 1.0f);
    }

    private void updateLocationFromSystem(long nowMs) {
        if (!mUseAccurateSun) return;
        if (mLastLocationUpdateMs != 0L && (nowMs - mLastLocationUpdateMs) < 300000L) return;
        Context ctx = GLESWallpaper.getAppContext();
        if (ctx == null) return;
        boolean hasFine = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) return;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location best = null;
        try {
            Location gps = hasFine ? lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location passive = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            best = pickBestLocation(gps, net);
            best = pickBestLocation(best, passive);
        } catch (SecurityException ignored) {}
        if (best != null) {
            mLocation.setLatitude(best.getLatitude());
            mLocation.setLongitude(best.getLongitude());
            mSunCalculator = new SunCalculator(mLocation, mTimeZone.getID());
            mLastLocationUpdateMs = nowMs;
        }
    }

    private Location pickBestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
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
        Calendar now = Calendar.getInstance(mTimeZone);
        SolarEclipse eclipse = computeSolarEclipse(data, now);
        float dayVisibility = clamp(mAccurateWeights[3] + 0.45f * (mAccurateWeights[1] + mAccurateWeights[2]),
                0.0f, 1.0f);
        float eased = smoothstep(0.0f, 1.0f, eclipse.fraction);
        mSolarEclipseWeight = clamp(eased * dayVisibility, 0.0f, 1.0f);
        mLastSolarEclipseUpdateMs = nowMs;
    }

    // ---- Astronomical computation ----

    private SolarEclipse computeSolarEclipse(MoonCalculator.MoonData data, Calendar now) {
        float defaultRatio = LUNAR_MEAN_ANGULAR_RADIUS_DEG / SOLAR_MEAN_ANGULAR_RADIUS_DEG;
        if (data.sunAltitudeDeg <= -0.8 || data.moonAltitudeDeg <= -2.0) {
            return new SolarEclipse(0.0f, 0.0f, defaultRatio);
        }
        float phase = (float) data.phaseAngleUtcDeg;
        float phaseDelta = Math.min(Math.abs(phase), Math.abs(360.0f - phase));
        float nodeLatitude = Math.abs((float) data.moonLatitudeLocalDeg);
        if (phaseDelta > 20.0f || nodeLatitude > 7.0f) {
            return new SolarEclipse(0.0f, 0.0f, defaultRatio);
        }
        float[] angularRadii = computeApparentAngularRadii(now);
        float sunRadiusDeg = angularRadii[0];
        float moonRadiusDeg = angularRadii[1];
        double separationDeg = angularSeparationDeg(
                data.sunAltitudeDeg, data.sunAzimuthDeg,
                data.moonAltitudeDeg, data.moonAzimuthDeg);
        float orbitalSeparationDeg = (float) Math.hypot(phaseDelta, nodeLatitude);
        float separationCandidate = Math.min((float) separationDeg, orbitalSeparationDeg);
        float moonAltRad = (float) Math.toRadians(clamp((float) data.moonAltitudeDeg, 0.0f, 90.0f));
        float parallaxCorrection = 0.65f * (float) Math.cos(moonAltRad);
        float effectiveSeparationDeg = Math.max(0.0f,
                separationCandidate - SOLAR_ECLIPSE_MODEL_TOLERANCE_DEG - 0.35f * parallaxCorrection);
        float overlapObscuration = computeDiscOverlapFraction(sunRadiusDeg, moonRadiusDeg, effectiveSeparationDeg);
        float solarVisible = smoothstep(-0.5f, 5.0f, (float) data.sunAltitudeDeg);
        float lunarVisible = smoothstep(-2.0f, 4.0f, (float) data.moonAltitudeDeg);
        float horizonAttenuation = smoothstep(1.5f, 12.0f,
                (float) Math.min(data.sunAltitudeDeg, data.moonAltitudeDeg));
        float conjunctionAttenuation = 1.0f - smoothstep(8.0f, 22.0f, phaseDelta);
        float nodeAttenuation = 1.0f - smoothstep(3.0f, 9.0f, nodeLatitude);
        float fraction = clamp(
                overlapObscuration * solarVisible * lunarVisible * horizonAttenuation
                        * conjunctionAttenuation * nodeAttenuation,
                0.0f, 1.0f);
        float signedAzDelta = normalizeSignedDegrees((float) (data.moonAzimuthDeg - data.sunAzimuthDeg));
        float phaseProgress = clamp((signedAzDelta + 12.0f) / 24.0f, 0.0f, 1.0f);
        float moonRadiusRatio = moonRadiusDeg / sunRadiusDeg;
        return new SolarEclipse(fraction, phaseProgress, moonRadiusRatio);
    }

    private float[] computeApparentAngularRadii(Calendar now) {
        float d = (float) ((now.getTimeInMillis() / 86400000.0) - 10957.5);
        float sunMeanAnomaly = (float) Math.toRadians(normalizeDegreesFloat(357.5291f + 0.98560028f * d));
        float moonMeanAnomaly = (float) Math.toRadians(normalizeDegreesFloat(134.9634f + 13.064993f * d));
        float sunRadiusDeg = SOLAR_MEAN_ANGULAR_RADIUS_DEG * (1.0f + 0.0167f * (float) Math.cos(sunMeanAnomaly));
        float moonRadiusDeg = LUNAR_MEAN_ANGULAR_RADIUS_DEG * (1.0f + 0.0549f * (float) Math.cos(moonMeanAnomaly));
        sunRadiusDeg = clamp(sunRadiusDeg, 0.258f, 0.275f);
        moonRadiusDeg = clamp(moonRadiusDeg, 0.245f, 0.285f);
        return new float[]{sunRadiusDeg, moonRadiusDeg};
    }

    private double angularSeparationDeg(double alt1Deg, double az1Deg, double alt2Deg, double az2Deg) {
        double alt1 = Math.toRadians(alt1Deg);
        double alt2 = Math.toRadians(alt2Deg);
        double deltaAz = Math.toRadians(az1Deg - az2Deg);
        double cosD = Math.sin(alt1) * Math.sin(alt2)
                + Math.cos(alt1) * Math.cos(alt2) * Math.cos(deltaAz);
        cosD = Math.max(-1.0, Math.min(1.0, cosD));
        return Math.toDegrees(Math.acos(cosD));
    }

    private float computeDiscOverlapFraction(float sunRadiusDeg, float moonRadiusDeg, float centerDistanceDeg) {
        if (sunRadiusDeg <= 0.0f || moonRadiusDeg <= 0.0f) return 0.0f;
        double r1 = sunRadiusDeg, r2 = moonRadiusDeg, d = centerDistanceDeg;
        if (d >= r1 + r2) return 0.0f;
        double overlapArea;
        if (d <= Math.abs(r1 - r2)) {
            double minR = Math.min(r1, r2);
            overlapArea = Math.PI * minR * minR;
        } else {
            overlapArea = circleIntersectionArea(r1, r2, d);
        }
        double sunArea = Math.PI * r1 * r1;
        if (sunArea <= 0.0) return 0.0f;
        return clamp((float) (overlapArea / sunArea), 0.0f, 1.0f);
    }

    private double circleIntersectionArea(double r1, double r2, double d) {
        double r1Sq = r1 * r1, r2Sq = r2 * r2;
        double alpha = Math.acos(clampDouble((d * d + r1Sq - r2Sq) / (2.0 * d * r1), -1.0, 1.0));
        double beta = Math.acos(clampDouble((d * d + r2Sq - r1Sq) / (2.0 * d * r2), -1.0, 1.0));
        double part1 = r1Sq * alpha;
        double part2 = r2Sq * beta;
        double part3 = 0.5 * Math.sqrt(clampDouble(
                (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2), 0.0, Double.MAX_VALUE));
        return part1 + part2 - part3;
    }

    private MoonEclipse computeMoonEclipse(MoonCalculator.MoonData data) {
        double delta = Math.abs(data.phaseAngleUtcDeg - 180.0);
        if (delta > 10.0) return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        double beta = Math.abs(data.moonLatitudeUtcDeg);
        if (beta > 10.2) return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        double umbra = (1.0 - beta) / 0.27;
        double penumbra = (1.5 - beta) / 0.27;
        float pen = clamp((float) penumbra, 0.0f, 1.0f);
        float umb = clamp((float) umbra, 0.0f, 1.0f);
        float total = clamp((float) (umbra - 1.0), 0.0f, 1.0f);
        double phaseRad = Math.toRadians(data.phaseAngleUtcDeg);
        float basePhase;
        if (umbra <= 0.0) { basePhase = pen * 0.25f; }
        else if (umbra < 1.0) { basePhase = 0.25f + umb * 0.35f; }
        else { basePhase = 0.60f + total * 0.20f; }
        float phase = basePhase;
        float easedPhase = smoothstep(0.0f, 1.0f, phase);
        float offsetMag = mix(1.6f, -1.6f, easedPhase);
        float dirX = Math.sin(phaseRad) >= 0.0 ? 1.0f : -1.0f;
        float offX = dirX * offsetMag;
        if (umbra > 1.0) return new MoonEclipse(3, (float) umbra, phase, offX, 0.0f);
        if (umbra > 0.0) return new MoonEclipse(2, (float) umbra, phase, offX, 0.0f);
        if (penumbra > 0.0) return new MoonEclipse(1, clamp((float) penumbra, 0.0f, 1.0f), phase, offX, 0.0f);
        return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private float normalizeSignedDegrees(float deg) {
        float n = deg % 360.0f;
        if (n > 180.0f) n -= 360.0f;
        else if (n < -180.0f) n += 360.0f;
        return n;
    }

    private float normalizeDegreesFloat(float deg) {
        float n = deg % 360.0f;
        if (n < 0.0f) n += 360.0f;
        return n;
    }

    private double clampDouble(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    // ---- Noise ----

    private void initNoise() {
        for (int i = 0; i < B; i++) {
            p[i] = i;
            g2[i][0] = (float) (mRandom.nextFloat() * 2 - 1);
            g2[i][1] = (float) (mRandom.nextFloat() * 2 - 1);
            float len = (float) Math.sqrt(g2[i][0] * g2[i][0] + g2[i][1] * g2[i][1]);
            g2[i][0] /= len;
            g2[i][1] /= len;
        }
        for (int i = B - 1; i >= 0; i--) {
            int j = mRandom.nextInt(B);
            int temp = p[i]; p[i] = p[j]; p[j] = temp;
        }
        for (int i = 0; i < B + 2; i++) {
            p[B + i] = p[i];
            g2[B + i][0] = g2[i][0];
            g2[B + i][1] = g2[i][1];
        }
    }

    private float turbulencef2(float x, float y, float octaves) {
        float t = 0.0f;
        for (float f = 1.0f; f <= octaves; f *= 2) {
            t += Math.abs(noisef2(f * x, f * y)) / f;
        }
        return t;
    }

    private float noisef2(float x, float y) {
        float t = x + N;
        int bx0 = ((int) t) & BM, bx1 = (bx0 + 1) & BM;
        float rx0 = t - (int) t, rx1 = rx0 - 1.0f;
        t = y + N;
        int by0 = ((int) t) & BM, by1 = (by0 + 1) & BM;
        float ry0 = t - (int) t, ry1 = ry0 - 1.0f;
        int i = p[bx0], j = p[bx1];
        int b00 = p[i + by0], b10 = p[j + by0], b01 = p[i + by1], b11 = p[j + by1];
        float sx = noiseSCurve(rx0), sy = noiseSCurve(ry0);
        float u = rx0 * g2[b00][0] + ry0 * g2[b00][1];
        float v = rx1 * g2[b10][0] + ry0 * g2[b10][1];
        float a = mix(u, v, sx);
        u = rx0 * g2[b01][0] + ry1 * g2[b01][1];
        v = rx1 * g2[b11][0] + ry1 * g2[b11][1];
        float b = mix(u, v, sx);
        return 1.5f * mix(a, b, sy);
    }

    private float noiseSCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
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
