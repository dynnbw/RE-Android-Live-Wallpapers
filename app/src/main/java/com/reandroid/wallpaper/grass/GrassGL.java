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
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

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

import static com.reandroid.wallpaper.grass.GrassConstants.SUN_PHOTOSPHERE_SCALE;
import com.reandroid.utils.MathUtils;

/**
 * Grass 壁纸渲染层（OpenGL ES 2.0），所有状态委托 GrassScene 管理。
 */
public class GrassGL extends GLESScene {

    private static final String TAG = "GrassGL";

    // ---- 场景逻辑层（非 GL）----
    private final Context mContext;
    private final GrassScene mScene;
    private final GrassSpriteRenderer mSpriteRenderer = new GrassSpriteRenderer();
    private final GrassBackgroundRenderer mBackgroundRenderer = new GrassBackgroundRenderer();
    private final GrassWeatherRenderer mWeatherRenderer = new GrassWeatherRenderer();
    private final GrassStarRenderer mStarRenderer = new GrassStarRenderer();

    /*
     * 天气渲染器与星空渲染器要的是同一件事（绑背景 program + 常规 alpha 混合），
     * 原先各写了一个内容相同的匿名实现，合并成一个共用。
     */
    private final RenderOps mBackgroundRenderOps = new RenderOps() {
        @Override
        public void useBackgroundProgram() {
            useProgram(mBackgroundProgram);
        }

        @Override
        public void setAlphaBlend() {
            setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
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
    private int mBgTintHandle;
    private int mBgVertexAlphaHandle;
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
    private int mMoonRotationHandle;
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
    private int mSunSunRampHandle;
    private int mSunAnnulusRampHandle;
    private int mSunRaysHandle;
    private int mSunCircleAlphaHandle;
    private int mSunCircleOffsetHandle;
    private int mSunCircleOffsetRatioHandle;
    private int mSunAnnulusAlphaHandle;
    private int mSunRayAlphaHandle;
    private int mSunQualityHandle;
    private int mSun22OpenHandle;
    private int mSunCloseCircleHandle;
    private int mSunSunPosOffsetYHandle;

    // Textures
    private int mTexNight;
    private int mTexSunrise;
    private int mTexSunset;
    private int mTexSky;
    private int mTexSolarEclipse;
    private int mTexSun;
    private int mTexSunRamp;
    private int mTexSunAnnulusRamp;
    private int mTexSunRays;
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
        mBackgroundRenderer.setViewport(width, height);
        mWeatherRenderer.setViewport(width, height);
        mStarRenderer.setViewport(width, height);
    }

    // ---- Tap interaction（移植自原版 MTK grass）----

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 桌面与预览统一走触摸事件（系统触摸必达，fall/forest 同模式）。
        // 不再依赖 tap 命令，避免与触摸双触发。
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScene.addTap(event.getX(), event.getY());
        }
    }

    // ---- Plugin prefs injection ----

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
        mWeatherIntegration.setPluginPrefs(prefs);
        mWeatherRenderer.setPluginPrefs(prefs);
        mStarRenderer.setPluginPrefs(prefs);
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
            mTexMoonBase, mTexMoonMask,
            mTexSunRamp, mTexSunAnnulusRamp, mTexSunRays
        };
        GLES30.glDeleteTextures(tex.length, tex, 0);
        mTexNight = 0; mTexSunrise = 0; mTexSunset = 0; mTexSky = 0;
        mTexSolarEclipse = 0; mTexSun = 0; mTexAA = 0;
        mTexSunRamp = 0; mTexSunAnnulusRamp = 0; mTexSunRays = 0;
        mTexDandelion = 0; mTexFirefly = 0; mTexFirefly1 = 0; mTexFirefly2 = 0;
        mTexMoonBase = 0; mTexMoonMask = 0;
        mWeatherRenderer.releaseTextures();
        mStarRenderer.releaseTextures();

        if (mBackgroundProgram != 0) { GLES30.glDeleteProgram(mBackgroundProgram); mBackgroundProgram = 0; }
        if (mSkyProgram != 0) { GLES30.glDeleteProgram(mSkyProgram); mSkyProgram = 0; }
        if (mGrassProgram != 0) { GLES30.glDeleteProgram(mGrassProgram); mGrassProgram = 0; }
        if (mMoonProgram != 0) { GLES30.glDeleteProgram(mMoonProgram); mMoonProgram = 0; }
        if (mSunProgram != 0) { GLES30.glDeleteProgram(mSunProgram); mSunProgram = 0; }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
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

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        float eclipseImpact, grassBrightness, nightDesat;

        useProgram(mSkyProgram);
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mSkyMatrixHandle, 1, false, sd.projectionMatrix, 0);
        mBackgroundRenderer.drawAccurateBackground(sd);
        drawWeatherBackground(sd);
        if (sd.sunEnabled && sd.hasSunData) drawSun(sd);
        eclipseImpact = MathUtils.clamp(sd.solarEclipseWeight, 0.0f, 1.0f);
        grassBrightness = sd.newB;
        nightDesat = 0.0f;
        if (sd.nightDesaturateGrass) {
            grassBrightness = MathUtils.mix(1.0f, 0.72f, eclipseImpact);
            float baseNightDesat = sd.accurateWeights[0];
            nightDesat = MathUtils.clamp(baseNightDesat + eclipseImpact * 0.85f, 0.0f, 1.0f);
        } else {
            grassBrightness *= MathUtils.mix(1.0f, 0.62f, eclipseImpact);
        }

        mStarRenderer.drawNightStars(sd, mSpriteRenderer, mBackgroundRenderOps);
        drawMoon(sd);
        drawWeatherOverlays(sd, false);

        useProgram(mGrassProgram);
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mGrassMatrixHandle, 1, false, sd.projectionMatrix, 0);

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

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);

        createBackgroundProgram();
        createSkyProgram();
        createGrassProgram();
        createMoonProgram();
        createSunProgram();
        loadTextures();
        loadMoonTextures();

        GLES30.glViewport(0, 0, mWidth, mHeight);
        }

        private void createBackgroundProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_bg_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_bg_fs.glsl");
        mBackgroundProgram = createProgram(vs, fs);
        mBgPositionHandle = GLES30.glGetAttribLocation(mBackgroundProgram, "aPosition");
        mBgTexHandle = GLES30.glGetAttribLocation(mBackgroundProgram, "aTexCoord");
        mBgMatrixHandle = GLES30.glGetUniformLocation(mBackgroundProgram, "uMVPMatrix");
        mBgAlphaHandle = GLES30.glGetUniformLocation(mBackgroundProgram, "uAlpha");
        mBgSamplerHandle = GLES30.glGetUniformLocation(mBackgroundProgram, "uSampler");
        /*
         * uTint 是 program 级状态，这里不设初值 —— createProgram 留在父类，链接后当前绑定的
         * 是哪个程序并不明确，而 useProgram 又带 mCurrentProgram 缓存，一次性设值不可靠。
         * 改为每个绘制方自己保证：GrassSpriteRenderer 每次绘制都上传，
         * GrassBackgroundRenderer.setAlpha 每次绘制前设回白色。两者覆盖了全部绘制路径。
         */
        mBgTintHandle = GLES30.glGetUniformLocation(mBackgroundProgram, "uTint");
        // 精灵的逐顶点 alpha（见 grass_bg_vs.glsl）；粒子/星空/天气精灵与 VK 共用同一套顶点
        mBgVertexAlphaHandle = GLES30.glGetAttribLocation(mBackgroundProgram, "aAlpha");
        /*
         * 把这个属性的**通用值**设成 1。
         *
         * 着色器里 float 属性取的是通用属性的 x 分量，而通用值默认是 (0,0,0,1) ——
         * 读出来是 0。于是任何"没启用该数组、也没写 a"的绘制都会整块变透明。
         * 背景天空就踩过这个坑（改完四层天空全没了）。设成 1 之后，忘了写 a 的代价从
         * "看不见"变成"看得见"，这类错误不会再静默发生。
         */
        if (mBgVertexAlphaHandle >= 0) {
            GLES30.glVertexAttrib1f(mBgVertexAlphaHandle, 1.0f);
        }
        mSpriteRenderer.setProgramHandles(mBgPositionHandle, mBgTexHandle, mBgSamplerHandle, mBgAlphaHandle);
        mSpriteRenderer.setTintHandle(mBgTintHandle);
        mSpriteRenderer.setVertexAlphaHandle(mBgVertexAlphaHandle);
        mBackgroundRenderer.setBackgroundProgramHandles(mBgPositionHandle, mBgTexHandle, mBgSamplerHandle, mBgAlphaHandle);
        mBackgroundRenderer.setBackgroundTintHandle(mBgTintHandle);
        mBackgroundRenderer.setBackgroundVertexAlphaHandle(mBgVertexAlphaHandle);
        mWeatherRenderer.setBackgroundMatrixHandle(mBgMatrixHandle);
        mStarRenderer.setBackgroundMatrixHandle(mBgMatrixHandle);
        mStarRenderer.setRenderDataBuilder(mScene.mRenderDataBuilder);
    }

    private void createSkyProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sky_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_sky_fs.glsl");
        mSkyProgram = createProgram(vs, fs);
        mSkyPositionHandle = GLES30.glGetAttribLocation(mSkyProgram, "aPosition");
        mSkyTexHandle = GLES30.glGetAttribLocation(mSkyProgram, "aTexCoord");
        mSkyMatrixHandle = GLES30.glGetUniformLocation(mSkyProgram, "uMVPMatrix");
        mSkySamplerNightHandle = GLES30.glGetUniformLocation(mSkyProgram, "uTexNight");
        mSkySamplerSunriseHandle = GLES30.glGetUniformLocation(mSkyProgram, "uTexSunrise");
        mSkySamplerSunsetHandle = GLES30.glGetUniformLocation(mSkyProgram, "uTexSunset");
        mSkySamplerSkyHandle = GLES30.glGetUniformLocation(mSkyProgram, "uTexSky");
        mSkySamplerSolarEclipseHandle = GLES30.glGetUniformLocation(mSkyProgram, "uTexSolarEclipse");
        mSkyWeightNightHandle = GLES30.glGetUniformLocation(mSkyProgram, "uWeightNight");
        mSkyWeightSunriseHandle = GLES30.glGetUniformLocation(mSkyProgram, "uWeightSunrise");
        mSkyWeightSunsetHandle = GLES30.glGetUniformLocation(mSkyProgram, "uWeightSunset");
        mSkyWeightSkyHandle = GLES30.glGetUniformLocation(mSkyProgram, "uWeightSky");
        mSkyWeightSolarEclipseHandle = GLES30.glGetUniformLocation(mSkyProgram, "uWeightSolarEclipse");
        mSkyNightInvertHandle = GLES30.glGetUniformLocation(mSkyProgram, "uNightInvert");
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
        mGrassPositionHandle = GLES30.glGetAttribLocation(mGrassProgram, "aPosition");
        mGrassColorHandle = GLES30.glGetAttribLocation(mGrassProgram, "aColor");
        mGrassTexHandle = GLES30.glGetAttribLocation(mGrassProgram, "aTexCoord");
        mGrassMatrixHandle = GLES30.glGetUniformLocation(mGrassProgram, "uMVPMatrix");
        mGrassSamplerHandle = GLES30.glGetUniformLocation(mGrassProgram, "uSampler");
    }

    private void createMoonProgram() {
        String vs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_moon_vs.glsl");
        String fs = AssetLoader.readText(mContext, "grass/shaders/GLES/grass_moon_fs.glsl");
        mMoonProgram = createProgram(vs, fs);
        mMoonPositionHandle = GLES30.glGetAttribLocation(mMoonProgram, "aPosition");
        mMoonTexHandle = GLES30.glGetAttribLocation(mMoonProgram, "aTexCoord");
        mMoonMatrixHandle = GLES30.glGetUniformLocation(mMoonProgram, "uMVPMatrix");
        mMoonSamplerBaseHandle = GLES30.glGetUniformLocation(mMoonProgram, "uMoonBase");
        mMoonSamplerMaskHandle = GLES30.glGetUniformLocation(mMoonProgram, "uMoonMask");
        mMoonPhaseHandle = GLES30.glGetUniformLocation(mMoonProgram, "uPhaseAngle");
        mMoonRotationHandle = GLES30.glGetUniformLocation(mMoonProgram, "uRotation");
        mMoonBrightnessHandle = GLES30.glGetUniformLocation(mMoonProgram, "uBrightness");
        mMoonAlphaHandle = GLES30.glGetUniformLocation(mMoonProgram, "uMoonAlpha");
        mMoonIsDaytimeHandle = GLES30.glGetUniformLocation(mMoonProgram, "uIsDaytime");
        mMoonContrastHandle = GLES30.glGetUniformLocation(mMoonProgram, "uContrast");
        mMoonSaturationHandle = GLES30.glGetUniformLocation(mMoonProgram, "uSaturation");
        mMoonBlueTintHandle = GLES30.glGetUniformLocation(mMoonProgram, "uBlueTint");
        mMoonEclipseTypeHandle = GLES30.glGetUniformLocation(mMoonProgram, "uEclipseType");
        mMoonEclipseFractionHandle = GLES30.glGetUniformLocation(mMoonProgram, "uEclipseFraction");
        mMoonEclipsePhaseHandle = GLES30.glGetUniformLocation(mMoonProgram, "uEclipsePhase");
        mMoonShadowOffsetHandle = GLES30.glGetUniformLocation(mMoonProgram, "uShadowOffset");
        mMoonShadowColorHandle = GLES30.glGetUniformLocation(mMoonProgram, "uShadowColor");
        mMoonPenumbraColorHandle = GLES30.glGetUniformLocation(mMoonProgram, "uPenumbraColor");
        mMoonSolarOcclusionHandle = GLES30.glGetUniformLocation(mMoonProgram, "uSolarOcclusion");
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
        mSunPositionHandle = GLES30.glGetAttribLocation(mSunProgram, "aPosition");
        mSunTexHandle = GLES30.glGetAttribLocation(mSunProgram, "aTexCoord");
        mSunMatrixHandle = GLES30.glGetUniformLocation(mSunProgram, "uMVPMatrix");
        mSunTimeHandle = GLES30.glGetUniformLocation(mSunProgram, "uTime");
        mSunOpacityHandle = GLES30.glGetUniformLocation(mSunProgram, "uOpacity");
        mSunLineAlphaHandle = GLES30.glGetUniformLocation(mSunProgram, "uLineAlpha");
        mSunResolutionHandle = GLES30.glGetUniformLocation(mSunProgram, "uResolution");
        mSunSunPosHandle = GLES30.glGetUniformLocation(mSunProgram, "uSunPos");
        mSunSunRampHandle = GLES30.glGetUniformLocation(mSunProgram, "uSunRamp");
        mSunAnnulusRampHandle = GLES30.glGetUniformLocation(mSunProgram, "uAnnulusRamp");
        mSunRaysHandle = GLES30.glGetUniformLocation(mSunProgram, "uRays");
        mSunCircleAlphaHandle = GLES30.glGetUniformLocation(mSunProgram, "uCircleAlpha");
        mSunCircleOffsetHandle = GLES30.glGetUniformLocation(mSunProgram, "uCircleOffset");
        mSunCircleOffsetRatioHandle = GLES30.glGetUniformLocation(mSunProgram, "uCircleOffsetRatio");
        mSunAnnulusAlphaHandle = GLES30.glGetUniformLocation(mSunProgram, "uAnnulusAlpha");
        mSunRayAlphaHandle = GLES30.glGetUniformLocation(mSunProgram, "uRayAlpha");
        mSunQualityHandle = GLES30.glGetUniformLocation(mSunProgram, "uQuality");
        mSun22OpenHandle = GLES30.glGetUniformLocation(mSunProgram, "u22Open");
        mSunCloseCircleHandle = GLES30.glGetUniformLocation(mSunProgram, "uCloseCircle");
        mSunSunPosOffsetYHandle = GLES30.glGetUniformLocation(mSunProgram, "uSunPosOffsetY");
    }



    // ---- Texture loading ----

    private void loadTextures() {
        mDensity = mResources.getDisplayMetrics().density;
        mWeatherRenderer.setDensity(mDensity);
        ensureSkyFieldsLoaded();
        mTexNight = GrassTextureUtils.createSkyFieldTexture(mSkyFieldNight, true);
        mTexSunrise = GrassTextureUtils.createSkyFieldTexture(mSkyFieldSunrise, true);
        mTexSunset = GrassTextureUtils.createSkyFieldTexture(mSkyFieldSunset, true);
        mTexSky = GrassTextureUtils.createSkyFieldTexture(mSkyFieldDay, true);
        mTexSolarEclipse = loadTexture("grass/drawable/solar_eclipse.jpg", false, false);
        mBackgroundRenderer.setSkyTextures(mTexNight, mTexSunrise, mTexSunset, mTexSky, mTexSolarEclipse);
        mTexSun = loadTexture("grass/drawable/sun.png", false, false);
        // 太阳的三张 LUT / 图，由 tools/weather_tex/decode_lzstc.py 从参考实现的
        // .lzstc 解出。sun_ramp / sun_annulus_ramp 是 540x1，着色器按
        // texture(tex, vec2(radius, 0.5)) 采样，所以 v 必须落在唯一那一行上 ——
        // repeat 必须为 false（CLAMP_TO_EDGE），mipmap 必须为 false。
        mTexSunRamp = loadTexture("grass/drawable/sun_ramp.png", false, false);
        mTexSunAnnulusRamp = loadTexture("grass/drawable/sun_annulus_ramp.png", false, false);
        mTexSunRays = loadTexture("grass/drawable/sun_rays.png", false, false);
        mTexAA = GrassTextureUtils.createAlphaTexture();
        mTexDandelion = loadTexture("grass/drawable/dandelion.png", false, false);
        mTexFirefly = loadTexture("grass/drawable/firefly.png", false, false);
        mTexFirefly1 = loadTexture("grass/drawable/firefly1.png", false, false);
        mTexFirefly2 = loadTexture("grass/drawable/firefly2.png", false, false);
        mWeatherRenderer.loadTextures(this::loadTexture, GrassTextureUtils::createSolidColorTexture);
        mStarRenderer.loadTextures(GrassTextureUtils::createSolidColorTexture);
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

    private void loadMoonTextures() {
        mTexMoonBase = loadTexture("grass/drawable/grass_moon.png", false, false);
        mTexMoonMask = GrassTextureUtils.createMoonMaskTexture(512);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexMoonBase);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexMoonMask);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    }

    private int loadTexture(int resId, boolean repeat, boolean mipmap) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resId, options);
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
                repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
                repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        bitmap.recycle();
        return tex[0];
    }

    private int loadTexture(String assetPath, boolean repeat, boolean mipmap) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
                repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
                repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        bitmap.recycle();
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
            GLES30.glUseProgram(program);
            mCurrentProgram = program;
        }
    }

    private void setBlendFunc(int src, int dst) {
        if (mBlendSrc != src || mBlendDst != dst) {
            GLES30.glBlendFunc(src, dst);
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
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);
        mSpriteRenderer.drawSprite(mTexSun, sd.sunX, sd.sunY, sd.sunSize, sd.sunAlpha, false, 0.0f);
        if (sd.hasSolarEclipseOcclusion && mTexMoonMask != 0) {
            drawSolarEclipseOcclusion(sd);
        }
    }

    private void drawProceduralSun(SceneData sd) {
        useProgram(mSunProgram);
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);

        GLES30.glUniformMatrix4fv(mSunMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES30.glUniform2f(mSunResolutionHandle, (float) mWidth, (float) mHeight);
        GLES30.glUniform2f(mSunSunPosHandle, sd.sunX, sd.sunY);
        GLES30.glUniform1f(mSunTimeHandle, sd.animNowMs * 0.001f);
        GLES30.glUniform1f(mSunOpacityHandle, sd.sunAlpha);
        GLES30.glUniform1f(mSunLineAlphaHandle, 320.0f);
        GLES30.glUniform1f(mSunSunPosOffsetYHandle, 0.0f);
        GLES30.glUniform1f(mSunCircleAlphaHandle, GrassConstants.SUN_CIRCLE_ALPHA);
        GLES30.glUniform1f(mSunCircleOffsetHandle, GrassConstants.SUN_CIRCLE_OFFSET);
        GLES30.glUniform1f(mSunCircleOffsetRatioHandle, GrassConstants.SUN_CIRCLE_OFFSET_RATIO);
        GLES30.glUniform1f(mSunAnnulusAlphaHandle, GrassConstants.SUN_ANNULUS_ALPHA);
        GLES30.glUniform1f(mSunRayAlphaHandle, GrassConstants.SUN_RAY_ALPHA);
        GLES30.glUniform1f(mSunQualityHandle, GrassConstants.SUN_QUALITY);
        GLES30.glUniform1f(mSun22OpenHandle, GrassConstants.SUN_22_OPEN);
        GLES30.glUniform1i(mSunCloseCircleHandle, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexSunRamp);
        GLES30.glUniform1i(mSunSunRampHandle, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexSunAnnulusRamp);
        GLES30.glUniform1i(mSunAnnulusRampHandle, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexSunRays);
        GLES30.glUniform1i(mSunRaysHandle, 2);

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

        GLES30.glEnableVertexAttribArray(mSunPositionHandle);
        GLES30.glVertexAttribPointer(mSunPositionHandle, 2, GLES30.GL_FLOAT, false, 16, mMoonBuffer);
        mMoonBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mSunTexHandle);
        GLES30.glVertexAttribPointer(mSunTexHandle, 2, GLES30.GL_FLOAT, false, 16, mMoonBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);

        GLES30.glDisableVertexAttribArray(mSunPositionHandle);
        GLES30.glDisableVertexAttribArray(mSunTexHandle);
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

        float overlapFactor = 1.0f - MathUtils.clamp(distance / (sunRadius + moonRadius), 0.0f, 1.0f);
        float maskAlpha = MathUtils.clamp(sunAlpha * (0.45f + 0.55f * eclipse.fraction) * overlapFactor, 0.0f, 1.0f);
        if (maskAlpha <= 0.001f) return;

        useProgram(mMoonProgram);
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mMoonMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES30.glUniform1f(mMoonPhaseHandle, 0.0f);
        GLES30.glUniform1f(mMoonBrightnessHandle, 1.0f);
        GLES30.glUniform1f(mMoonAlphaHandle, maskAlpha);
        GLES30.glUniform1i(mMoonIsDaytimeHandle, 1);
        GLES30.glUniform1f(mMoonContrastHandle, 1.0f);
        GLES30.glUniform1f(mMoonSaturationHandle, 1.0f);
        GLES30.glUniform1f(mMoonBlueTintHandle, 0.0f);
        GLES30.glUniform1i(mMoonEclipseTypeHandle, 0);
        GLES30.glUniform1f(mMoonEclipseFractionHandle, 0.0f);
        GLES30.glUniform1f(mMoonEclipsePhaseHandle, 0.0f);
        GLES30.glUniform2f(mMoonShadowOffsetHandle, 0.0f, 0.0f);
        GLES30.glUniform3f(mMoonShadowColorHandle, 0.0f, 0.0f, 0.0f);
        GLES30.glUniform3f(mMoonPenumbraColorHandle, 0.0f, 0.0f, 0.0f);
        GLES30.glUniform1f(mMoonSolarOcclusionHandle, 1.0f);
        drawMoonSprite(moonX, moonY, moonSize);
        GLES30.glUniform1f(mMoonSolarOcclusionHandle, 0.0f);
    }

    private void drawMoon(SceneData sd) {
        if (!sd.moonEnabled || !sd.moonVisible) return;
        if (mMoonProgram == 0 || mTexMoonBase == 0 || mTexMoonMask == 0) return;

        useProgram(mMoonProgram);
        MoonEclipse eclipse = sd.moonEclipse;
        if (sd.moonIsDaytime) {
            setBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR);
        } else {
            setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }
        GLES30.glUniformMatrix4fv(mMoonMatrixHandle, 1, false, sd.projectionMatrix, 0);
        GLES30.glUniform1f(mMoonPhaseHandle, sd.moonPhaseAngle);
        GLES30.glUniform1f(mMoonRotationHandle, sd.moonRotationDeg);
        GLES30.glUniform1f(mMoonBrightnessHandle, sd.moonBrightness);
        GLES30.glUniform1f(mMoonAlphaHandle, sd.moonAlpha);
        GLES30.glUniform1i(mMoonIsDaytimeHandle, sd.moonIsDaytime ? 1 : 0);
        GLES30.glUniform1f(mMoonContrastHandle, sd.moonContrast);
        GLES30.glUniform1f(mMoonSaturationHandle, sd.moonSaturation);
        GLES30.glUniform1f(mMoonBlueTintHandle, sd.moonBlueTint);
        GLES30.glUniform1i(mMoonEclipseTypeHandle, eclipse != null ? eclipse.type : 0);
        GLES30.glUniform1f(mMoonEclipseFractionHandle, eclipse != null ? eclipse.fraction : 0.0f);
        GLES30.glUniform1f(mMoonEclipsePhaseHandle, eclipse != null ? eclipse.phase : 0.0f);
        GLES30.glUniform2f(mMoonShadowOffsetHandle,
                eclipse != null ? eclipse.shadowOffsetX : 0.0f,
                eclipse != null ? eclipse.shadowOffsetY : 0.0f);
        GLES30.glUniform3f(mMoonShadowColorHandle, 0.6f, 0.2f, 0.1f);
        GLES30.glUniform3f(mMoonPenumbraColorHandle, 0.2f, 0.2f, 0.2f);
        GLES30.glUniform1f(mMoonSolarOcclusionHandle, 0.0f);
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
        GLES30.glEnableVertexAttribArray(mMoonPositionHandle);
        GLES30.glVertexAttribPointer(mMoonPositionHandle, 2, GLES30.GL_FLOAT, false, 16, mMoonBuffer);
        mMoonBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mMoonTexHandle);
        GLES30.glVertexAttribPointer(mMoonTexHandle, 2, GLES30.GL_FLOAT, false, 16, mMoonBuffer);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexMoonBase);
        GLES30.glUniform1i(mMoonSamplerBaseHandle, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexMoonMask);
        GLES30.glUniform1i(mMoonSamplerMaskHandle, 1);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
        GLES30.glDisableVertexAttribArray(mMoonPositionHandle);
        GLES30.glDisableVertexAttribArray(mMoonTexHandle);
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

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexAA);
        GLES30.glUniform1i(mGrassSamplerHandle, 0);

        GLES30.glEnableVertexAttribArray(mGrassPositionHandle);
        GLES30.glVertexAttribPointer(mGrassPositionHandle, 2, GLES30.GL_FLOAT, false, 32, mGrassVertexBuffer);
        mGrassVertexBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mGrassColorHandle);
        GLES30.glVertexAttribPointer(mGrassColorHandle, 4, GLES30.GL_FLOAT, false, 32, mGrassVertexBuffer);
        mGrassVertexBuffer.position(6);
        GLES30.glEnableVertexAttribArray(mGrassTexHandle);
        GLES30.glVertexAttribPointer(mGrassTexHandle, 2, GLES30.GL_FLOAT, false, 32, mGrassVertexBuffer);

        mGrassIndexBuffer.position(0);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mScene.mIndexCount, GLES30.GL_UNSIGNED_SHORT, mGrassIndexBuffer);

        GLES30.glDisableVertexAttribArray(mGrassPositionHandle);
        GLES30.glDisableVertexAttribArray(mGrassColorHandle);
        GLES30.glDisableVertexAttribArray(mGrassTexHandle);
    }

    // ---- Sprite drawing ----

    private void drawSprites(SceneData sd) {
        useProgram(mBackgroundProgram);
        setBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mBgMatrixHandle, 1, false, sd.projectionMatrix, 0);

        /*
         * 几何全部由 GrassRenderDataBuilder 产出，与 Vulkan 共用同一份 —— 这里只管
         * 挑贴图和发起绘制。builder 内部已经处理了"传统开关优先，否则现代粒子"，
         * 所以不需要在这边分支。
         *
         * 注意 uniform alpha 一律传 1：逐顶点 alpha 由 builder 烘进顶点里，
         * 再乘一次就会双重衰减。
         */
        GrassRenderDataBuilder b = mScene.mRenderDataBuilder;
        final int stride = GrassRenderDataBuilder.FLOATS_PER_SPRITE_VERTEX;

        float[] dandelion = b.buildDandelionSpriteVertices(sd);
        int dandelionFloats = b.getDandelionVertexCount() * stride;
        if (mTexDandelion != 0 && dandelionFloats > 0) {
            mSpriteRenderer.drawBatch(mTexDandelion, dandelion, dandelionFloats, 1.0f);
        }

        // 传统萤火虫分两张贴图（本体 / 闪光），现代萤火虫只有一张
        float[] firefly = b.buildFireflySpriteVertices(sd);
        int fireflyFloats = b.getFireflyVertexCount() * stride;
        int fireflyTexture = sd.legacyFireflyEnabled ? mTexFirefly1 : mTexFirefly;
        if (fireflyTexture != 0 && fireflyFloats > 0) {
            mSpriteRenderer.drawBatch(fireflyTexture, firefly, fireflyFloats, 1.0f);
        }

        float[] flare = b.buildFireflyFlareSpriteVertices(sd);
        int flareFloats = b.getFireflyFlareVertexCount() * stride;
        if (mTexFirefly2 != 0 && flareFloats > 0) {
            mSpriteRenderer.drawBatch(mTexFirefly2, flare, flareFloats, 1.0f);
        }
    }

    private void drawWeatherOverlays(SceneData sd, boolean frontPass) {
        mWeatherRenderer.drawWeatherOverlays(sd, frontPass,
                mWeatherIntegration.isWeatherEnabled(), mBackgroundRenderOps, mSpriteRenderer);
    }

    private void drawWeatherBackground(SceneData sd) {
        mWeatherRenderer.drawWeatherBackground(sd,
                mWeatherIntegration.isWeatherEnabled(), mBackgroundRenderOps, mSpriteRenderer);
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

    }
