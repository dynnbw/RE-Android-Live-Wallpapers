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

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.settings.WallpaperSettings;

import java.util.ArrayList;
import java.util.List;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static com.reandroid.wallpaper.grass.GrassConstants.HALF_TESSELATION;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_INTERVAL_VARIANCE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_FLARE;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_MAX_INTERVAL;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_DANDELION;
import static com.reandroid.wallpaper.grass.GrassConstants.LEGACY_TYPE_FIREFLY;
import static com.reandroid.wallpaper.grass.GrassConstants.SUN_PHOTOSPHERE_SCALE;
import com.reandroid.utils.MathUtils;

/**
 * Grass 壁纸渲染层（OpenGL ES 2.0），所有状态委托 GrassScene 管理。
 */
public class GrassGL extends GLESScene {

    private static final String TAG = "GrassGL";
    private static final int LEGACY_FIREFLY_ALPHA_BIN_COUNT = 8;
    private static final int LEGACY_BATCH_GROUP_DANDELION = 0;
    private static final int LEGACY_BATCH_GROUP_FIREFLY1_START = 1;
    private static final int LEGACY_BATCH_GROUP_FIREFLY2_START = LEGACY_BATCH_GROUP_FIREFLY1_START + LEGACY_FIREFLY_ALPHA_BIN_COUNT;
    private static final int LEGACY_BATCH_GROUP_COUNT = LEGACY_BATCH_GROUP_FIREFLY2_START + LEGACY_FIREFLY_ALPHA_BIN_COUNT;
    private static final int LEGACY_FLOATS_PER_VERTEX = 4;
    private static final int LEGACY_FLOATS_PER_QUAD = 6 * LEGACY_FLOATS_PER_VERTEX;

    // ---- 场景逻辑层（非 GL）----
    private final Context mContext;
    private final GrassScene mScene;
    private final GrassSpriteRenderer mSpriteRenderer = new GrassSpriteRenderer();
    private final GrassBackgroundRenderer mBackgroundRenderer = new GrassBackgroundRenderer();
    private final GrassWeatherRenderer mWeatherRenderer = new GrassWeatherRenderer();
    private final GrassStarRenderer mStarRenderer = new GrassStarRenderer();
    private final GrassWeatherRenderer.RenderOps mWeatherRenderOps = new GrassWeatherRenderer.RenderOps() {
        @Override
        public void useBackgroundProgram() {
            useProgram(mBackgroundProgram);
        }

        @Override
        public void setAlphaBlend() {
            setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }
    };
    private GrassLegacyParticleRenderer mLegacyParticleRenderer;

    private final GrassStarRenderer.RenderOps mStarRenderOps = new GrassStarRenderer.RenderOps() {
        @Override
        public void useBackgroundProgram() {
            useProgram(mBackgroundProgram);
        }

        @Override
        public void setAlphaBlend() {
            setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }
    };

    // ---- GL state ----
    private boolean mGLInitialized = false;

    // Shader programs
    private int mBackgroundProgram;
    private int mSkyProgram;
    private int mGrassProgram;
    private int mMoonProgram;
    private int mSunProgram;

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

    // Sun program handles
    private int mSunPositionHandle;
    private int mSunTexHandle;
    private int mSunMatrixHandle;
    private int mSunTimeHandle;
    private int mSunOpacityHandle;
    private int mSunLineAlphaHandle;
    private int mSunResolutionHandle;
    private int mSunSunPosHandle;

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
    private int mTexMoonBase;
    private int mTexMoonMask;

    // NIO buffers
    private FloatBuffer mGrassVertexBuffer;
    private ShortBuffer mGrassIndexBuffer;
    private FloatBuffer mMoonBuffer;
    private final float[] mQuadVerts = new float[16];
    private final float[][] mLegacyBatchVertices = new float[LEGACY_BATCH_GROUP_COUNT][];
    private final int[] mLegacyBatchFloatCounts = new int[LEGACY_BATCH_GROUP_COUNT];

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
    private final GrassWeatherIntegration mWeatherIntegration = new GrassWeatherIntegration();
    private float mDensity = 1.0f;
    private int[][] mSkyFieldNight;
    private int[][] mSkyFieldSunrise;
    private int[][] mSkyFieldSunset;
    private int[][] mSkyFieldDay;
    // Sky fields moved to res/raw/grass_sky_fields.txt and loaded at runtime.

