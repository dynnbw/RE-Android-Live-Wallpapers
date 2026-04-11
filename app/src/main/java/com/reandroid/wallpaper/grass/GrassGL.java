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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.GLESWallpaper;
import com.reandroid.wallpaper.gles.RawResourceLoader;
import com.reandroid.wallpaper.settings.WallpaperSettings;
import com.reandroid.wallpaper.weather.WeatherCondition;
import com.reandroid.wallpaper.weather.WeatherManager;
import com.reandroid.wallpaper.weather.WeatherState;

import java.util.Calendar;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Grass 壁纸渲染层（OpenGL ES 2.0），所有状态委托 GrassScene 管理。
 */
public class GrassGL extends GLESScene {

    private static final String TAG = "GrassGL";

    // ---- Scene (non-GL logic) ----
    private final GrassScene mScene;

    // ---- GL state ----
    private boolean mGLInitialized = false;

    // Shader programs
    private int mBackgroundProgram;
    private int mSkyProgram;
    private int mGrassProgram;
    private int mMoonProgram;

    // Background / sprite program handles
    private int mBgPositionHandle;
    private int mBgTexHandle;
    private int mBgMatrixHandle;
    private int mBgAlphaHandle;
    private int mBgSamplerHandle;

    // Sky program handles
    private int mSkyPositionHandle;
    private int mSkyTexHandle;
    private int mSkyMatrixHandle;
    private int mSkySamplerNightHandle;
    private int mSkySamplerSunriseHandle;
    private int mSkySamplerSunsetHandle;
    private int mSkySamplerSkyHandle;
    private int mSkySamplerSolarEclipseHandle;
    private int mSkyWeightNightHandle;
    private int mSkyWeightSunriseHandle;
    private int mSkyWeightSunsetHandle;
    private int mSkyWeightSkyHandle;
    private int mSkyWeightSolarEclipseHandle;
    private int mSkyNightInvertHandle;

    // Grass program handles
    private int mGrassPositionHandle;
    private int mGrassColorHandle;
    private int mGrassTexHandle;
    private int mGrassMatrixHandle;
    private int mGrassSamplerHandle;

    // Moon program handles
    private int mMoonPositionHandle;
    private int mMoonTexHandle;
    private int mMoonMatrixHandle;
    private int mMoonSamplerBaseHandle;
    private int mMoonSamplerMaskHandle;
    private int mMoonPhaseHandle;
    private int mMoonBrightnessHandle;
    private int mMoonAlphaHandle;
    private int mMoonIsDaytimeHandle;
    private int mMoonContrastHandle;
    private int mMoonSaturationHandle;
    private int mMoonBlueTintHandle;
    private int mMoonEclipseTypeHandle;
    private int mMoonEclipseFractionHandle;
    private int mMoonEclipsePhaseHandle;
    private int mMoonShadowOffsetHandle;
    private int mMoonShadowColorHandle;
    private int mMoonPenumbraColorHandle;
    private int mMoonSolarOcclusionHandle;

    // Textures
    private int mTexNight;
    private int mTexSunrise;
    private int mTexSunset;
    private int mTexSky;
    private int mTexSolarEclipse;
    private int mTexSun;
    private int mTexAA;
    private int mTexDandelion;
    private int mTexFirefly;
    private int mTexFirefly1;
    private int mTexFirefly2;
    private int mTexWeatherRain1;
    private int mTexWeatherRain2;
    private int mTexWeatherRain3;
    private int mTexWeatherSnow1;
    private int mTexWeatherSnow2;
    private int mTexWeatherSnow3;
    private int mTexWeatherSnow4;
    private int mTexWeatherFog1;
    private int mTexWeatherFog2;
    private int mTexWeatherCloud1;
    private int mTexWeatherCloud2;
    private int mTexWeatherCloud3;
    private int mTexWeatherCloud4;
    private int mTexWeatherLightning1;
    private int mTexWeatherLightning2;
    private int mTexWeatherLightning3;
    private int mTexWeatherFlash;
    private int mTexWeatherTone;
    private int mTexMoonBase;
    private int mTexMoonMask;

    // NIO buffers
    private FloatBuffer mGrassVertexBuffer;
    private ShortBuffer mGrassIndexBuffer;
    private FloatBuffer mSpriteBuffer;
    private FloatBuffer mBgQuadBuffer;
    private FloatBuffer mSkyQuadBuffer;
    private FloatBuffer mMoonBuffer;
    private final float[] mQuadVerts = new float[16];
    private boolean mSkyQuadDirty = true;

    // Performance and diagnostics
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled = false;
    private long mLastPerfSyncMs = 0L;
    private long mDiagFrameCount = 0L;
    private long mDiagAccumulatedMs = 0L;
    private long mDiagMaxMs = 0L;
    private int mCurrentProgram = -1;
    private int mBlendSrc = -1;
    private int mBlendDst = -1;

    // Weather integration
    private WeatherManager mWeatherManager;
    private SharedPreferences mPrefs;
    private volatile WeatherState mPendingWeatherState;
    private volatile boolean mClearWeatherStatePending;
    private boolean mWeatherEnabled = true;
    private boolean mWeatherRunning;

    // Weather rendering - display density and original mdpi texture dimensions
    private float mDensity = 1.0f;
    // cloud_01(256x180), cloud_02(256x163), cloud_05→cloud3(256x198), cloudy→cloud4(276x170)
    private static final float[] CLOUD_MDPI_W = {256f, 256f, 256f, 276f};
    private static final float[] CLOUD_MDPI_H = {180f, 163f, 198f, 170f};
    // fog_01=haze(280x95), fog_02=fog(150x86) aspect ratio H/W at mdpi
    private static final float FOG1_H_OVER_W = 95f / 280f;
    private static final float FOG2_H_OVER_W = 86f / 150f;
    // rain_01(2x42), rain_02(2x30), rain_03(2x49) mdpi dimensions
    private static final float[] RAIN_MDPI_W = {2f, 2f, 2f};
    private static final float[] RAIN_MDPI_H = {42f, 30f, 49f};

    // Original thunder behavior state (bwlw/Thunder.java style)
    private long mThunderNextStartMs = 0L;
    private long mThunderActiveStartMs = 0L;
    private int mThunderTextureIndex = 0;
    private boolean mThunderLTR = true;
    private float mThunderFlashAlpha = 0.0f;

    // ---- Constructor ----

    public GrassGL(int width, int height) {
        super(width, height);
        mScene = new GrassScene(width, height);
    }

    // ---- GLESScene lifecycle ----

    @Override
    protected void onCreate() {
        mScene.init(isPreview());
        Context appContext = GLESWallpaper.getAppContext();
        if (appContext != null && mWeatherManager == null) {
            mPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext);
            mWeatherManager = new WeatherManager(appContext, this::onWeatherUpdated);
        }
        if (mResources != null) {
            initGL();
        }
    }

    @Override
    public void start() {
        mWeatherEnabled = WallpaperSettings.isGrassWeatherEnabled(true);
        if (!isPreview() && mWeatherManager != null && mWeatherEnabled) {
            mWeatherManager.start();
            mWeatherRunning = true;
            return;
        }
        mWeatherRunning = false;
        mClearWeatherStatePending = true;

        if (isPreview() && mWeatherEnabled) {
            boolean isNight = computePreviewIsNight();
            mPendingWeatherState = new WeatherState(WeatherCondition.D1_CLEAR, isNight,
                    0.0f, 0.0f, 0L, 0L, 0L);
        }
    }

    @Override
    public void stop() {
        if (mWeatherManager != null) {
            mWeatherManager.stop();
        }
        mWeatherRunning = false;
    }

    @Override
    public void release() {
        int[] tex = new int[]{
                mTexNight, mTexSunrise, mTexSunset, mTexSky,
                mTexSolarEclipse, mTexSun, mTexAA, mTexDandelion, mTexFirefly,
            mTexFirefly1, mTexFirefly2,
            mTexWeatherRain1, mTexWeatherRain2, mTexWeatherRain3,
            mTexWeatherSnow1, mTexWeatherSnow2, mTexWeatherSnow3, mTexWeatherSnow4,
            mTexWeatherFog1, mTexWeatherFog2,
            mTexWeatherCloud1, mTexWeatherCloud2, mTexWeatherCloud3, mTexWeatherCloud4,
            mTexWeatherLightning1, mTexWeatherLightning2, mTexWeatherLightning3,
            mTexWeatherFlash,
            mTexWeatherTone,
            mTexMoonBase, mTexMoonMask
        };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexNight = 0; mTexSunrise = 0; mTexSunset = 0; mTexSky = 0;
        mTexSolarEclipse = 0; mTexSun = 0; mTexAA = 0;
        mTexDandelion = 0; mTexFirefly = 0; mTexFirefly1 = 0; mTexFirefly2 = 0;
        mTexWeatherRain1 = 0; mTexWeatherRain2 = 0; mTexWeatherRain3 = 0;
        mTexWeatherSnow1 = 0; mTexWeatherSnow2 = 0; mTexWeatherSnow3 = 0; mTexWeatherSnow4 = 0;
        mTexWeatherFog1 = 0; mTexWeatherFog2 = 0;
        mTexWeatherCloud1 = 0; mTexWeatherCloud2 = 0; mTexWeatherCloud3 = 0; mTexWeatherCloud4 = 0;
        mTexWeatherLightning1 = 0; mTexWeatherLightning2 = 0; mTexWeatherLightning3 = 0;
        mTexWeatherFlash = 0;
        mTexWeatherTone = 0;
        mTexMoonBase = 0; mTexMoonMask = 0;

        if (mBackgroundProgram != 0) { GLES20.glDeleteProgram(mBackgroundProgram); mBackgroundProgram = 0; }
        if (mSkyProgram != 0) { GLES20.glDeleteProgram(mSkyProgram); mSkyProgram = 0; }
        if (mGrassProgram != 0) { GLES20.glDeleteProgram(mGrassProgram); mGrassProgram = 0; }
        if (mMoonProgram != 0) { GLES20.glDeleteProgram(mMoonProgram); mMoonProgram = 0; }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        mSkyQuadDirty = true;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    // ---- Main draw loop ----

    @Override
    public void drawFrame(long timeMs) {
        long frameStart = SystemClock.uptimeMillis();
        syncPerfSettingsIfNeeded(frameStart);

        if (!mScene.isInitialized()) return;
        if (!mGLInitialized) {
            if (mResources == null) return;
            initGL();
        }

        WeatherState weatherState = mPendingWeatherState;
        boolean weatherEnabled = WallpaperSettings.isGrassWeatherEnabled(true);
        if (weatherEnabled != mWeatherEnabled) {
            mWeatherEnabled = weatherEnabled;
            if (mWeatherManager != null && !isPreview()) {
                if (mWeatherEnabled && !mWeatherRunning) {
                    mWeatherManager.start();
                    mWeatherRunning = true;
                } else if (!mWeatherEnabled && mWeatherRunning) {
                    mWeatherManager.stop();
                    mWeatherRunning = false;
                }
            }
            if (!mWeatherEnabled) {
                mClearWeatherStatePending = true;
                mPendingWeatherState = null;
                weatherState = null;
            } else if (isPreview()) {
                boolean isNight = computePreviewIsNight();
                weatherState = new WeatherState(WeatherCondition.D1_CLEAR, isNight,
                        0.0f, 0.0f, 0L, 0L, 0L);
                mPendingWeatherState = weatherState;
            }
        }

        if (mClearWeatherStatePending) {
            mScene.setWeatherState(null);
            mClearWeatherStatePending = false;
        }
        if (weatherState != null) {
            mScene.setWeatherState(weatherState);
            mPendingWeatherState = null;
        }

        long animNow = SystemClock.uptimeMillis();
        mScene.update(animNow);
        GrassScene.SceneData sd = mScene.getSceneData();

        // Rebuild blade index/vertex buffers when blade count changes
        if (sd.bladeIndexRebuildNeeded || mGrassIndexBuffer == null) {
            buildBladeBuffers();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        float eclipseImpact, grassBrightness, nightDesat;

        if (sd.useAccurateSun) {
            useProgram(mSkyProgram);
            setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniformMatrix4fv(mSkyMatrixHandle, 1, false, sd.projectionMatrix, 0);
            drawAccurateBackground(sd);
            if (sd.sunEnabled && sd.hasSunData) drawSun(sd);
            eclipseImpact = clamp(sd.solarEclipseWeight, 0.0f, 1.0f);
            grassBrightness = sd.newB;
            nightDesat = 0.0f;
            if (sd.nightDesaturateGrass) {
                grassBrightness = mix(1.0f, 0.72f, eclipseImpact);
                float baseNightDesat = sd.accurateWeights[0];
                nightDesat = clamp(baseNightDesat + eclipseImpact * 0.85f, 0.0f, 1.0f);
            } else {
                grassBrightness *= mix(1.0f, 0.62f, eclipseImpact);
            }
        } else {
            useProgram(mBackgroundProgram);
            setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
            drawBackground(sd);
            eclipseImpact = 0.0f;
            grassBrightness = sd.newB;
            nightDesat = 0.0f;
            if (sd.nightDesaturateGrass) {
                grassBrightness = 1.0f;
                nightDesat = clamp(1.0f - sd.newB, 0.0f, 1.0f);
            }
        }

        drawMoon(sd);
        drawWeatherOverlays(sd, false);

        useProgram(mGrassProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mGrassMatrixHandle, 1, false, sd.projectionMatrix, 0);

        drawBlades(sd, grassBrightness, sd.xDraw, nightDesat);
        drawSprites(sd);
        drawWeatherOverlays(sd, true);

        long frameCost = SystemClock.uptimeMillis() - frameStart;
        recordFrameCost(frameCost);
    }

    // ---- GL initialisation ----

    private void initGL() {
        if (mGLInitialized) return;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        mGLInitialized = true;
        mCurrentProgram = -1;
        mBlendSrc = -1;
        mBlendDst = -1;

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);

        createBackgroundProgram();
        createSkyProgram();
        createGrassProgram();
        createMoonProgram();
        loadTextures();
        loadMoonTextures();

        GLES20.glViewport(0, 0, mWidth, mHeight);
        }

        private void createBackgroundProgram() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.grass_bg_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.grass_bg_fs);
        mBackgroundProgram = createProgram(vs, fs);
        mBgPositionHandle = GLES20.glGetAttribLocation(mBackgroundProgram, "aPosition");
        mBgTexHandle = GLES20.glGetAttribLocation(mBackgroundProgram, "aTexCoord");
        mBgMatrixHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uMVPMatrix");
        mBgAlphaHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uAlpha");
        mBgSamplerHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uSampler");
    }

    private void createSkyProgram() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.grass_sky_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.grass_sky_fs);
        mSkyProgram = createProgram(vs, fs);
        mSkyPositionHandle = GLES20.glGetAttribLocation(mSkyProgram, "aPosition");
        mSkyTexHandle = GLES20.glGetAttribLocation(mSkyProgram, "aTexCoord");
        mSkyMatrixHandle = GLES20.glGetUniformLocation(mSkyProgram, "uMVPMatrix");
        mSkySamplerNightHandle = GLES20.glGetUniformLocation(mSkyProgram, "uTexNight");
        mSkySamplerSunriseHandle = GLES20.glGetUniformLocation(mSkyProgram, "uTexSunrise");
        mSkySamplerSunsetHandle = GLES20.glGetUniformLocation(mSkyProgram, "uTexSunset");
        mSkySamplerSkyHandle = GLES20.glGetUniformLocation(mSkyProgram, "uTexSky");
        mSkySamplerSolarEclipseHandle = GLES20.glGetUniformLocation(mSkyProgram, "uTexSolarEclipse");
        mSkyWeightNightHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightNight");
        mSkyWeightSunriseHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSunrise");
        mSkyWeightSunsetHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSunset");
        mSkyWeightSkyHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSky");
        mSkyWeightSolarEclipseHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSolarEclipse");
        mSkyNightInvertHandle = GLES20.glGetUniformLocation(mSkyProgram, "uNightInvert");
    }

    private void createGrassProgram() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.grass_grass_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.grass_grass_fs);
        mGrassProgram = createProgram(vs, fs);
        mGrassPositionHandle = GLES20.glGetAttribLocation(mGrassProgram, "aPosition");
        mGrassColorHandle = GLES20.glGetAttribLocation(mGrassProgram, "aColor");
        mGrassTexHandle = GLES20.glGetAttribLocation(mGrassProgram, "aTexCoord");
        mGrassMatrixHandle = GLES20.glGetUniformLocation(mGrassProgram, "uMVPMatrix");
        mGrassSamplerHandle = GLES20.glGetUniformLocation(mGrassProgram, "uSampler");
    }

    private void createMoonProgram() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.grass_moon_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.grass_moon_fs);
        mMoonProgram = createProgram(vs, fs);
        mMoonPositionHandle = GLES20.glGetAttribLocation(mMoonProgram, "aPosition");
        mMoonTexHandle = GLES20.glGetAttribLocation(mMoonProgram, "aTexCoord");
        mMoonMatrixHandle = GLES20.glGetUniformLocation(mMoonProgram, "uMVPMatrix");
        mMoonSamplerBaseHandle = GLES20.glGetUniformLocation(mMoonProgram, "uMoonBase");
        mMoonSamplerMaskHandle = GLES20.glGetUniformLocation(mMoonProgram, "uMoonMask");
        mMoonPhaseHandle = GLES20.glGetUniformLocation(mMoonProgram, "uPhaseAngle");
        mMoonBrightnessHandle = GLES20.glGetUniformLocation(mMoonProgram, "uBrightness");
        mMoonAlphaHandle = GLES20.glGetUniformLocation(mMoonProgram, "uMoonAlpha");
        mMoonIsDaytimeHandle = GLES20.glGetUniformLocation(mMoonProgram, "uIsDaytime");
        mMoonContrastHandle = GLES20.glGetUniformLocation(mMoonProgram, "uContrast");
        mMoonSaturationHandle = GLES20.glGetUniformLocation(mMoonProgram, "uSaturation");
        mMoonBlueTintHandle = GLES20.glGetUniformLocation(mMoonProgram, "uBlueTint");
        mMoonEclipseTypeHandle = GLES20.glGetUniformLocation(mMoonProgram, "uEclipseType");
        mMoonEclipseFractionHandle = GLES20.glGetUniformLocation(mMoonProgram, "uEclipseFraction");
        mMoonEclipsePhaseHandle = GLES20.glGetUniformLocation(mMoonProgram, "uEclipsePhase");
        mMoonShadowOffsetHandle = GLES20.glGetUniformLocation(mMoonProgram, "uShadowOffset");
        mMoonShadowColorHandle = GLES20.glGetUniformLocation(mMoonProgram, "uShadowColor");
        mMoonPenumbraColorHandle = GLES20.glGetUniformLocation(mMoonProgram, "uPenumbraColor");
        mMoonSolarOcclusionHandle = GLES20.glGetUniformLocation(mMoonProgram, "uSolarOcclusion");
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] link = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    // ---- Texture loading ----

    private void loadTextures() {
        mDensity = mResources.getDisplayMetrics().density;
        mTexNight = loadTexture(R.drawable.night, true, false);
        mTexSunrise = loadTexture(R.drawable.sunrise, true, false);
        mTexSunset = loadTexture(R.drawable.sunset, true, false);
        mTexSky = loadTexture(R.drawable.sky, true, false);
        mTexSolarEclipse = loadTexture(R.drawable.solar_eclipse, false, false);
        mTexSun = loadTexture(R.drawable.sun, false, false);
        mTexAA = createAlphaTexture();
        mTexDandelion = loadTexture(R.drawable.dandelion, false, false);
        mTexFirefly = loadTexture(R.drawable.firefly, false, false);
        mTexFirefly1 = loadTexture(R.drawable.firefly1, false, false);
        mTexFirefly2 = loadTexture(R.drawable.firefly2, false, false);
        mTexWeatherRain1 = loadTexture(R.drawable.grass_weather_rain_01, false, false);
        mTexWeatherRain2 = loadTexture(R.drawable.grass_weather_rain_02, false, false);
        mTexWeatherRain3 = loadTexture(R.drawable.grass_weather_rain_03, false, false);
        mTexWeatherSnow1 = loadTexture(R.drawable.grass_weather_snow_01, false, false);
        mTexWeatherSnow2 = loadTexture(R.drawable.grass_weather_snow_02, false, false);
        mTexWeatherSnow3 = loadTexture(R.drawable.grass_weather_snow_03, false, false);
        mTexWeatherSnow4 = loadTexture(R.drawable.grass_weather_snow_04, false, false);
        mTexWeatherFog1 = loadTexture(R.drawable.grass_weather_fog_01, false, false);
        mTexWeatherFog2 = loadTexture(R.drawable.grass_weather_fog_02, false, false);
        mTexWeatherCloud1 = loadTexture(R.drawable.grass_weather_cloud_01, false, false);
        mTexWeatherCloud2 = loadTexture(R.drawable.grass_weather_cloud_02, false, false);
        mTexWeatherCloud3 = loadTexture(R.drawable.grass_weather_cloud_03, false, false);
        mTexWeatherCloud4 = loadTexture(R.drawable.grass_weather_cloud_04, false, false);
        mTexWeatherLightning1 = loadTexture(R.drawable.grass_weather_lightning_01, false, false);
        mTexWeatherLightning2 = loadTexture(R.drawable.grass_weather_lightning_02, false, false);
        mTexWeatherLightning3 = loadTexture(R.drawable.grass_weather_lightning_03, false, false);
        mTexWeatherFlash = createSolidWhiteTexture();
        mTexWeatherTone = createWeatherToneTexture();
    }

    private int createWeatherToneTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Approximate original rainy sky gradient used in Canvas version.
        byte[] rgba = new byte[]{
            (byte) 0x51, (byte) 0x7a, (byte) 0x98, (byte) 0xff,
            (byte) 0x6b, (byte) 0xaf, (byte) 0x9f, (byte) 0xff
        };
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private void loadMoonTextures() {
        mTexMoonBase = loadTexture(R.drawable.grass_moon, false, false);
        mTexMoonMask = createMoonMaskTexture(512);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexMoonBase);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexMoonMask);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }

    private int createMoonMaskTexture(int size) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        byte[] alpha = new byte[size * size];
        float cx = (size - 1) * 0.5f, cy = (size - 1) * 0.5f;
        float radius = size * 0.5f - 1.0f;
        float edge = radius * 0.08f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - cx, dy = y - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float a = 1.0f - clamp((dist - radius + edge) / edge, 0.0f, 1.0f);
                alpha[y * size + x] = (byte) Math.round(a * 255.0f);
            }
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(alpha.length).order(ByteOrder.nativeOrder());
        buf.put(alpha).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, size, size,
                0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private int loadTexture(int resId, boolean repeat, boolean mipmap) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resId, options);
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        bitmap.recycle();
        return tex[0];
    }

    private int createAlphaTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST_MIPMAP_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        byte[] mip0 = new byte[]{0, (byte) 255, (byte) 255, 0};
        byte[] mip1 = new byte[]{64, 64};
        byte[] mip2 = new byte[]{0};
        ByteBuffer b0 = ByteBuffer.allocateDirect(mip0.length).order(ByteOrder.nativeOrder());
        b0.put(mip0).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 4, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b0);
        ByteBuffer b1 = ByteBuffer.allocateDirect(mip1.length).order(ByteOrder.nativeOrder());
        b1.put(mip1).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 1, GLES20.GL_ALPHA, 2, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b1);
        ByteBuffer b2 = ByteBuffer.allocateDirect(mip2.length).order(ByteOrder.nativeOrder());
        b2.put(mip2).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 2, GLES20.GL_ALPHA, 1, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b2);
        return tex[0];
    }

    private int createSolidWhiteTexture() {
        return createSolidColorTexture((byte) 255, (byte) 255, (byte) 255, (byte) 255);
    }

    private int createSolidColorTexture(byte r, byte g, byte b, byte a) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        byte[] rgba = new byte[]{r, g, b, a};
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    // ---- Blade index/vertex buffer build (triggered by GrassScene signal) ----

    private void buildBladeBuffers() {
        int vertexTotal = mScene.mVertexCount * 2; // 2 vertices per segment
        int stride = 8; // x,y + r,g,b,a + s,t

        mGrassVertexBuffer = ByteBuffer.allocateDirect(vertexTotal * stride * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        int indexCount = mScene.mIndexCount;
        short[] idx = new short[indexCount];
        int idxIdx = 0, vtxIdx = 0;
        for (int size : mScene.mBladeSizes) {
            for (int ct = 0; ct < size; ct++) {
                idx[idxIdx + 0] = (short) (vtxIdx + 0);
                idx[idxIdx + 1] = (short) (vtxIdx + 1);
                idx[idxIdx + 2] = (short) (vtxIdx + 2);
                idx[idxIdx + 3] = (short) (vtxIdx + 1);
                idx[idxIdx + 4] = (short) (vtxIdx + 3);
                idx[idxIdx + 5] = (short) (vtxIdx + 2);
                idxIdx += 6;
                vtxIdx += 2;
            }
            vtxIdx += 2;
        }
        mGrassIndexBuffer = ByteBuffer.allocateDirect(idx.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        mGrassIndexBuffer.put(idx).position(0);
    }

    // ---- Draw methods ----

    private void drawBackground(GrassScene.SceneData sd) {
        float now = sd.timeFraction;
        float dawn = sd.dawn, morning = sd.morning, afternoon = sd.afternoon, dusk = sd.dusk;

        if (now >= 0.0f && now < dawn) {
            setAlpha(1.0f);
            drawNight(sd.nightInvert);
        } else if (now >= dawn && now <= morning) {
            float half = dawn + (morning - dawn) * 0.5f;
            if (now <= half) {
                setAlpha(1.0f);
                drawNight(sd.nightInvert);
                setAlpha(normf(dawn, half, now));
                drawSunrise();
            } else {
                setAlpha(1.0f);
                drawSunrise();
                setAlpha(normf(half, morning, now));
                drawNoon();
            }
        } else if (now > morning && now < afternoon) {
            setAlpha(1.0f);
            drawNoon();
        } else if (now >= afternoon && now <= dusk) {
            float half = afternoon + (dusk - afternoon) * 0.5f;
            if (now <= half) {
                setAlpha(1.0f);
                drawNoon();
                setAlpha(normf(afternoon, half, now));
                drawSunset();
            } else {
                setAlpha(1.0f);
                drawSunset();
                setAlpha(normf(half, dusk, now));
                drawNight(sd.nightInvert);
            }
        } else if (now > dusk) {
            setAlpha(1.0f);
            drawNight(sd.nightInvert);
        }
    }

    private void setAlpha(float a) {
        GLES20.glUniform1f(mBgAlphaHandle, a);
    }

    private void useProgram(int program) {
        if (mCurrentProgram != program) {
            GLES20.glUseProgram(program);
            mCurrentProgram = program;
        }
    }

    private void setBlendFunc(int src, int dst) {
        if (mBlendSrc != src || mBlendDst != dst) {
            GLES20.glBlendFunc(src, dst);
            mBlendSrc = src;
            mBlendDst = dst;
        }
    }

    private void drawNight(boolean nightInvert) {
        if (nightInvert) {
            drawBackgroundQuad(mTexNight, 0.0f, -32.0f, 0.0f, 1.0f,
                    0.0f, mHeight, 0.0f, 0.0f,
                    mWidth, mHeight, 2.0f, 0.0f,
                    mWidth, -32.0f, 2.0f, 1.0f);
        } else {
            drawBackgroundQuad(mTexNight, 0.0f, -32.0f, 0.0f, 0.0f,
                    0.0f, mHeight, 0.0f, 1.0f,
                    mWidth, mHeight, 2.0f, 1.0f,
                    mWidth, -32.0f, 2.0f, 0.0f);
        }
    }

    private void drawSunrise() { drawRect(mTexSunrise); }
    private void drawNoon()    { drawRect(mTexSky); }
    private void drawSunset()  { drawRect(mTexSunset); }

    private void drawRect(int texture) {
        drawBackgroundQuad(texture, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, mHeight, 0.0f, 1.0f,
                mWidth, mHeight, 1.0f, 1.0f,
                mWidth, 0.0f, 1.0f, 0.0f);
    }

    private void drawBackgroundQuad(int texture,
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3) {
        if (mBgQuadBuffer == null) {
            mBgQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        mQuadVerts[0] = x0;  mQuadVerts[1] = y0;  mQuadVerts[2] = u0;  mQuadVerts[3] = v0;
        mQuadVerts[4] = x1;  mQuadVerts[5] = y1;  mQuadVerts[6] = u1;  mQuadVerts[7] = v1;
        mQuadVerts[8] = x2;  mQuadVerts[9] = y2;  mQuadVerts[10] = u2; mQuadVerts[11] = v2;
        mQuadVerts[12] = x3; mQuadVerts[13] = y3; mQuadVerts[14] = u3; mQuadVerts[15] = v3;
        mBgQuadBuffer.clear();
        mBgQuadBuffer.put(mQuadVerts).position(0);
        GLES20.glEnableVertexAttribArray(mBgPositionHandle);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mBgQuadBuffer);
        mBgQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mBgTexHandle);
        GLES20.glVertexAttribPointer(mBgTexHandle, 2, GLES20.GL_FLOAT, false, 16, mBgQuadBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mBgSamplerHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgTexHandle);
    }

    private void drawAccurateBackground(GrassScene.SceneData sd) {
        if (mSkyQuadBuffer == null) {
            mSkyQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            mSkyQuadDirty = true;
        }
        if (mSkyQuadDirty) {
            mSkyQuadBuffer.clear();
            mSkyQuadBuffer.put(0.0f).put(0.0f).put(0.0f).put(0.0f);
            mSkyQuadBuffer.put(0.0f).put(mHeight).put(0.0f).put(1.0f);
            mSkyQuadBuffer.put(mWidth).put(mHeight).put(1.0f).put(1.0f);
            mSkyQuadBuffer.put(mWidth).put(0.0f).put(1.0f).put(0.0f);
            mSkyQuadBuffer.position(0);
            mSkyQuadDirty = false;
        }
        mSkyQuadBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mSkyPositionHandle);
        GLES20.glVertexAttribPointer(mSkyPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mSkyQuadBuffer);
        mSkyQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mSkyTexHandle);
        GLES20.glVertexAttribPointer(mSkyTexHandle, 2, GLES20.GL_FLOAT, false, 16, mSkyQuadBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexNight);
        GLES20.glUniform1i(mSkySamplerNightHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSunrise);
        GLES20.glUniform1i(mSkySamplerSunriseHandle, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSunset);
        GLES20.glUniform1i(mSkySamplerSunsetHandle, 2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSky);
        GLES20.glUniform1i(mSkySamplerSkyHandle, 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSolarEclipse);
        GLES20.glUniform1i(mSkySamplerSolarEclipseHandle, 4);
        GLES20.glUniform1f(mSkyWeightNightHandle, sd.accurateWeights[0]);
        GLES20.glUniform1f(mSkyWeightSunriseHandle, sd.accurateWeights[1]);
        GLES20.glUniform1f(mSkyWeightSunsetHandle, sd.accurateWeights[2]);
        GLES20.glUniform1f(mSkyWeightSkyHandle, sd.accurateWeights[3]);
        GLES20.glUniform1f(mSkyWeightSolarEclipseHandle, sd.solarEclipseWeight);
        GLES20.glUniform1f(mSkyNightInvertHandle, sd.nightInvert ? 1.0f : 0.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mSkyPositionHandle);
        GLES20.glDisableVertexAttribArray(mSkyTexHandle);
    }

    private void drawSun(GrassScene.SceneData sd) {
        if (mTexSun == 0) return;
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        drawSprite(mTexSun, sd.sunX, sd.sunY, sd.sunSize, sd.sunAlpha, false, 0.0f);
        if (sd.hasSolarEclipseOcclusion && mTexMoonMask != 0) {
            drawSolarEclipseOcclusion(sd);
        }
    }

    private void drawSolarEclipseOcclusion(GrassScene.SceneData sd) {
        GrassScene.SolarEclipse eclipse = sd.solarEclipseAtSun;
        float sunX = sd.eclipseSunX, sunY = sd.eclipseSunY, sunSize = sd.eclipseSunSize;
        float sunAlpha = sd.eclipseSunAlpha;
        float moonX = sd.eclipseMoonX, moonY = sd.eclipseMoonY;

        float sunDiscSize = sunSize * GrassScene.SUN_PHOTOSPHERE_SCALE;
        float sunRadius = sunDiscSize * 0.5f;
        float moonSize = sunDiscSize * eclipse.moonRadiusRatio;
        float moonRadius = moonSize * 0.5f;
        float dx = moonX - sunX, dy = moonY - sunY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance >= (sunRadius + moonRadius)) return;

        float overlapFactor = 1.0f - clamp(distance / (sunRadius + moonRadius), 0.0f, 1.0f);
        float maskAlpha = clamp(sunAlpha * (0.45f + 0.55f * eclipse.fraction) * overlapFactor, 0.0f, 1.0f);
        if (maskAlpha <= 0.001f) return;

        useProgram(mMoonProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mMoonMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES20.glUniform1f(mMoonPhaseHandle, 0.0f);
        GLES20.glUniform1f(mMoonBrightnessHandle, 1.0f);
        GLES20.glUniform1f(mMoonAlphaHandle, maskAlpha);
        GLES20.glUniform1i(mMoonIsDaytimeHandle, 1);
        GLES20.glUniform1f(mMoonContrastHandle, 1.0f);
        GLES20.glUniform1f(mMoonSaturationHandle, 1.0f);
        GLES20.glUniform1f(mMoonBlueTintHandle, 0.0f);
        GLES20.glUniform1i(mMoonEclipseTypeHandle, 0);
        GLES20.glUniform1f(mMoonEclipseFractionHandle, 0.0f);
        GLES20.glUniform1f(mMoonEclipsePhaseHandle, 0.0f);
        GLES20.glUniform2f(mMoonShadowOffsetHandle, 0.0f, 0.0f);
        GLES20.glUniform3f(mMoonShadowColorHandle, 0.0f, 0.0f, 0.0f);
        GLES20.glUniform3f(mMoonPenumbraColorHandle, 0.0f, 0.0f, 0.0f);
        GLES20.glUniform1f(mMoonSolarOcclusionHandle, 1.0f);
        drawMoonSprite(moonX, moonY, moonSize);
        GLES20.glUniform1f(mMoonSolarOcclusionHandle, 0.0f);
    }

    private void drawMoon(GrassScene.SceneData sd) {
        if (!sd.useAccurateSun || !sd.moonEnabled || !sd.moonVisible) return;
        if (mMoonProgram == 0 || mTexMoonBase == 0 || mTexMoonMask == 0) return;

        useProgram(mMoonProgram);
        GrassScene.MoonEclipse eclipse = sd.moonEclipse;
        if (sd.moonIsDaytime) {
            setBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR);
        } else {
            setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }
        GLES20.glUniformMatrix4fv(mMoonMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES20.glUniform1f(mMoonPhaseHandle, sd.moonPhaseAngle);
        GLES20.glUniform1f(mMoonBrightnessHandle, sd.moonBrightness);
        GLES20.glUniform1f(mMoonAlphaHandle, sd.moonAlpha);
        GLES20.glUniform1i(mMoonIsDaytimeHandle, sd.moonIsDaytime ? 1 : 0);
        GLES20.glUniform1f(mMoonContrastHandle, sd.moonContrast);
        GLES20.glUniform1f(mMoonSaturationHandle, sd.moonSaturation);
        GLES20.glUniform1f(mMoonBlueTintHandle, sd.moonBlueTint);
        GLES20.glUniform1i(mMoonEclipseTypeHandle, eclipse != null ? eclipse.type : 0);
        GLES20.glUniform1f(mMoonEclipseFractionHandle, eclipse != null ? eclipse.fraction : 0.0f);
        GLES20.glUniform1f(mMoonEclipsePhaseHandle, eclipse != null ? eclipse.phase : 0.0f);
        GLES20.glUniform2f(mMoonShadowOffsetHandle,
                eclipse != null ? eclipse.shadowOffsetX : 0.0f,
                eclipse != null ? eclipse.shadowOffsetY : 0.0f);
        GLES20.glUniform3f(mMoonShadowColorHandle, 0.6f, 0.2f, 0.1f);
        GLES20.glUniform3f(mMoonPenumbraColorHandle, 0.2f, 0.2f, 0.2f);
        GLES20.glUniform1f(mMoonSolarOcclusionHandle, 0.0f);
        drawMoonSprite(sd.moonX, sd.moonY, sd.moonSize);
    }

    private void drawMoonSprite(float cx, float cy, float size) {
        if (mMoonBuffer == null) {
            mMoonBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        float half = size * 0.5f;
        mQuadVerts[0] = cx - half;  mQuadVerts[1] = cy - half;  mQuadVerts[2] = 0.0f; mQuadVerts[3] = 0.0f;
        mQuadVerts[4] = cx - half;  mQuadVerts[5] = cy + half;  mQuadVerts[6] = 0.0f; mQuadVerts[7] = 1.0f;
        mQuadVerts[8] = cx + half;  mQuadVerts[9] = cy + half;  mQuadVerts[10] = 1.0f; mQuadVerts[11] = 1.0f;
        mQuadVerts[12] = cx + half; mQuadVerts[13] = cy - half; mQuadVerts[14] = 1.0f; mQuadVerts[15] = 0.0f;
        mMoonBuffer.clear();
        mMoonBuffer.put(mQuadVerts).position(0);
        GLES20.glEnableVertexAttribArray(mMoonPositionHandle);
        GLES20.glVertexAttribPointer(mMoonPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mMoonBuffer);
        mMoonBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mMoonTexHandle);
        GLES20.glVertexAttribPointer(mMoonTexHandle, 2, GLES20.GL_FLOAT, false, 16, mMoonBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexMoonBase);
        GLES20.glUniform1i(mMoonSamplerBaseHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexMoonMask);
        GLES20.glUniform1i(mMoonSamplerMaskHandle, 1);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mMoonPositionHandle);
        GLES20.glDisableVertexAttribArray(mMoonTexHandle);
    }

    private void drawBlades(GrassScene.SceneData sd, float brightness, float xOffset, float nightDesat) {
        if (!sd.grassEnabled || sd.blades == null) return;
        if (mGrassVertexBuffer == null || mGrassIndexBuffer == null) return;

        mGrassVertexBuffer.clear();
        for (GrassScene.Blade blade : sd.blades) {
            appendBladeVertices(blade, sd, brightness, xOffset, nightDesat, mGrassVertexBuffer);
        }
        mGrassVertexBuffer.position(0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexAA);
        GLES20.glUniform1i(mGrassSamplerHandle, 0);

        GLES20.glEnableVertexAttribArray(mGrassPositionHandle);
        GLES20.glVertexAttribPointer(mGrassPositionHandle, 2, GLES20.GL_FLOAT, false, 32, mGrassVertexBuffer);
        mGrassVertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mGrassColorHandle);
        GLES20.glVertexAttribPointer(mGrassColorHandle, 4, GLES20.GL_FLOAT, false, 32, mGrassVertexBuffer);
        mGrassVertexBuffer.position(6);
        GLES20.glEnableVertexAttribArray(mGrassTexHandle);
        GLES20.glVertexAttribPointer(mGrassTexHandle, 2, GLES20.GL_FLOAT, false, 32, mGrassVertexBuffer);

        mGrassIndexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mScene.mIndexCount, GLES20.GL_UNSIGNED_SHORT, mGrassIndexBuffer);

        GLES20.glDisableVertexAttribArray(mGrassPositionHandle);
        GLES20.glDisableVertexAttribArray(mGrassColorHandle);
        GLES20.glDisableVertexAttribArray(mGrassTexHandle);
    }

    private void appendBladeVertices(GrassScene.Blade blade, GrassScene.SceneData sd,
            float brightness, float xOffset, float nightDesat, FloatBuffer out) {
        float scale = blade.scale * sd.grassWidthScale;
        float angle = blade.angle; // already updated by GrassScene.updateBladeAngles()
        float xpos = blade.xPos + xOffset;
        int size = blade.size;

        float h = blade.h, s = blade.s;
        float v = mix(0.0f, blade.b, brightness);
        if (sd.useGrassTint) {
            h = sd.grassTintH;
            s = sd.grassTintS;
            v = clamp(v * sd.grassTintV, 0.0f, 1.0f);
        }
        if (sd.nightDesaturateGrass && nightDesat > 0.0f) {
            s = mix(s, 0.0f, clamp(nightDesat, 0.0f, 1.0f));
        }
        int color = hsbToRgb(h, s, v);
        float r = Color.red(color) / 255.0f;
        float g = Color.green(color) / 255.0f;
        float b = Color.blue(color) / 255.0f;

        float currentAngle = (float) (Math.PI * 0.5);
        float bottomX = xpos, bottomY = blade.yPos;
        float d = angle * blade.hardness * sd.grassHardnessScale;

        float si = size * scale;
        putVertex(out, bottomX - si, bottomY + GrassScene.HALF_TESSELATION, r, g, b, 1.0f, 0.0f, 0.0f);
        putVertex(out, bottomX + si, bottomY + GrassScene.HALF_TESSELATION, r, g, b, 1.0f, 1.0f, 0.0f);

        for (; size > 0; size -= 1) {
            float lengthX = blade.lengthX * sd.grassHeightScale;
            float lengthY = blade.lengthY * sd.grassHeightScale;
            float topX = bottomX - (float) Math.cos(currentAngle) * lengthX;
            float topY = bottomY - (float) Math.sin(currentAngle) * lengthY;
            si = (float) size * scale;
            float spi = si - scale;
            putVertex(out, topX - spi, topY, r, g, b, 1.0f, 0.0f, 0.0f);
            putVertex(out, topX + spi, topY, r, g, b, 1.0f, 1.0f, 0.0f);
            bottomX = topX;
            bottomY = topY;
            currentAngle += d;
        }
    }

    private void putVertex(FloatBuffer out, float x, float y, float r, float g, float b, float a, float s, float t) {
        out.put(x); out.put(y); out.put(r); out.put(g); out.put(b); out.put(a); out.put(s); out.put(t);
    }

    // ---- Sprite drawing ----

    private void drawSprites(GrassScene.SceneData sd) {
        if (sd.legacyParticles) {
            drawLegacyParticles(sd);
            return;
        }
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        if (!sd.isNight && sd.dandelionEnabled && mTexDandelion != 0 && sd.dandelions != null) {
            for (GrassScene.Dandelion d : sd.dandelions) {
                float sway = (float) Math.sin(d.swayPhase + sd.animNowMs * 0.001f * d.swaySpeed) * 6.0f;
                drawSprite(mTexDandelion, d.x, d.y + sway, d.size, 0.9f, true, d.rotationDeg);
            }
        }

        if (sd.isNight && sd.fireflyEnabled && mTexFirefly != 0 && sd.fireflies != null) {
            float time = sd.animNowMs * 0.001f;
            for (GrassScene.Firefly f : sd.fireflies) {
                float flicker = 0.5f + 0.5f * (float) Math.sin(f.phase + time * f.flickerSpeed);
                float alpha = 0.2f + 0.8f * flicker;
                float size = f.size * (0.8f + 0.4f * flicker);
                drawSprite(mTexFirefly, f.x, f.y, size, alpha, false, 0.0f);
            }
        }
    }

    private void drawWeatherOverlays(GrassScene.SceneData sd, boolean frontPass) {
        if (!mWeatherEnabled || sd.weatherCondition == null) {
            return;
        }
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        if (!frontPass) {
            drawWeatherTone(sd);
        }

        switch (sd.weatherCondition) {
            case D2_CLOUDY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight);
                }
                break;
            case D3_DREARY:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight);
                }
                break;
            case D4_FOG:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight);
                } else {
                    drawFogLayer(sd.weatherCondition);
                }
                break;
            case D5_RAIN_SHOWERS:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight);
                }
                drawRainLayer(sd.animNowMs, 12, frontPass);
                break;
            case D6_THUNDERSTORMS:
                if (!frontPass) {
                    mThunderFlashAlpha = drawLightningSweep(sd.animNowMs);
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight);
                }
                drawRainLayer(sd.animNowMs, 25, frontPass);
                if (frontPass && mThunderFlashAlpha > 0.0f && mTexWeatherFlash != 0) {
                    float fullSize = Math.max(mWidth, mHeight) * 2.4f;
                    drawSprite(mTexWeatherFlash, mWidth * 0.5f, mHeight * 0.5f,
                            fullSize, clamp(mThunderFlashAlpha, 0.0f, 0.58f), false, 0.0f);
                }
                break;
            case D7_FLURRIES_SNOW:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 2, sd.isNight);
                }
                drawSnowLayer(sd.animNowMs, 12, frontPass);
                break;
            case D8_ICE_COLD:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 8, sd.isNight);
                }
                drawSnowLayer(sd.animNowMs, 25, frontPass);
                break;
            case D9_SLEET:
                if (!frontPass) {
                    drawCloudLayer(sd.weatherCondition, sd.animNowMs, 12, sd.isNight);
                }
                drawRainLayer(sd.animNowMs, 25, frontPass);
                drawSnowLayer(sd.animNowMs, 12, frontPass);
                break;
            case D1_CLEAR:
            default:
                if (!frontPass) {
                    resetThunderState();
                }
                break;
        }

        if (!frontPass && sd.weatherCondition != WeatherCondition.D6_THUNDERSTORMS) {
            resetThunderState();
        }
    }

    private void drawWeatherTone(GrassScene.SceneData sd) {
        if (sd.isNight) {
            return;
        }
        float alpha = 0.0f;
        switch (sd.weatherCondition) {
            case D2_CLOUDY:
                alpha = 0.05f;
                break;
            case D3_DREARY:
                alpha = 0.08f;
                break;
            case D4_FOG:
                alpha = 0.10f;
                break;
            case D5_RAIN_SHOWERS:
                alpha = 0.13f;
                break;
            case D6_THUNDERSTORMS:
                alpha = 0.18f;
                break;
            case D7_FLURRIES_SNOW:
                alpha = 0.06f;
                break;
            case D8_ICE_COLD:
                alpha = 0.07f;
                break;
            case D9_SLEET:
                alpha = 0.14f;
                break;
            case D1_CLEAR:
            default:
                break;
        }
            if (mTexWeatherTone == 0 || alpha <= 0.001f) {
            return;
        }
            // Extend 32px outside vertical bounds to avoid bottom-edge seams on non-16:9 screens.
            GLES20.glUniform1f(mBgAlphaHandle, alpha);
            drawBackgroundQuad(mTexWeatherTone,
                0.0f, -32.0f, 0.0f, 0.0f,
                0.0f, mHeight + 32.0f, 0.0f, 1.0f,
                mWidth, mHeight + 32.0f, 1.0f, 1.0f,
                mWidth, -32.0f, 1.0f, 0.0f);
            GLES20.glUniform1f(mBgAlphaHandle, 1.0f);
    }

    // drawFogLayer: faithful to original drawFog(). Draws haze (fog_01) full-width at screen
    // bottom, then fog (fog_02) above it. Only active for D4_FOG (Weather_Fog).
    private void drawFogLayer(WeatherCondition condition) {
        if (mTexWeatherFog1 == 0 || mTexWeatherFog2 == 0) return;
        if (condition != WeatherCondition.D4_FOG) return;
        float hazeDrawW = mWidth + 40f;
        float hazeDrawH = FOG1_H_OVER_W * mWidth;
        float hazeTop = mHeight - hazeDrawH;
        drawRect(mTexWeatherFog1, -20f, hazeTop, hazeDrawW, hazeDrawH, 1.0f);
        float fogDrawH = FOG2_H_OVER_W * mWidth * 1.2f;
        float fogTop = hazeTop - (FOG2_H_OVER_W * mWidth / 2.0f);
        drawRect(mTexWeatherFog2, 0f, fogTop, mWidth, fogDrawH, 1.0f);
    }

    private int cloudTexForIndex(int idx) {
        switch (idx) {
            case 0: return mTexWeatherCloud1;
            case 1: return mTexWeatherCloud2;
            case 2: return mTexWeatherCloud3;
            case 3: return mTexWeatherCloud4;
            default: return mTexWeatherCloud1;
        }
    }

    private int cloudTexIndexForWeather(WeatherCondition condition, int i) {
        switch (condition) {
            case D7_FLURRIES_SNOW:
                return 0;
            case D2_CLOUDY:
            case D4_FOG:
            case D8_ICE_COLD:
                return (i & 1) == 0 ? 2 : 0;
            case D3_DREARY:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            case D9_SLEET:
                return (i & 1) == 0 ? 3 : 2;
            default:
                return 0;
        }
    }

    private void drawCloudLayer(WeatherCondition condition, long animNowMs, int cloudCount, boolean isNight) {
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int cond = condition.ordinal();
        float tSec = animNowMs / 1000.0f;
        float cloudAlpha = isNight ? 0.30f : 1.0f;
        for (int i = 0; i < cloudCount; i++) {
            int texIdx = cloudTexIndexForWeather(condition, i);
            int texture = cloudTexForIndex(texIdx);
            if (texture == 0) continue;
            float cloudW = CLOUD_MDPI_W[texIdx] * mDensity;
            float cloudH = CLOUD_MDPI_H[texIdx] * mDensity;
            float speed = 8.0f * (1.0f + hash01((long) (i * 7 + cond * 31)));
            float cycleLen = cloudW + mWidth;
            float phase = hash01((long) (i * 13 + cond * 17 + 1000)) * cycleLen;
            float xPos = (phase + speed * tSec) % cycleLen - cloudW;
            float yOff = hash01((long) (i * 11 + cond * 7 + 2000)) * (cloudH / 2.0f) - cloudH / 4.0f;
            drawRect(texture, xPos, yOff, cloudW, cloudH, cloudAlpha);
        }
    }

    private void drawRainLayer(long animNowMs, int count, boolean frontPass) {
        if (mTexWeatherRain1 == 0 || mTexWeatherRain2 == 0 || mTexWeatherRain3 == 0) return;
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        float tSec = animNowMs / 1000.0f;
        for (int i = 0; i < count; i++) {
            boolean front = hash01(i * 37L + 991L) > 0.5f;
            if (front != frontPass) continue;
            int texIdx = i % 3;
            int texture;
            switch (texIdx) {
                case 0:
                    texture = mTexWeatherRain1;
                    break;
                case 1:
                    texture = mTexWeatherRain2;
                    break;
                default:
                    texture = mTexWeatherRain3;
                    break;
            }
            float rainW = RAIN_MDPI_W[texIdx] * mDensity;
            float rainH = RAIN_MDPI_H[texIdx] * mDensity;
            float xPos = hash01((long) (i * 17 + 503)) * mWidth;
            float speed = 300.0f + hash01((long) (i * 31 + 271)) * 50.0f;
            float cycleLen = rainH + mHeight;
            float phase = hash01((long) (i * 23 + 713)) * cycleLen;
            float yPos = (phase + speed * tSec) % cycleLen - rainH;
            // Rain texture needs vertical flip to match original drop direction.
            drawRectUv(texture,
                    xPos, yPos, xPos + rainW, yPos + rainH,
                    0.0f, 0.0f,
                    1.0f, 1.0f,
                    1.0f);
        }
    }

    private void drawSnowLayer(long animNowMs, int count, boolean frontPass) {
        if (mTexWeatherSnow1 == 0 || mTexWeatherSnow2 == 0 || mTexWeatherSnow3 == 0 || mTexWeatherSnow4 == 0) {
            return;
        }
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        float tSec = animNowMs / 1000.0f;
        for (int i = 0; i < count; i++) {
            boolean front = hash01(i * 41L + 577L) > 0.5f;
            if (front != frontPass) continue;
            float radiusOffset = hash01((long) (i * 7 + 101)) * 4.0f;
            float size = (2.0f + radiusOffset) * 2.0f;
            float speed = 40.0f + hash01((long) (i * 11 + 137)) * (6.0f - (2.0f + radiusOffset)) * 5.0f;
            float xPos = hash01((long) (i * 13 + 199)) * mWidth;
            float cycleLen = size + mHeight;
            float phase = hash01((long) (i * 19 + 317)) * cycleLen;
            float yPos = (phase + speed * tSec) % cycleLen - size;
            int texIdx = i & 3;
            int texture;
            switch (texIdx) {
                case 0:
                    texture = mTexWeatherSnow1;
                    break;
                case 1:
                    texture = mTexWeatherSnow2;
                    break;
                case 2:
                    texture = mTexWeatherSnow3;
                    break;
                default:
                    texture = mTexWeatherSnow4;
                    break;
            }
            drawSprite(texture, xPos, yPos + size * 0.5f, size, 1.0f, false, 0.0f);
        }
    }

    // drawLightningSweep: faithful to original Thunder.java behavior.
    // - random delay 2-8s between strikes
    // - each strike lasts 200ms
    // - reveal area sweeps across screen with growing clip
    // returns fullscreen white flash alpha used in front pass.
    private float drawLightningSweep(long animNowMs) {
        if (mTexWeatherLightning1 == 0 || mTexWeatherLightning2 == 0 || mTexWeatherLightning3 == 0) {
            return 0.0f;
        }

        if (mThunderActiveStartMs == 0L && mThunderNextStartMs == 0L) {
            mThunderNextStartMs = animNowMs + 2000L + (long) (hash01(animNowMs * 31L + 7L) * 6000.0f);
        }

        if (mThunderActiveStartMs == 0L && animNowMs >= mThunderNextStartMs) {
            mThunderActiveStartMs = animNowMs;
            mThunderTextureIndex = (int) (hash01(animNowMs * 13L + 17L) * 3.0f) % 3;
            mThunderLTR = hash01(animNowMs * 19L + 23L) > 0.5f;
        }

        if (mThunderActiveStartMs == 0L) {
            return 0.0f;
        }

        long elapsed = animNowMs - mThunderActiveStartMs;
        if (elapsed >= 200L) {
            mThunderActiveStartMs = 0L;
            mThunderNextStartMs = animNowMs + 2000L + (long) (hash01(animNowMs * 29L + 31L) * 6000.0f);
            return 0.0f;
        }

        float progress = clamp(elapsed / 200.0f, 0.0f, 1.0f);
        float clipW = mWidth * progress;
        float clipH = mHeight * progress;
        float left = mThunderLTR ? 0.0f : (mWidth - clipW);
        float right = left + clipW;
        float top = 0.0f;
        float bottom = clipH;
        int texture;
        switch (mThunderTextureIndex) {
            case 0:
                texture = mTexWeatherLightning1;
                break;
            case 1:
                texture = mTexWeatherLightning2;
                break;
            default:
                texture = mTexWeatherLightning3;
                break;
        }
        drawRectUv(texture,
                left, top, right, bottom,
                left / mWidth, 1.0f - top / mHeight,
                right / mWidth, 1.0f - bottom / mHeight,
                1.0f);

        return clamp(((elapsed / 200.0f) + 0.2f) * 0.5f, 0.0f, 1.0f);
    }

    private void resetThunderState() {
        mThunderNextStartMs = 0L;
        mThunderActiveStartMs = 0L;
        mThunderTextureIndex = 0;
        mThunderLTR = true;
        mThunderFlashAlpha = 0.0f;
    }

    private static float thunderFlashAlpha(long animNowMs) {
        long segment = animNowMs / 7000L;
        long local = animNowMs % 7000L;
        long base = 1300L + (long) (hash01(segment * 37L + 11L) * 3600.0f);
        float a1 = pulseAlpha(local - base, 70L);
        float a2 = pulseAlpha(local - (base + 110L), 60L) * 0.85f;
        float a3 = pulseAlpha(local - (base + 250L), 85L) * 0.55f;
        long recoilDelay = 80L + (long) (hash01(segment * 97L + 19L) * 40.0f);
        float recoil = pulseAlpha(local - (base + recoilDelay), 45L) * 0.46f;
        return clamp(a1 + a2 + a3 + recoil, 0.0f, 1.0f);
    }

    private static float thunderAfterglowAlpha(long animNowMs) {
        long segment = animNowMs / 7000L;
        long local = animNowMs % 7000L;
        long base = 1300L + (long) (hash01(segment * 37L + 11L) * 3600.0f);
        float a1 = pulseAlpha(local - (base + 30L), 210L) * 0.85f;
        float a2 = pulseAlpha(local - (base + 145L), 190L) * 0.65f;
        float a3 = pulseAlpha(local - (base + 290L), 240L) * 0.45f;
        return clamp(a1 + a2 + a3, 0.0f, 1.0f);
    }

    private static float pulseAlpha(long deltaMs, long widthMs) {
        float t = Math.abs(deltaMs) / (float) widthMs;
        return t >= 1.0f ? 0.0f : (1.0f - t);
    }

    private static float hash01(long v) {
        long x = v;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        long masked = x & 0x7fffffffL;
        return masked / 2147483647.0f;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private void drawLegacyParticles(GrassScene.SceneData sd) {
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        long animNowMs = sd.legacyNow;
        int legacyType = sd.legacyType;

        drawLegacyParticleSet(sd.legacyNormal, legacyType, false, animNowMs);
        drawLegacyParticleSet(sd.legacyExtras, legacyType, true, animNowMs);
    }

    private void drawLegacyParticleSet(GrassScene.LegacyParticle[] particles, int legacyType,
            boolean isExtras, long animNowMs) {
        if (particles == null) {
            return;
        }

        for (int i = 0; i < particles.length; i++) {
            GrassScene.LegacyParticle p = particles[i];
            if (p == null || !p.active) continue;
            long delta = animNowMs - p.startTime;
            if (delta < 0L) continue;
            boolean outOfBounds = isLegacyParticleOutOfBounds(p, legacyType);
            if (outOfBounds) {
                GrassScene.LegacyParticle np = mScene.createLegacyParticle(legacyType);
                np.active = true;
                particles[i] = np;
                p = np;
                delta = animNowMs - p.startTime;
                if (delta < 0L) continue;
            }
            if ((p.stayEndTime - animNowMs) <= 0L) {
                if (legacyType == GrassScene.LEGACY_TYPE_DANDELION) {
                    mScene.flyLegacyDandelion(p, false);
                } else {
                    mScene.flyLegacyFirefly(p, false);
                }
            }
            p.startTime = animNowMs;
            drawLegacyParticle(p, legacyType, i + (isExtras ? 100 : 0), isExtras, animNowMs);
        }
    }

    private boolean isLegacyParticleOutOfBounds(GrassScene.LegacyParticle p, int legacyType) {
        if (legacyType == GrassScene.LEGACY_TYPE_DANDELION) {
            return p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0 || p.originY > mHeight;
        } else {
            return p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0;
        }
    }

    private void drawLegacyParticle(GrassScene.LegacyParticle p, int legacyType, int index,
            boolean isExtras, long animNowMs) {
        if (legacyType == GrassScene.LEGACY_TYPE_FIREFLY) {
            long interval = p.flareEndTime - p.silentEndTime;
            if (animNowMs >= p.flareEndTime && interval > 0L) {
                p.silentEndTime = animNowMs
                        + (long) ((1.0 + (Math.random() * 2 - 1) * GrassScene.LEGACY_INTERVAL_VARIANCE)
                        * GrassScene.LEGACY_MAX_INTERVAL);
            } else if (animNowMs >= p.silentEndTime && interval < 0L) {
                p.flareEndTime = animNowMs + GrassScene.LEGACY_MAX_FLARE;
            }
            int tex = (animNowMs < p.flareEndTime) ? mTexFirefly2 : mTexFirefly1;
            float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + index * 1234) * 0.002);
            float alpha = 0.2f + 0.8f * flicker;
            float size = (isExtras ? 48.0f : 72.0f) * (0.8f + 0.4f * flicker);
            drawSprite(tex, p.originX, p.originY, size, alpha, false, 0.0f);
        } else {
            float size = isExtras ? 64.0f : 96.0f;
            drawSprite(mTexDandelion, p.originX, p.originY, size, 0.9f, true, p.angle);
        }
    }

    private void drawSprite(int texture, float cx, float cy, float size, float alpha, boolean flipV,
            float rotationDeg) {
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        float half = size * 0.5f;
        float rad = (float) Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);

        float x0 = (-half * cos) - (-half * sin) + cx, y0 = (-half * sin) + (-half * cos) + cy;
        float x1 = (-half * cos) - ( half * sin) + cx, y1 = (-half * sin) + ( half * cos) + cy;
        float x2 = ( half * cos) - ( half * sin) + cx, y2 = ( half * sin) + ( half * cos) + cy;
        float x3 = ( half * cos) - (-half * sin) + cx, y3 = ( half * sin) + (-half * cos) + cy;

        float v0 = flipV ? 0.0f : 1.0f, v1 = flipV ? 1.0f : 0.0f;
        mQuadVerts[0] = x0;  mQuadVerts[1] = y0;  mQuadVerts[2] = 0.0f; mQuadVerts[3] = v0;
        mQuadVerts[4] = x1;  mQuadVerts[5] = y1;  mQuadVerts[6] = 0.0f; mQuadVerts[7] = v1;
        mQuadVerts[8] = x2;  mQuadVerts[9] = y2;  mQuadVerts[10] = 1.0f; mQuadVerts[11] = v1;
        mQuadVerts[12] = x3; mQuadVerts[13] = y3; mQuadVerts[14] = 1.0f; mQuadVerts[15] = v0;
        mSpriteBuffer.clear();
        mSpriteBuffer.put(mQuadVerts).position(0);

        GLES20.glEnableVertexAttribArray(mBgPositionHandle);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        mSpriteBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mBgTexHandle);
        GLES20.glVertexAttribPointer(mBgTexHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mBgSamplerHandle, 0);
        GLES20.glUniform1f(mBgAlphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgTexHandle);
    }

    // drawRect: draws texture as rectangle with top-left at (left,top), dimensions (w,h).
    // Same UV convention as drawSprite: v=1 at screen top, v=0 at screen bottom.
    private void drawRect(int texture, float left, float top, float w, float h, float alpha) {
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        float right = left + w, bottom = top + h;
        mQuadVerts[0] = left;  mQuadVerts[1] = top;    mQuadVerts[2] = 0.0f; mQuadVerts[3] = 1.0f;
        mQuadVerts[4] = left;  mQuadVerts[5] = bottom; mQuadVerts[6] = 0.0f; mQuadVerts[7] = 0.0f;
        mQuadVerts[8] = right; mQuadVerts[9] = bottom; mQuadVerts[10] = 1.0f; mQuadVerts[11] = 0.0f;
        mQuadVerts[12] = right; mQuadVerts[13] = top;  mQuadVerts[14] = 1.0f; mQuadVerts[15] = 1.0f;
        mSpriteBuffer.clear();
        mSpriteBuffer.put(mQuadVerts).position(0);
        GLES20.glEnableVertexAttribArray(mBgPositionHandle);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        mSpriteBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mBgTexHandle);
        GLES20.glVertexAttribPointer(mBgTexHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mBgSamplerHandle, 0);
        GLES20.glUniform1f(mBgAlphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgTexHandle);
    }

    private void drawRectUv(int texture,
            float left, float top, float right, float bottom,
            float uLeft, float vTop, float uRight, float vBottom,
            float alpha) {
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        mQuadVerts[0] = left;  mQuadVerts[1] = top;    mQuadVerts[2] = uLeft;  mQuadVerts[3] = vTop;
        mQuadVerts[4] = left;  mQuadVerts[5] = bottom; mQuadVerts[6] = uLeft;  mQuadVerts[7] = vBottom;
        mQuadVerts[8] = right; mQuadVerts[9] = bottom; mQuadVerts[10] = uRight; mQuadVerts[11] = vBottom;
        mQuadVerts[12] = right; mQuadVerts[13] = top;  mQuadVerts[14] = uRight; mQuadVerts[15] = vTop;
        mSpriteBuffer.clear();
        mSpriteBuffer.put(mQuadVerts).position(0);
        GLES20.glEnableVertexAttribArray(mBgPositionHandle);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        mSpriteBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mBgTexHandle);
        GLES20.glVertexAttribPointer(mBgTexHandle, 2, GLES20.GL_FLOAT, false, 16, mSpriteBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mBgSamplerHandle, 0);
        GLES20.glUniform1f(mBgAlphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgTexHandle);
    }
    private void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) {
            return;
        }
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    private void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) {
            return;
        }
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) {
            mDiagMaxMs = frameCostMs;
        }
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(TAG, "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
        }
    }

    private void onWeatherUpdated(WeatherState state) {
        if (state == null || !mWeatherEnabled) {
            return;
        }
        mPendingWeatherState = state;
    }

    private boolean computePreviewIsNight() {
        long nowMs = System.currentTimeMillis();
        long sunriseUtc = mPrefs != null ? mPrefs.getLong("last_sunrise", 0L) : 0L;
        long sunsetUtc = mPrefs != null ? mPrefs.getLong("last_sunset", 0L) : 0L;
        if (sunriseUtc > 0L && sunsetUtc > 0L) {
            long nowSec = nowMs / 1000L;
            return nowSec < sunriseUtc || nowSec >= sunsetUtc;
        }
        Calendar calendar = Calendar.getInstance();
        int time = (calendar.get(Calendar.HOUR_OF_DAY) * 100) + calendar.get(Calendar.MINUTE);
        return time < 600 || time > 1800;
    }

    // ---- Math utilities (local copies for GL rendering code) ----

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    private static float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    private static int hsbToRgb(float h, float s, float b) {
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
}