    // ---- Constructor ----

    public GrassGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new GrassScene(width, height);
        mLegacyParticleRenderer = new GrassLegacyParticleRenderer(mScene, mSpriteRenderer);
        mBackgroundRenderer.setViewport(width, height);
        mWeatherRenderer.setViewport(width, height);
        mStarRenderer.setViewport(width, height);
    }

    // ---- Plugin prefs injection ----

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
        mWeatherIntegration.setPluginPrefs(prefs);
    }

    // ---- GLESScene lifecycle ----

    @Override
    protected void onCreate() {
        mScene.init(isPreview());
        Context appContext = GLESWallpaper.getAppContext();
        mWeatherIntegration.onCreate(appContext);
        if (mResources != null) {
            initGL();
        }
    }

    @Override
    public void start() {
        mWeatherIntegration.start(isPreview());
    }

    @Override
    public void stop() {
        mWeatherIntegration.stop();
    }

    @Override
    public void release() {
        mWeatherIntegration.release();
        int[] tex = new int[]{
                mTexNight, mTexSunrise, mTexSunset, mTexSky,
                mTexSolarEclipse, mTexSun, mTexAA, mTexDandelion, mTexFirefly,
            mTexFirefly1, mTexFirefly2,
            mTexMoonBase, mTexMoonMask
        };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexNight = 0; mTexSunrise = 0; mTexSunset = 0; mTexSky = 0;
        mTexSolarEclipse = 0; mTexSun = 0; mTexAA = 0;
        mTexDandelion = 0; mTexFirefly = 0; mTexFirefly1 = 0; mTexFirefly2 = 0;
        mTexMoonBase = 0; mTexMoonMask = 0;
        mWeatherRenderer.releaseTextures();
        mStarRenderer.releaseTextures();

        if (mBackgroundProgram != 0) { GLES20.glDeleteProgram(mBackgroundProgram); mBackgroundProgram = 0; }
        if (mSkyProgram != 0) { GLES20.glDeleteProgram(mSkyProgram); mSkyProgram = 0; }
        if (mGrassProgram != 0) { GLES20.glDeleteProgram(mGrassProgram); mGrassProgram = 0; }
        if (mMoonProgram != 0) { GLES20.glDeleteProgram(mMoonProgram); mMoonProgram = 0; }
        if (mSunProgram != 0) { GLES20.glDeleteProgram(mSunProgram); mSunProgram = 0; }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        mLegacyParticleRenderer = new GrassLegacyParticleRenderer(mScene, mSpriteRenderer);
        mBackgroundRenderer.setViewport(width, height);
        mWeatherRenderer.setViewport(width, height);
        mStarRenderer.setViewport(width, height);
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

        GrassWeatherIntegration.FrameUpdate weatherUpdate = mWeatherIntegration.update(timeMs, isPreview());

        if (weatherUpdate.clearSceneWeather) {
            mScene.setWeatherState(null);
        }
        if (weatherUpdate.stateToApply != null) {
            mScene.setWeatherState(weatherUpdate.stateToApply);
        }

        long animNow = SystemClock.uptimeMillis();
        mScene.update(animNow);
        SceneData sd = mScene.getSceneData();

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
            mBackgroundRenderer.drawAccurateBackground(sd);
            drawWeatherBackground(sd);
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
            mBackgroundRenderer.drawBackground(sd);
            drawWeatherBackground(sd);
            eclipseImpact = 0.0f;
            grassBrightness = sd.newB;
            nightDesat = 0.0f;
            if (sd.nightDesaturateGrass) {
                grassBrightness = 1.0f;
                nightDesat = clamp(1.0f - sd.newB, 0.0f, 1.0f);
            }
        }

        mStarRenderer.drawNightStars(sd, mSpriteRenderer, mStarRenderOps);
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
        createSunProgram();
        loadTextures();
        loadMoonTextures();

        GLES20.glViewport(0, 0, mWidth, mHeight);
        }

        private void createBackgroundProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_bg_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_bg_fs.glsl");
        mBackgroundProgram = createProgram(vs, fs);
        mBgPositionHandle = GLES20.glGetAttribLocation(mBackgroundProgram, "aPosition");
        mBgTexHandle = GLES20.glGetAttribLocation(mBackgroundProgram, "aTexCoord");
        mBgMatrixHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uMVPMatrix");
        mBgAlphaHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uAlpha");
        mBgSamplerHandle = GLES20.glGetUniformLocation(mBackgroundProgram, "uSampler");
        mSpriteRenderer.setProgramHandles(mBgPositionHandle, mBgTexHandle, mBgSamplerHandle, mBgAlphaHandle);
        mBackgroundRenderer.setBackgroundProgramHandles(mBgPositionHandle, mBgTexHandle, mBgSamplerHandle, mBgAlphaHandle);
        mWeatherRenderer.setBackgroundMatrixHandle(mBgMatrixHandle);
        mStarRenderer.setBackgroundMatrixHandle(mBgMatrixHandle);
    }

    private void createSkyProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sky_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sky_fs.glsl");
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
        mBackgroundRenderer.setSkyProgramHandles(
            mSkyPositionHandle,
            mSkyTexHandle,
            mSkySamplerNightHandle,
            mSkySamplerSunriseHandle,
            mSkySamplerSunsetHandle,
            mSkySamplerSkyHandle,
            mSkySamplerSolarEclipseHandle,
            mSkyWeightNightHandle,
            mSkyWeightSunriseHandle,
            mSkyWeightSunsetHandle,
            mSkyWeightSkyHandle,
            mSkyWeightSolarEclipseHandle,
            mSkyNightInvertHandle);
    }

    private void createGrassProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_grass_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_grass_fs.glsl");
        mGrassProgram = createProgram(vs, fs);
        mGrassPositionHandle = GLES20.glGetAttribLocation(mGrassProgram, "aPosition");
        mGrassColorHandle = GLES20.glGetAttribLocation(mGrassProgram, "aColor");
        mGrassTexHandle = GLES20.glGetAttribLocation(mGrassProgram, "aTexCoord");
        mGrassMatrixHandle = GLES20.glGetUniformLocation(mGrassProgram, "uMVPMatrix");
        mGrassSamplerHandle = GLES20.glGetUniformLocation(mGrassProgram, "uSampler");
    }

    private void createMoonProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_moon_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_moon_fs.glsl");
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

    private void createSunProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sun_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sun_fs.glsl");
        mSunProgram = createProgram(vs, fs);
        if (mSunProgram != 0) {
            Log.i(TAG, "Procedural sun shader compiled successfully");
        } else {
            Log.w(TAG, "Procedural sun shader compilation failed");
        }
        mSunPositionHandle = GLES20.glGetAttribLocation(mSunProgram, "aPosition");
        mSunTexHandle = GLES20.glGetAttribLocation(mSunProgram, "aTexCoord");
        mSunMatrixHandle = GLES20.glGetUniformLocation(mSunProgram, "uMVPMatrix");
        mSunTimeHandle = GLES20.glGetUniformLocation(mSunProgram, "uTime");
        mSunOpacityHandle = GLES20.glGetUniformLocation(mSunProgram, "uOpacity");
        mSunLineAlphaHandle = GLES20.glGetUniformLocation(mSunProgram, "uLineAlpha");
        mSunResolutionHandle = GLES20.glGetUniformLocation(mSunProgram, "uResolution");
        mSunSunPosHandle = GLES20.glGetUniformLocation(mSunProgram, "uSunPos");
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
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
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
        mWeatherRenderer.setDensity(mDensity);
        ensureSkyFieldsLoaded();
        mTexNight = createSkyFieldTexture(mSkyFieldNight, true);
        mTexSunrise = createSkyFieldTexture(mSkyFieldSunrise, true);
        mTexSunset = createSkyFieldTexture(mSkyFieldSunset, true);
        mTexSky = createSkyFieldTexture(mSkyFieldDay, true);
        mTexSolarEclipse = loadTexture("grass/drawable/solar_eclipse.jpg", false, false);
        mBackgroundRenderer.setSkyTextures(mTexNight, mTexSunrise, mTexSunset, mTexSky, mTexSolarEclipse);
        mTexSun = loadTexture("grass/drawable/sun.png", false, false);
        mTexAA = createAlphaTexture();
        mTexDandelion = loadTexture("grass/drawable/dandelion.png", false, false);
        mTexFirefly = loadTexture("grass/drawable/firefly.png", false, false);
        mTexFirefly1 = loadTexture("grass/drawable/firefly1.png", false, false);
        mTexFirefly2 = loadTexture("grass/drawable/firefly2.png", false, false);
        mWeatherRenderer.loadTextures(this::loadTexture, this::createSolidColorTexture);
        mStarRenderer.loadTextures(this::createSolidColorTexture);
    }

    private void ensureSkyFieldsLoaded() {
        if (mSkyFieldNight != null && mSkyFieldSunrise != null
                && mSkyFieldSunset != null && mSkyFieldDay != null) {
            return;
        }
        String text = AssetLoader.readText(mContext, "grass/data/grass_sky_fields.txt");
        mSkyFieldNight = parseSkyFieldSection(text, "SKY_FIELD_NIGHT");
        mSkyFieldSunrise = parseSkyFieldSection(text, "SKY_FIELD_SUNRISE");
        mSkyFieldSunset = parseSkyFieldSection(text, "SKY_FIELD_SUNSET");
        mSkyFieldDay = parseSkyFieldSection(text, "SKY_FIELD_DAY");
    }

    private int[][] parseSkyFieldSection(String allText, String sectionName) {
        if (allText == null) return null;
        String marker = "[" + sectionName + "]";
        int start = allText.indexOf(marker);
        if (start < 0) return null;
        int bodyStart = start + marker.length();
        int next = allText.indexOf("[", bodyStart);
        String body = (next > bodyStart) ? allText.substring(bodyStart, next) : allText.substring(bodyStart);

        List<int[]> cols = new ArrayList<>();
        int depth = 0;
        int rowStart = -1;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{') {
                depth++;
                if (depth == 2) {
                    rowStart = i + 1;
                }
            } else if (ch == '}') {
                if (depth == 2 && rowStart >= 0) {
                    String row = body.substring(rowStart, i).trim();
                    if (!row.isEmpty() && row.contains("0x")) {
                        String[] parts = row.split(",");
                        int[] values = new int[parts.length];
                        for (int p = 0; p < parts.length; p++) {
                            String token = parts[p].trim();
                            long parsed = Long.decode(token);
                            values[p] = (int) parsed;
                        }
                        cols.add(values);
                    }
                    rowStart = -1;
                }
                depth--;
            }
        }
        if (cols.isEmpty()) return null;
        return cols.toArray(new int[0][]);
    }

    private int createSkyFieldTexture(int[][] fieldColors, boolean repeatS) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) {
            return 0;
        }

        final int cols = fieldColors.length;
        final int rows = fieldColors[0].length;
        final int targetW = 24;
        final int targetH = 64;
        byte[] rgba = new byte[targetW * targetH * 4];

        for (int y = 0; y < targetH; y++) {
            float v = y / (float) (targetH - 1);
            float srcY = v * (rows - 1);
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(srcY)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float ty = srcY - y0;
            for (int x = 0; x < targetW; x++) {
                float u = x / (float) (targetW - 1);
                float srcX = u * (cols - 1);
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(srcX)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float tx = srcX - x0;

                int c00 = fieldColors[x0][y0];
                int c10 = fieldColors[x1][y0];
                int c01 = fieldColors[x0][y1];
                int c11 = fieldColors[x1][y1];

                int r = Math.round(lerp(
                        lerp(Color.red(c00), Color.red(c10), tx),
                        lerp(Color.red(c01), Color.red(c11), tx), ty));
                int g = Math.round(lerp(
                        lerp(Color.green(c00), Color.green(c10), tx),
                        lerp(Color.green(c01), Color.green(c11), tx), ty));
                int b = Math.round(lerp(
                        lerp(Color.blue(c00), Color.blue(c10), tx),
                        lerp(Color.blue(c01), Color.blue(c11), tx), ty));
                int a = Math.round(lerp(
                        lerp(Color.alpha(c00), Color.alpha(c10), tx),
                        lerp(Color.alpha(c01), Color.alpha(c11), tx), ty));

                int idx = (y * targetW + x) * 4;
                rgba[idx] = (byte) r;
                rgba[idx + 1] = (byte) g;
                rgba[idx + 2] = (byte) b;
                rgba[idx + 3] = (byte) a;
            }
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeatS ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, targetW, targetH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private static float lerp(float a, float b, float t) {
        return a * (1.0f - t) + b * t;
    }

    private void loadMoonTextures() {
        mTexMoonBase = loadTexture("grass/drawable/grass_moon.png", false, false);
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
        options.inPremultiplied = false;
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

    private int loadTexture(String assetPath, boolean repeat, boolean mipmap) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
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
                GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
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

        short[] idx = mScene.mRenderDataBuilder.buildGrassIndexArray();
        mGrassIndexBuffer = ByteBuffer.allocateDirect(idx.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        mGrassIndexBuffer.put(idx).position(0);
    }

    // ---- Draw methods ----

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

    private void drawSun(SceneData sd) {
        if (sd.proceduralSunEnabled && mSunProgram != 0) {
            drawProceduralSun(sd);
            if (sd.hasSolarEclipseOcclusion && mTexMoonMask != 0) {
                drawSolarEclipseOcclusion(sd);
            }
            return;
        }
        if (mTexSun == 0) return;
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        mSpriteRenderer.drawSprite(mTexSun, sd.sunX, sd.sunY, sd.sunSize, sd.sunAlpha, false, 0.0f);
        if (sd.hasSolarEclipseOcclusion && mTexMoonMask != 0) {
            drawSolarEclipseOcclusion(sd);
        }
    }

    private void drawProceduralSun(SceneData sd) {
        useProgram(mSunProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        GLES20.glUniformMatrix4fv(mSunMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES20.glUniform2f(mSunResolutionHandle, (float) mWidth, (float) mHeight);
        GLES20.glUniform2f(mSunSunPosHandle, sd.sunX, sd.sunY);
        GLES20.glUniform1f(mSunTimeHandle, sd.animNowMs * 0.001f);
        GLES20.glUniform1f(mSunOpacityHandle, sd.sunAlpha);
        GLES20.glUniform1f(mSunLineAlphaHandle, 320.0f);

        // draw full-screen quad via moon buffer (reused)
        if (mMoonBuffer == null) {
            mMoonBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        float[] qv = mQuadVerts;
        qv[0] = 0.0f;  qv[1] = 0.0f;    qv[2] = 0.0f; qv[3] = 0.0f;
        qv[4] = 0.0f;  qv[5] = (float) mHeight; qv[6] = 0.0f; qv[7] = 1.0f;
        qv[8] = (float) mWidth; qv[9] = (float) mHeight; qv[10] = 1.0f; qv[11] = 1.0f;
        qv[12] = (float) mWidth; qv[13] = 0.0f;  qv[14] = 1.0f; qv[15] = 0.0f;
        mMoonBuffer.clear();
        mMoonBuffer.put(qv).position(0);

        GLES20.glEnableVertexAttribArray(mSunPositionHandle);
        GLES20.glVertexAttribPointer(mSunPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mMoonBuffer);
        mMoonBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mSunTexHandle);
        GLES20.glVertexAttribPointer(mSunTexHandle, 2, GLES20.GL_FLOAT, false, 16, mMoonBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mSunPositionHandle);
        GLES20.glDisableVertexAttribArray(mSunTexHandle);
    }

    private void drawSolarEclipseOcclusion(SceneData sd) {
        SolarEclipse eclipse = sd.solarEclipseAtSun;
        float sunX = sd.eclipseSunX, sunY = sd.eclipseSunY, sunSize = sd.eclipseSunSize;
        float sunAlpha = sd.eclipseSunAlpha;
        float moonX = sd.eclipseMoonX, moonY = sd.eclipseMoonY;

        float sunDiscSize = sunSize * SUN_PHOTOSPHERE_SCALE;
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

    private void drawMoon(SceneData sd) {
        if (!sd.useAccurateSun || !sd.moonEnabled || !sd.moonVisible) return;
        if (mMoonProgram == 0 || mTexMoonBase == 0 || mTexMoonMask == 0) return;

        useProgram(mMoonProgram);
        MoonEclipse eclipse = sd.moonEclipse;
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

    private void drawBlades(SceneData sd, float brightness, float xOffset, float nightDesat) {
        if (!sd.grassEnabled || sd.blades == null) return;
        if (mGrassVertexBuffer == null || mGrassIndexBuffer == null) return;

        float[] sharedVerts = mScene.mRenderDataBuilder.buildGrassVertexArray(sd);
        int sharedVertCount = mScene.mRenderDataBuilder.getGrassVertexCount();
        int floatCount = sharedVertCount * 8;
        if (sharedVerts == null || floatCount <= 0 || floatCount > sharedVerts.length) {
            return;
        }

        if (mScene.mRenderDataBuilder.wasGrassVertexArrayUpdated()) {
            mGrassVertexBuffer.clear();
            mGrassVertexBuffer.put(sharedVerts, 0, floatCount);
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

    // ---- Sprite drawing ----

    private void drawSprites(SceneData sd) {
        if (sd.legacyParticles) {
            drawLegacyParticles(sd);
            return;
        }
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        if (sd.dandelionVisibility > 0.001f && sd.dandelionEnabled
                && mTexDandelion != 0 && sd.dandelions != null) {
            for (Dandelion d : sd.dandelions) {
                float sway = (float) Math.sin(d.swayPhase + sd.animNowMs * 0.001f * d.swaySpeed) * 6.0f;
                mSpriteRenderer.drawSprite(mTexDandelion, d.x, d.y + sway, d.size,
                        0.9f * sd.dandelionVisibility, true, d.rotationDeg);
            }
        }

        if (sd.fireflyVisibility > 0.001f && sd.fireflyEnabled
                && mTexFirefly != 0 && sd.fireflies != null) {
            float time = sd.animNowMs * 0.001f;
            for (Firefly f : sd.fireflies) {
                float flicker = 0.5f + 0.5f * (float) Math.sin(f.phase + time * f.flickerSpeed);
                float alpha = (0.2f + 0.8f * flicker) * sd.fireflyVisibility;
                float size = f.size * (0.8f + 0.4f * flicker);
                mSpriteRenderer.drawSprite(mTexFirefly, f.x, f.y, size, alpha, false, 0.0f);
            }
        }
    }

    private void drawWeatherOverlays(SceneData sd, boolean frontPass) {
        mWeatherRenderer.drawWeatherOverlays(sd, frontPass,
                mWeatherIntegration.isWeatherEnabled(), mWeatherRenderOps, mSpriteRenderer);
    }

    private void drawWeatherTone(SceneData sd) {
        // moved to GrassWeatherRenderer
    }

    private void drawWeatherBackground(SceneData sd) {
        mWeatherRenderer.drawWeatherBackground(sd,
                mWeatherIntegration.isWeatherEnabled(), mWeatherRenderOps, mSpriteRenderer);
    }

    private void drawLegacyParticles(SceneData sd) {
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        clearLegacyBatchCounts();

        long animNowMs = sd.legacyNow;
        int legacyType = sd.legacyType;

        drawLegacyParticleSet(sd.legacyNormal, legacyType, false, animNowMs);
        drawLegacyParticleSet(sd.legacyExtras, legacyType, true, animNowMs);

        flushLegacyBatches();
    }

    private void drawLegacyParticleSet(LegacyParticle[] particles, int legacyType,
            boolean isExtras, long animNowMs) {
        if (particles == null) {
            return;
        }

        for (int i = 0; i < particles.length; i++) {
            LegacyParticle p = particles[i];
            if (p == null || !p.active) continue;
            long delta = animNowMs - p.startTime;
            if (delta < 0L) continue;
            boolean outOfBounds = isLegacyParticleOutOfBounds(p, legacyType);
            if (outOfBounds) {
                LegacyParticle np = mScene.createLegacyParticle(legacyType);
                np.active = true;
                particles[i] = np;
                p = np;
                delta = animNowMs - p.startTime;
                if (delta < 0L) continue;
            }
            if ((p.stayEndTime - animNowMs) <= 0L) {
                if (legacyType == LEGACY_TYPE_DANDELION) {
                    mScene.flyLegacyDandelion(p, false);
                } else {
                    mScene.flyLegacyFirefly(p, false);
                }
            }
            p.startTime = animNowMs;
            drawLegacyParticle(p, legacyType, i + (isExtras ? 100 : 0), isExtras, animNowMs);
        }
    }

    private boolean isLegacyParticleOutOfBounds(LegacyParticle p, int legacyType) {
        if (legacyType == LEGACY_TYPE_DANDELION) {
            return p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0 || p.originY > mHeight;
        } else {
            return p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0;
        }
    }

    private void drawLegacyParticle(LegacyParticle p, int legacyType, int index,
            boolean isExtras, long animNowMs) {
        if (legacyType == LEGACY_TYPE_FIREFLY) {
            long interval = p.flareEndTime - p.silentEndTime;
            if (animNowMs >= p.flareEndTime && interval > 0L) {
                p.silentEndTime = animNowMs
                        + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE)
                        * LEGACY_MAX_INTERVAL);
            } else if (animNowMs >= p.silentEndTime && interval < 0L) {
                p.flareEndTime = animNowMs + LEGACY_MAX_FLARE;
            }
            int tex = (animNowMs < p.flareEndTime) ? mTexFirefly2 : mTexFirefly1;
            float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + index * 1234) * 0.002);
            float alpha = 0.2f + 0.8f * flicker;
            float size = (isExtras ? 48.0f : 72.0f) * (0.8f + 0.4f * flicker);
            int alphaBin = alphaBinForLegacy(alpha);
            int group = (tex == mTexFirefly2)
                    ? (LEGACY_BATCH_GROUP_FIREFLY2_START + alphaBin)
                    : (LEGACY_BATCH_GROUP_FIREFLY1_START + alphaBin);
            appendLegacySpriteQuad(group, p.originX, p.originY, size, false, 0.0f);
        } else {
            float size = isExtras ? 64.0f : 96.0f;
            appendLegacySpriteQuad(LEGACY_BATCH_GROUP_DANDELION, p.originX, p.originY, size, true, p.angle);
        }
    }

    private void clearLegacyBatchCounts() {
        for (int i = 0; i < mLegacyBatchFloatCounts.length; i++) {
            mLegacyBatchFloatCounts[i] = 0;
        }
    }

    private void flushLegacyBatches() {
        if (mTexDandelion != 0 && mLegacyBatchFloatCounts[LEGACY_BATCH_GROUP_DANDELION] > 0) {
            mSpriteRenderer.drawBatch(
                    mTexDandelion,
                    mLegacyBatchVertices[LEGACY_BATCH_GROUP_DANDELION],
                    mLegacyBatchFloatCounts[LEGACY_BATCH_GROUP_DANDELION],
                    0.9f);
        }

        if (mTexFirefly1 != 0) {
            for (int bin = 0; bin < LEGACY_FIREFLY_ALPHA_BIN_COUNT; bin++) {
                int group = LEGACY_BATCH_GROUP_FIREFLY1_START + bin;
                int floatCount = mLegacyBatchFloatCounts[group];
                if (floatCount <= 0) {
                    continue;
                }
                mSpriteRenderer.drawBatch(mTexFirefly1, mLegacyBatchVertices[group], floatCount, alphaForLegacyBin(bin));
            }
        }

        if (mTexFirefly2 != 0) {
            for (int bin = 0; bin < LEGACY_FIREFLY_ALPHA_BIN_COUNT; bin++) {
                int group = LEGACY_BATCH_GROUP_FIREFLY2_START + bin;
                int floatCount = mLegacyBatchFloatCounts[group];
                if (floatCount <= 0) {
                    continue;
                }
                mSpriteRenderer.drawBatch(mTexFirefly2, mLegacyBatchVertices[group], floatCount, alphaForLegacyBin(bin));
            }
        }
    }

    private int alphaBinForLegacy(float alpha) {
        int idx = (int) (alpha * (LEGACY_FIREFLY_ALPHA_BIN_COUNT - 1) + 0.5f);
        if (idx < 0) {
            return 0;
        }
        if (idx >= LEGACY_FIREFLY_ALPHA_BIN_COUNT) {
            return LEGACY_FIREFLY_ALPHA_BIN_COUNT - 1;
        }
        return idx;
    }

    private float alphaForLegacyBin(int alphaBin) {
        if (LEGACY_FIREFLY_ALPHA_BIN_COUNT <= 1) {
            return 1.0f;
        }
        return alphaBin / (float) (LEGACY_FIREFLY_ALPHA_BIN_COUNT - 1);
    }

    private void appendLegacySpriteQuad(int group, float cx, float cy, float size, boolean flipV, float rotationDeg) {
        ensureLegacyGroupCapacity(group, LEGACY_FLOATS_PER_QUAD);
        float[] out = mLegacyBatchVertices[group];
        int cursor = mLegacyBatchFloatCounts[group];

        float half = size * 0.5f;
        float rad = (float) Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x0 = (-half * cos) - (-half * sin) + cx;
        float y0 = (-half * sin) + (-half * cos) + cy;
        float x1 = (-half * cos) - (half * sin) + cx;
        float y1 = (-half * sin) + (half * cos) + cy;
        float x2 = (half * cos) - (half * sin) + cx;
        float y2 = (half * sin) + (half * cos) + cy;
        float x3 = (half * cos) - (-half * sin) + cx;
        float y3 = (half * sin) + (-half * cos) + cy;

        float v0 = flipV ? 0.0f : 1.0f;
        float v1 = flipV ? 1.0f : 0.0f;

        cursor = putLegacyBatchVertex(out, cursor, x0, y0, 0.0f, v0);
        cursor = putLegacyBatchVertex(out, cursor, x1, y1, 0.0f, v1);
        cursor = putLegacyBatchVertex(out, cursor, x2, y2, 1.0f, v1);
        cursor = putLegacyBatchVertex(out, cursor, x0, y0, 0.0f, v0);
        cursor = putLegacyBatchVertex(out, cursor, x2, y2, 1.0f, v1);
        cursor = putLegacyBatchVertex(out, cursor, x3, y3, 1.0f, v0);

        mLegacyBatchFloatCounts[group] = cursor;
    }

    private int putLegacyBatchVertex(float[] out, int cursor, float x, float y, float u, float v) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        return cursor;
    }

    private void ensureLegacyGroupCapacity(int group, int appendFloats) {
        int required = mLegacyBatchFloatCounts[group] + appendFloats;
        float[] current = mLegacyBatchVertices[group];
        if (current != null && current.length >= required) {
            return;
        }

        int newSize = current == null ? 2048 : current.length;
        while (newSize < required) {
            newSize *= 2;
        }

        float[] expanded = new float[newSize];
        if (current != null && mLegacyBatchFloatCounts[group] > 0) {
            System.arraycopy(current, 0, expanded, 0, mLegacyBatchFloatCounts[group]);
        }
        mLegacyBatchVertices[group] = expanded;
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
