package com.reandroid.wallpaper.windmill;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.GLTextureUtils;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.weather.WeatherManager;
import com.reandroid.weather.WeatherState;
import com.reandroid.gles.RawResourceLoader;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;

public class WindmillGL extends GLESScene {
    private static final String TAG = "WindmillGL";
    private static final int MAX_FRAME = 2000;
    private static final float FOV = 45.0f;

    private final float[] mProjectionMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mSamplerHandle;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mRectOneToTwoBuffer;
    private FloatBuffer mRectVertexBuffer;
    private FloatBuffer mTexBuffer;

    private boolean mGlReady = false;

    private float mOffset = 1.25f;
    private float mLandscape = 1.0f;
    private float mFillScaleY = 1.0f;

    private int mFrameCnt = 0;
    private float mFrameAccumulator = 0.0f;
    private long mLastTimeMs = 0L;

    private WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    private boolean mIsNight = false;
    private WeatherCondition mLastCondition = WeatherCondition.D1_CLEAR;
    private boolean mLastNight = false;

    private WeatherManager mWeatherManager;
    private SharedPreferences mPrefs;

    private boolean mPreviewActive = false;
    private int mPreviewIndex = 0;
    private long mPreviewNextMs = 0L;

    private boolean mClearOn = false;
    private boolean mRainOn = false;
    private boolean mSnowOn = false;
    private boolean mThunderOn = false;

    private int mSky01;
    private int mSky02;
    private int mSky03;
    private int mSky04;
    private int mSkyStars;
    private int mCloudA01;
    private int mCloudA02;
    private int mCloudA03;
    private int mCloudB01;
    private int mCloudB02;
    private int mCloudB03;
    private int mSun1;
    private int mSun2;
    private int mSun3;
    private int mSun4;
    private int mStar;
    private int mMeteor;
    private int mMoon;
    private int mRain1;
    private int mRain2;
    private int mRain3;
    private int mRain4;
    private int mFog02;
    private int mIce;
    private int[] mRaindrop1;
    private int[] mRaindrop2;
    private int mWaterdrop;
    private int mFrostC;
    private int mFrostE;
    private int mFrostF;
    private int mSnow1;
    private int mSnow2;
    private int mSnow3;
    private int mSnow4;
    private int mNightcover;
    private int mSkyFlash;
    private int mLightning1;
    private int mLightning2;
    private int mLightning3;
    private int mCloudLightA1;
    private int mCloudLightA2;
    private int mCloudLightA3;
    private int mCloudLightB1;
    private int mCloudLightB2;
    private int mCloudLightB3;
    private int mWindmillWing;
    private int mWindmillWingBlur;
    private int mWindmillCenter1;
    private int mWindmillCenter2;
    private int mWindmillPillar1;
    private int mWindmillPillar2;
    private int mWindmillPillarFlip1;
    private int mWindmillPillarFlip2;
    private int mLand01;
    private int mLand02;
    private int mLand03;
    private int mLand04;
    private int mLand05;
    private int mLand06;
    private int mLand07;
    private int mLand08;
    private int mLand09;
    private int mLawn01;
    private int mLawn02;
    private int mLawn03;
    private int mLawn04;
    private int mLawn05;

    private boolean[] mStarDraw;
    private float[] mStarAlpha;
    private int[] mStarStart;
    private int[] mStarDuration;
    private float[] mSnow1X;
    private float[] mSnow1Y;
    private float[] mSnow1Scale;
    private float[] mSnow2X;
    private float[] mSnow2Y;
    private float[] mSnow2Scale;
    private float[] mSnow3X;
    private float[] mSnow3Y;
    private float[] mSnow3Scale;
    private int[] mThunderStart;
    private int[] mThunderDuration;
    private int[] mThunderNum;
    private float[] mThunderScale;
    private float[] mThunderX;
    private float[] mThunderY;
    private int[] mCloudLightStart;
    private int[] mCloudLightNum;
    private int[] mCloudLightPos;
    private int[] mCloudLightDuration;
    private int[] mRaindrop1Start;
    private float[] mRaindrop1X;
    private float[] mRaindrop1Y;
    private float[] mRaindrop1Scale;
    private int[] mRaindrop2Start;
    private float[] mRaindrop2X;
    private float[] mRaindrop2Y;
    private float[] mRaindrop2Scale;

    private final float[] mStarX = {1.0f, -1.7f, 1.2f, -1.5f, -4.5f, -6.1f, -7.5f};
    private final float[] mStarY = {5.4f, 4.5f, 3.2f, 3.0f, 4.7f, 5.2f, 4.8f};
    private final float[] mStarSize = {0.1f, 0.1f, 0.08f, 0.1f, 0.08f, 0.08f, 0.1f};

    private float mMeteorX = 0.0f;
    private float mMeteorY = 0.0f;
    private float mMeteorScale = 0.0f;
    private float mMeteorAlpha = 0.0f;
    private int mMeteorInitCnt = 0;
    private int mSunInitCnt = 0;

    private float mCloudAX1 = 0.0f;
    private float mCloudAX2 = 0.0f;
    private float mCloudAX3 = 0.0f;
    private float mCloudAX4 = 0.0f;
    private float mCloudBX1 = 0.0f;
    private float mCloudBX2 = 0.0f;
    private float mCloudBX3 = 0.0f;
    private float mCloudBX4 = 0.0f;
    private float mCloudBX5 = 0.0f;
    private float mCloudBX6 = 0.0f;
    private float mCloudBX7 = 0.0f;

    private WindMill[] mWindmills;

    public WindmillGL(int width, int height) {
        super(width, height);
    }

    @Override
    protected void onCreate() {
        initMemory();
        Context appContext = com.reandroid.gles.GLESWallpaper.getAppContext();
        if (appContext != null) {
            mPrefs = PreferenceManager.getDefaultSharedPreferences(appContext);
            mWeatherManager = new WeatherManager(appContext, this::onWeatherUpdated);
        }
    }

    @Override
    public void start() {
        if (!isPreview() && mWeatherManager != null) {
            mWeatherManager.start();
        } else {
            initPreviewCycle();
        }
    }

    @Override
    public void stop() {
        if (mWeatherManager != null) {
            mWeatherManager.stop();
        }
    }

    @Override
    public void release() {
        deleteTextures();
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mGlReady = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mGlReady) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
            updateProjection();
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mOffset = 2.5f * xOffset;
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) {
            initGl();
        }

        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
        }
        long deltaMs = timeMs - mLastTimeMs;
        mLastTimeMs = timeMs;

        float frameDuration = shouldFastAnimate() ? 16.666f : 20.0f;
        mFrameAccumulator += deltaMs / frameDuration;
        int advance = (int) mFrameAccumulator;
        if (advance > 0) {
            mFrameAccumulator -= advance;
            mFrameCnt += advance;
            if (mFrameCnt > MAX_FRAME) {
                mFrameCnt = 0;
            }
        }

        if (isPreview()) {
            updatePreviewCycle(timeMs);
        }

        updateWeatherFlags();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        drawObjects();
    }

    private void initGl() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.windmill_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.windmill_fs);
        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        float[] quadVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.windmill_quad_vertices);
        float[] rectOneToTwoVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.windmill_rect_one_to_two_vertices);
        float[] rectOneToFourVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.windmill_rect_one_to_four_vertices);
        float[] quadTex = RawResourceLoader.readRawFloatArray(mResources, R.raw.windmill_quad_tex);

        mVertexBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertexBuffer.put(quadVertices).position(0);

        mRectOneToTwoBuffer = ByteBuffer.allocateDirect(rectOneToTwoVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mRectOneToTwoBuffer.put(rectOneToTwoVertices).position(0);

        mRectVertexBuffer = ByteBuffer.allocateDirect(rectOneToFourVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mRectVertexBuffer.put(rectOneToFourVertices).position(0);

        mTexBuffer = ByteBuffer.allocateDirect(quadTex.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mTexBuffer.put(quadTex).position(0);

        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        updateProjection();
        loadTextures();
        mGlReady = true;
    }

    private void updateProjection() {
        mLandscape = mWidth < mHeight ? 1.0f : 2.0f;
        float aspect = (float) mWidth / (float) mHeight;
        float refAspect = 9.0f / 16.0f;
        mFillScaleY = aspect < refAspect ? (refAspect / aspect) : 1.0f;
        float fov = FOV;
        if (mWidth < mHeight) {
            if (aspect < refAspect) {
                float scale = aspect / refAspect;
                double fovRad = Math.toRadians(FOV);
                fov = (float) Math.toDegrees(2.0d * Math.atan(Math.tan(fovRad / 2.0d) * scale));
            }
        }
        Matrix.perspectiveM(mProjectionMatrix, 0, fov, aspect, 0.1f, 40.0f);
    }

    private void loadTextures() {
        mSky01 = GLTextureUtils.loadTexture(mResources, R.drawable.sky_01);
        mSky02 = GLTextureUtils.loadTexture(mResources, R.drawable.sky_02);
        mSky03 = GLTextureUtils.loadTexture(mResources, R.drawable.sky_03);
        mSky04 = GLTextureUtils.loadTexture(mResources, R.drawable.sky_04);
        mSkyStars = GLTextureUtils.loadTexture(mResources, R.drawable.d_sky_stars);
        mCloudA01 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_01);
        mCloudA02 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_02);
        mCloudA03 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_03);
        mCloudB01 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_01);
        mCloudB02 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_02);
        mCloudB03 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_03);
        mSun1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_01);
        mSun2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_02);
        mSun3 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_03);
        mSun4 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_04);
        mStar = GLTextureUtils.loadTexture(mResources, R.drawable.d_star);
        mMeteor = GLTextureUtils.loadTexture(mResources, R.drawable.d_meteor);
        mMoon = GLTextureUtils.loadTexture(mResources, R.drawable.d_moon);
        mRain1 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_01);
        mRain2 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_02);
        mRain3 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_03);
        mRain4 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_04);
        mFog02 = GLTextureUtils.loadTexture(mResources, R.drawable.fog_02);
        mIce = GLTextureUtils.loadTexture(mResources, R.drawable.ice);
        mWaterdrop = GLTextureUtils.loadTexture(mResources, R.drawable.c_waterdrop);
        mFrostC = GLTextureUtils.loadTexture(mResources, R.drawable.c_frost);
        mFrostE = GLTextureUtils.loadTexture(mResources, R.drawable.e_frost);
        mFrostF = GLTextureUtils.loadTexture(mResources, R.drawable.f_frost);
        mSnow1 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_01);
        mSnow2 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_02);
        mSnow3 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_03);
        mSnow4 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_04);
        mNightcover = GLTextureUtils.loadTexture(mResources, R.drawable.nightcover_01);
        mSkyFlash = GLTextureUtils.loadTexture(mResources, R.drawable.g_sky_flash);
        mLightning1 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_01);
        mLightning2 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_02);
        mLightning3 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_03);
        mCloudLightA1 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_01);
        mCloudLightA2 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_02);
        mCloudLightA3 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_03);
        mCloudLightB1 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_01);
        mCloudLightB2 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_02);
        mCloudLightB3 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_03);
        mWindmillWing = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_wing);
        mWindmillWingBlur = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_wing_blur2);
        mWindmillCenter1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_center_01);
        mWindmillCenter2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_center_02);
        mWindmillPillar1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_pillar_01);
        mWindmillPillar2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_pillar_02);
        mWindmillPillarFlip1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_pillar_flip_01);
        mWindmillPillarFlip2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_windmill_pillar_flip_blur2_02);
        mLand01 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_01);
        mLand02 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_02);
        mLand03 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_03);
        mLand04 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_04);
        mLand05 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_05);
        mLand06 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_06);
        mLand07 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_07);
        mLand08 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_08);
        mLand09 = GLTextureUtils.loadTexture(mResources, R.drawable.a_land_09);
        mLawn01 = GLTextureUtils.loadTexture(mResources, R.drawable.a_lawn_01);
        mLawn02 = GLTextureUtils.loadTexture(mResources, R.drawable.a_lawn_02);
        mLawn03 = GLTextureUtils.loadTexture(mResources, R.drawable.a_lawn_03);
        mLawn04 = GLTextureUtils.loadTexture(mResources, R.drawable.a_lawn_04);
        mLawn05 = GLTextureUtils.loadTexture(mResources, R.drawable.a_lawn_05);

        mRaindrop1 = new int[25];
        mRaindrop2 = new int[25];
        int[] raindropRes1 = new int[] {
                R.drawable.waterdrop_a_0, R.drawable.waterdrop_a_1, R.drawable.waterdrop_a_2, R.drawable.waterdrop_a_3,
                R.drawable.waterdrop_a_4, R.drawable.waterdrop_a_5, R.drawable.waterdrop_a_6, R.drawable.waterdrop_a_7,
                R.drawable.waterdrop_a_8, R.drawable.waterdrop_a_9, R.drawable.waterdrop_a_10, R.drawable.waterdrop_a_11,
                R.drawable.waterdrop_a_12, R.drawable.waterdrop_a_13, R.drawable.waterdrop_a_14, R.drawable.waterdrop_a_15,
                R.drawable.waterdrop_a_16, R.drawable.waterdrop_a_17, R.drawable.waterdrop_a_18, R.drawable.waterdrop_a_19,
                R.drawable.waterdrop_a_20, R.drawable.waterdrop_a_21, R.drawable.waterdrop_a_22, R.drawable.waterdrop_a_23,
                R.drawable.waterdrop_a_24
        };
        int[] raindropRes2 = new int[] {
                R.drawable.waterdrop_b_0, R.drawable.waterdrop_b_1, R.drawable.waterdrop_b_2, R.drawable.waterdrop_b_3,
                R.drawable.waterdrop_b_4, R.drawable.waterdrop_b_5, R.drawable.waterdrop_b_6, R.drawable.waterdrop_b_7,
                R.drawable.waterdrop_b_8, R.drawable.waterdrop_b_9, R.drawable.waterdrop_b_10, R.drawable.waterdrop_b_11,
                R.drawable.waterdrop_b_12, R.drawable.waterdrop_b_13, R.drawable.waterdrop_b_14, R.drawable.waterdrop_b_15,
                R.drawable.waterdrop_b_16, R.drawable.waterdrop_b_17, R.drawable.waterdrop_b_18, R.drawable.waterdrop_b_19,
                R.drawable.waterdrop_b_20, R.drawable.waterdrop_b_21, R.drawable.waterdrop_b_22, R.drawable.waterdrop_b_23,
                R.drawable.waterdrop_b_24
        };
        for (int i = 0; i < 25; i++) {
            mRaindrop1[i] = GLTextureUtils.loadTexture(mResources, raindropRes1[i]);
            mRaindrop2[i] = GLTextureUtils.loadTexture(mResources, raindropRes2[i]);
        }
    }

    private void deleteTextures() {
        int[] textures = new int[] {
            mSky01, mSky02, mSky03, mSky04, mSkyStars,
            mCloudA01, mCloudA02, mCloudA03, mCloudB01, mCloudB02, mCloudB03,
            mSun1, mSun2, mSun3, mSun4, mStar, mMeteor, mMoon,
            mRain1, mRain2, mRain3, mRain4, mFog02, mIce, mWaterdrop, mFrostC, mFrostE, mFrostF,
            mSnow1, mSnow2, mSnow3, mSnow4, mNightcover, mSkyFlash, mLightning1, mLightning2,
            mLightning3, mCloudLightA1, mCloudLightA2, mCloudLightA3, mCloudLightB1, mCloudLightB2,
            mCloudLightB3, mWindmillWing, mWindmillWingBlur, mWindmillCenter1, mWindmillCenter2,
            mWindmillPillar1, mWindmillPillar2, mWindmillPillarFlip1, mWindmillPillarFlip2,
            mLand01, mLand02, mLand03, mLand04, mLand05, mLand06, mLand07, mLand08, mLand09,
            mLawn01, mLawn02, mLawn03, mLawn04, mLawn05
        };
        GLES20.glDeleteTextures(textures.length, textures, 0);
        if (mRaindrop1 != null) {
            GLES20.glDeleteTextures(mRaindrop1.length, mRaindrop1, 0);
        }
        if (mRaindrop2 != null) {
            GLES20.glDeleteTextures(mRaindrop2.length, mRaindrop2, 0);
        }
    }

    private void drawObjects() {
        if (isPreview()) {
            mOffset = 1.25f;
        }

        int skyTex = selectSkyTexture();
        int land1Tex = selectLand1Texture();
        int land2Tex = selectLand2Texture();
        int lawnTex = selectLawnTexture();

        drawSprite(skyTex, (-1.5f) + ((1.5f - mOffset) * 5.0f), -2.3f, -30.0f,
            2.0f * mLandscape, 2.0f * mFillScaleY, 0.0f, 1.0f);

        if (mIsNight && (mCondition == WeatherCondition.D3_DREARY
                || mCondition == WeatherCondition.D4_FOG
                || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS
                || mCondition == WeatherCondition.D9_SLEET)) {
                drawSprite(mNightcover, (-1.5f) + ((1.5f - mOffset) * 5.0f), -2.3f, -29.9f,
                    2.0f * mLandscape, 2.0f * mFillScaleY, 0.0f, 1.0f);
        }

        if (mCondition == WeatherCondition.D1_CLEAR && mIsNight) {
            drawSprite(mSkyStars, 1.3f + ((1.5f - mOffset) * 5.0f), 7.0f, -29.9f, 1.8f * mLandscape, 0.45f, 0.0f, 1.0f);
        }

        if (mCondition == WeatherCondition.D1_CLEAR || mCondition == WeatherCondition.D8_ICE_COLD) {
            if (!mIsNight) {
                if (mCondition == WeatherCondition.D1_CLEAR) {
                    drawSprite(mSun1, ((1.5f - mOffset) * 5.0f * 0.2f) + 3.0f, 6.0f, -28.0f,
                            1.0f, 1.0f, mFrameCnt * 0.54f, 1.0f);
                    drawSprite(mSun2, ((1.5f - mOffset) * 5.0f * 0.2f) + 3.0f, 6.0f, -28.0f,
                            1.0f, 1.0f, mFrameCnt * 0.36f, 1.0f);
                    drawSprite(mSun3, ((1.5f - mOffset) * 5.0f * 0.2f) + 3.0f, 6.0f, -28.0f,
                            1.0f, 1.0f, mFrameCnt * -0.54f, 1.0f);
                }
            } else {
                drawSprite(mMoon, 3.2f + ((1.5f - mOffset) * 5.0f), 7.0f, -28.5f,
                        0.25f * mLandscape, 0.25f, 0.0f, 1.0f);
                updateStars();
                updateMeteor();
            }
        }

        drawClouds();
        drawWindmills();

        drawSpriteRectOneToFour(land2Tex, (-1.5f) + ((1.5f - (mOffset * 0.5f)) * 5.0f), -5.2f, -24.0f,
                3.6f * mLandscape, 1.8f, 0.0f, 1.0f);

        drawWindmillsByDistance(1);

        drawSpriteRectOneToFour(land1Tex, (1.5f - (mOffset * 1.2f)) * 5.0f, -6.4f, -23.0f,
                3.5f * mLandscape, 3.2f, 0.0f, 1.0f);
        drawWindmillsByDistance(0);

        drawSpriteRectOneToFour(lawnTex, (1.5f - (mOffset * 1.2f)) * 5.0f, -4.3f, -23.0f,
                3.5f * mLandscape, 1.0f, 0.0f, 1.0f);

        drawSunlight();
        drawFogIce();
        drawRain();
        drawSnow();
        drawThunder();
    }

    private int selectSkyTexture() {
        if (mIsNight) {
            return mSky02;
        }
        switch (mCondition) {
            case D1_CLEAR:
            case D8_ICE_COLD:
                return mSky01;
            case D2_CLOUDY:
            case D4_FOG:
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return mSky03;
            case D3_DREARY:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            default:
                return mSky04;
        }
    }

    private int selectLand1Texture() {
        switch (mCondition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return mIsNight ? mLand03 : mLand01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return mIsNight ? mLand08 : mLand06;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return mIsNight ? mLand03 : mLand05;
        }
    }

    private int selectLand2Texture() {
        switch (mCondition) {
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return mIsNight ? mLand09 : mLand07;
            default:
                return mIsNight ? mLand04 : mLand02;
        }
    }

    private int selectLawnTexture() {
        switch (mCondition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return mIsNight ? mLawn02 : mLawn01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return mIsNight ? mLawn05 : mLawn04;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return mIsNight ? mLawn02 : mLawn03;
        }
    }

    private void updateStars() {
        if (mFrameCnt % 200 == 0 || mClearOn) {
            if (mClearOn) {
                for (int i = 0; i < 7; i++) {
                    mStarDraw[i] = true;
                    mStarStart[i] = mFrameCnt % 200;
                    mStarAlpha[i] = 0.0f;
                    mStarDuration[i] = (int) ((Math.random() * 20.0d) + 30.0d);
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    mStarDraw[i] = Math.random() > 0.2d;
                    mStarStart[i] = (int) (Math.random() * 100.0d);
                    mStarAlpha[i] = 0.0f;
                    mStarDuration[i] = (int) ((Math.random() * 20.0d) + 30.0d);
                }
            }
        } else {
            for (int i = 0; i < 7; i++) {
                if (mStarDraw[i] && mFrameCnt % 200 > mStarStart[i]) {
                    if (mFrameCnt % 200 < mStarStart[i] + mStarDuration[i]) {
                        if (mStarAlpha[i] < 1.0f) {
                            mStarAlpha[i] = (float) (mStarAlpha[i] + 0.04d);
                        }
                    } else if (mFrameCnt % 200 < mStarStart[i] + (mStarDuration[i] * 2) && mStarAlpha[i] > 0.0f) {
                        mStarAlpha[i] = (float) (mStarAlpha[i] - 0.04d);
                    }
                    drawSprite(mStar, mStarX[i] + ((1.5f - mOffset) * 5.0f), mStarY[i], -28.0f,
                            mLandscape * mStarSize[i], mStarSize[i], 0.0f, mStarAlpha[i]);
                }
            }
        }
    }

    private void updateMeteor() {
        if (mFrameCnt % 200 == 0 || mClearOn) {
            if (mClearOn) {
                mMeteorX = 9.0f;
                mMeteorY = 10.0f;
                mMeteorScale = 0.4f;
                mMeteorInitCnt = 0;
                mMeteorAlpha = 1.0f;
            } else if (mMeteorInitCnt > 199) {
                mMeteorX = (float) ((Math.random() * 6.0d) + 5.0d);
                mMeteorY = (float) ((Math.random() * 8.0d) + 8.0d);
                mMeteorScale = ((float) (Math.random() * 0.2d)) + 0.2f;
                mMeteorAlpha = 1.0f;
            }
            mClearOn = false;
        } else {
            if (mMeteorInitCnt < 200) {
                mMeteorInitCnt++;
            }
            mMeteorX = (float) (mMeteorX - 0.45d);
            mMeteorY = (float) (mMeteorY - 0.3d);
            mMeteorScale *= 0.98f;
            mMeteorAlpha *= 0.9f;
            drawSprite(mMeteor, mMeteorX + ((1.5f - mOffset) * 5.0f), mMeteorY, -28.0f,
                    mLandscape * mMeteorScale, mMeteorScale, 0.0f, 1.0f);
        }
    }

    private void drawClouds() {
        if ((mCondition == WeatherCondition.D1_CLEAR || mCondition == WeatherCondition.D8_ICE_COLD) && !mIsNight) {
            if (mFrameCnt < 100) {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) - 18.0d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) + 32.0d);
            }
            if (mFrameCnt < 530) {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) - 11.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) + 39.0d);
            }
            if (mFrameCnt < 400) {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) - 9.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) + 41.0d);
            }
            if (mFrameCnt < 850) {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) + 2.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) + 52.0d);
            }
            if (mFrameCnt < 1000) {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 5.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 55.0d);
            }
            if (mFrameCnt < 1080) {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) + 8.0d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) + 58.0d);
            }
            if (mFrameCnt < 1450) {
                mCloudAX4 = (float) (((-0.025d) * mFrameCnt) + 13.0d);
            } else {
                mCloudAX4 = (float) (((-0.025d) * mFrameCnt) + 63.0d);
            }
            int cloudA = mCloudA01;
            int cloudB = mCloudB01;
                float clearAlphaScale = mIsNight ? 1.0f : 0.7f;
                float clearTint = 0.05f;
                float alphaA3 = 0.4f * clearAlphaScale;
                float colorA3 = Math.max(0.0f, alphaA3 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - mOffset) * 5.0f), 4.5f, -27.0f,
                    3.0f * mLandscape, 3.0f, 0.0f, colorA3, colorA3, colorA3, alphaA3);
                float alphaA1 = 0.9f * clearAlphaScale;
                float colorA1 = Math.max(0.0f, alphaA1 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - mOffset) * 5.0f), -3.0f, -27.0f,
                    4.3f * mLandscape, 4.3f, 0.0f, colorA1, colorA1, colorA1, alphaA1);
                float alphaB3 = 0.3f * clearAlphaScale;
                float colorB3 = Math.max(0.0f, alphaB3 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - mOffset) * 5.0f), 3.8f, -26.0f,
                    2.7f * mLandscape, 2.7f, 0.0f, colorB3, colorB3, colorB3, alphaB3);
                float alphaB1 = 0.75f * clearAlphaScale;
                float colorB1 = Math.max(0.0f, alphaB1 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - mOffset) * 5.0f), -3.2f, -26.0f,
                    3.0f * mLandscape, 3.0f, 0.0f, colorB1, colorB1, colorB1, alphaB1);
                float alphaA4 = 0.95f * clearAlphaScale;
                float colorA4 = Math.max(0.0f, alphaA4 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX4 + ((1.5f - mOffset) * 5.0f), 4.0f, -27.0f,
                    4.3f * mLandscape, 4.3f, 0.0f, colorA4, colorA4, colorA4, alphaA4);
                float alphaA2 = 0.8f * clearAlphaScale;
                float colorA2 = Math.max(0.0f, alphaA2 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - mOffset) * 5.0f), 4.0f, -27.0f,
                    2.4f * mLandscape, 2.4f, 0.0f, colorA2, colorA2, colorA2, alphaA2);
                float alphaB2 = 0.9f * clearAlphaScale;
                float colorB2 = Math.max(0.0f, alphaB2 - clearTint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - mOffset) * 5.0f), 3.8f, -26.0f,
                    2.5f * mLandscape, 2.5f, 0.0f, colorB2, colorB2, colorB2, alphaB2);
            return;
        }

        if (mCondition == WeatherCondition.D2_CLOUDY || mCondition == WeatherCondition.D3_DREARY
                || mCondition == WeatherCondition.D4_FOG || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS || mCondition == WeatherCondition.D7_FLURRIES_SNOW
                || mCondition == WeatherCondition.D8_ICE_COLD || mCondition == WeatherCondition.D9_SLEET) {
            if (mFrameCnt < 360) {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) - 14.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) + 26.0d);
            }
            if (mFrameCnt < 1100) {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 7.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 47.0d);
            }
            if (mFrameCnt < 400) {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) - 10.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) + 30.0d);
            }
            if (mFrameCnt < 600) {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) - 3.5d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) + 36.5d);
            }
            if (mFrameCnt < 850) {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) + 2.5d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) + 42.5d);
            }
            if (mFrameCnt < 650) {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) - 4.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) + 36.0d);
            }
            if (mFrameCnt < 800) {
                mCloudBX4 = (float) (((-0.025d) * mFrameCnt) - 5.0d);
            } else {
                mCloudBX4 = (float) (((-0.025d) * mFrameCnt) + 35.0d);
            }
            if (mFrameCnt < 300) {
                mCloudBX5 = (float) (((-0.025d) * mFrameCnt) - 15.0d);
            } else {
                mCloudBX5 = (float) (((-0.025d) * mFrameCnt) + 25.0d);
            }
            if (mFrameCnt < 110) {
                mCloudBX6 = (float) (((-0.025d) * mFrameCnt) - 20.0d);
            } else {
                mCloudBX6 = (float) (((-0.025d) * mFrameCnt) + 20.0d);
            }
            if (mFrameCnt < 1000) {
                mCloudBX7 = (float) (((-0.025d) * mFrameCnt) + 5.0d);
            } else {
                mCloudBX7 = (float) (((-0.025d) * mFrameCnt) + 45.0d);
            }

            boolean useDarkClouds = mCondition == WeatherCondition.D3_DREARY || mCondition == WeatherCondition.D4_FOG;
            int cloudA = useDarkClouds ? mCloudA03 : mCloudA02;
            int cloudB = useDarkClouds ? mCloudB03 : mCloudB02;
                float dayAlphaScale = mIsNight ? 1.0f : 0.8f;
                float tint = useDarkClouds ? 0.1f : 0.0f;
                float alphaA1 = (mIsNight ? 0.25f : 0.9f) * dayAlphaScale;
                float colorA1 = Math.max(0.0f, alphaA1 - tint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - mOffset) * 5.0f), 5.5f, -27.0f,
                    4.0f * mLandscape, 4.4f, 0.0f, colorA1, colorA1, colorA1, alphaA1);
                float alphaA2 = 0.2f * dayAlphaScale;
                float colorA2 = Math.max(0.0f, alphaA2 - tint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - mOffset) * 5.0f), 4.8f, -27.3f,
                    3.6f * mLandscape, 3.6f, 0.0f, colorA2, colorA2, colorA2, alphaA2);
                float alphaA3 = 0.2f * dayAlphaScale;
                float colorA3 = Math.max(0.0f, alphaA3 - tint);
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - mOffset) * 5.0f), 2.0f, -27.5f,
                    2.8f * mLandscape, 2.8f, 0.0f, colorA3, colorA3, colorA3, alphaA3);
                float alphaB3 = (mIsNight ? 0.25f : 0.5f) * dayAlphaScale;
                float colorB3 = Math.max(0.0f, alphaB3 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - mOffset) * 5.0f), 3.2f, -27.4f,
                    2.8f * mLandscape, 3.2f, 0.0f, colorB3, colorB3, colorB3, alphaB3);
                float alphaB1 = (mIsNight ? 0.25f : 0.9f) * dayAlphaScale;
                float colorB1 = Math.max(0.0f, alphaB1 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - mOffset) * 5.0f), 6.2f, -26.9f,
                    4.4f * mLandscape, 5.0f, 0.0f, colorB1, colorB1, colorB1, alphaB1);
                float alphaB2 = (mIsNight ? 0.25f : 0.3f) * dayAlphaScale;
                float colorB2 = Math.max(0.0f, alphaB2 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - mOffset) * 5.0f), 7.2f, -27.1f,
                    3.4f * mLandscape, 3.4f, 0.0f, colorB2, colorB2, colorB2, alphaB2);
                float alphaB4 = (mIsNight ? 0.2f : 0.4f) * dayAlphaScale;
                float colorB4 = Math.max(0.0f, alphaB4 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX4 + ((1.5f - mOffset) * 5.0f), 0.2f, -27.6f,
                    3.2f * mLandscape, 3.2f, 0.0f, colorB4, colorB4, colorB4, alphaB4);
                float alphaB5 = (mIsNight ? 0.25f : 0.4f) * dayAlphaScale;
                float colorB5 = Math.max(0.0f, alphaB5 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX5 + ((1.5f - mOffset) * 5.0f), 0.3f, -27.7f,
                    3.8f * mLandscape, 3.8f, 0.0f, colorB5, colorB5, colorB5, alphaB5);
                float alphaB6 = (mIsNight ? 0.2f : 0.2f) * dayAlphaScale;
                float colorB6 = Math.max(0.0f, alphaB6 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX6 + ((1.5f - mOffset) * 5.0f), -0.2f, -27.8f,
                    4.4f * mLandscape, 4.4f, 0.0f, colorB6, colorB6, colorB6, alphaB6);
                float alphaB7 = (mIsNight ? 0.15f : 0.47f) * dayAlphaScale;
                float colorB7 = Math.max(0.0f, alphaB7 - tint);
                drawSpriteColoredRectOneToTwo(cloudB, 0.0f, 0.0f, -27.6f,
                    8.0f * mLandscape, 8.0f, 0.0f, colorB7, colorB7, colorB7, alphaB7);

            if (mCondition == WeatherCondition.D6_THUNDERSTORMS) {
                updateCloudLights();
            }
        }
    }

    private void updateCloudLights() {
        if (mThunderOn || mFrameCnt % 400 == 0) {
            for (int i = 0; i < 20; i++) {
                mCloudLightStart[i] = (int) (Math.random() * 390.0d);
                mCloudLightDuration[i] = (int) (Math.random() * 20.0d);
                mCloudLightNum[i] = (int) (Math.random() * 9.0d);
                mCloudLightPos[i] = (int) (Math.random() * 3.0d);
            }
            if (mThunderOn) {
                mCloudLightStart[0] = 5;
                mCloudLightDuration[0] = 10;
                mCloudLightNum[0] = 5;
                mCloudLightPos[0] = 2;
                mCloudLightStart[1] = 15;
                mCloudLightDuration[1] = 15;
                mCloudLightNum[1] = 6;
                mCloudLightPos[1] = 1;
                mCloudLightStart[2] = 20;
                mCloudLightDuration[2] = 20;
                mCloudLightNum[2] = 7;
                mCloudLightPos[2] = 3;
            }
        } else {
            for (int i = 0; i < 20; i++) {
                if (mFrameCnt % 400 > mCloudLightStart[i] && mFrameCnt % 400 < mCloudLightStart[i] + mCloudLightDuration[i]) {
                    float cloudLightX = 0.0f;
                    float cloudLightY = 0.0f;
                    float cloudScale = 0.0f;
                    switch (mCloudLightNum[i]) {
                        case 0:
                            cloudLightX = mCloudAX1;
                            cloudLightY = 5.5f;
                            cloudScale = 2.2f;
                            break;
                        case 1:
                            cloudLightX = mCloudAX2;
                            cloudLightY = 6.0f;
                            cloudScale = 1.5f;
                            break;
                        case 2:
                            cloudLightX = mCloudAX3;
                            cloudLightY = 3.5f;
                            cloudScale = 1.2f;
                            break;
                        case 3:
                            cloudLightX = mCloudBX1;
                            cloudLightY = 6.5f;
                            cloudScale = 2.0f;
                            break;
                        case 4:
                            cloudLightX = mCloudBX2;
                            cloudLightY = 8.0f;
                            cloudScale = 1.2f;
                            break;
                        case 5:
                            cloudLightX = mCloudBX3;
                            cloudLightY = 5.8f;
                            cloudScale = 1.0f;
                            break;
                        case 6:
                            cloudLightX = mCloudBX4;
                            cloudLightY = 1.8f;
                            cloudScale = 1.6f;
                            break;
                        case 7:
                            cloudLightX = mCloudBX5;
                            cloudLightY = 0.8f;
                            cloudScale = 2.2f;
                            break;
                        case 8:
                            cloudLightX = mCloudBX6;
                            cloudLightY = 0.5f;
                            cloudScale = 1.2f;
                            break;
                    }
                    float alpha = (mFrameCnt % 400 < mCloudLightStart[i] + (mCloudLightDuration[i] * 0.5d))
                            ? (0.6f + ((float) Math.random()) * 0.4f)
                            : (((float) Math.random()) * 0.4f);
                    int tex;
                    if (mCloudLightNum[i] < 3) {
                        tex = mCloudLightPos[i] == 0 ? mCloudLightA1 : mCloudLightPos[i] == 1 ? mCloudLightA2 : mCloudLightA3;
                    } else {
                        tex = mCloudLightPos[i] == 0 ? mCloudLightB1 : mCloudLightPos[i] == 1 ? mCloudLightB2 : mCloudLightB3;
                    }
                        drawSpriteRectOneToTwo(tex, ((1.5f - mOffset) * 5.0f) + cloudLightX, cloudLightY, -26.0f,
                            mLandscape * cloudScale, cloudScale, 0.0f, alpha);
                }
            }
        }
    }

    private void drawWindmills() {
        drawWindmillsByDistance(2);
    }

    private void drawWindmillsByDistance(int distance) {
        if (mWindmills == null) return;
        for (int i = 0; i < mWindmills.length; i++) {
            WindMill mill = mWindmills[i];
            if (mill != null && mill.distance == distance) {
                mill.fanAngle = (mFrameCnt * -1.8f) + mill.wingOffset;
                mill.draw();
            }
        }
    }

    private void drawSunlight() {
        if (mCondition == WeatherCondition.D1_CLEAR && !mIsNight && mClearOn) {
            float sunlightCnt = (mFrameCnt + 125) % 200;
            float coeff = sunlightCnt < 100.0f ? (2.0f - (sunlightCnt / 100.0f))
                    : (((sunlightCnt - 100.0f) / 100.0f) + 1.0f);
            float sunlightAlpha = (float) Math.sqrt(coeff - 1.0f);
            drawSprite(mSun4, (3.0f - 0.4f) + ((1.5f - mOffset) * 5.0f * 0.15f), 6.0f - 1.0f, -23.5f,
                    mLandscape * coeff * 0.8f, 0.8f * coeff, -1.8f * sunlightCnt, sunlightAlpha);
            mSunInitCnt++;
            if (mSunInitCnt > 800 && sunlightCnt == 100.0f) {
                mClearOn = false;
                mSunInitCnt = 0;
            }
        }
    }

    private void drawFogIce() {
        if (mCondition == WeatherCondition.D4_FOG) {
            float alpha = mIsNight ? 0.4f : 1.0f;
            drawSprite(mFog02, 0.0f, -0.5f, -20.0f, 0.65f * mLandscape, 1.05f, 0.0f, alpha);
        }
        if (mCondition == WeatherCondition.D8_ICE_COLD) {
            drawSprite(mIce, 0.0f, -0.5f, -20.0f, 0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
        }
    }

    private void drawRain() {
        if (mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS
                || mCondition == WeatherCondition.D9_SLEET) {
            int rainTex = (mFrameCnt % 4 == 0) ? mRain1
                    : (mFrameCnt % 4 == 1) ? mRain2
                    : (mFrameCnt % 4 == 2) ? mRain3 : mRain4;
            drawSprite(rainTex, 0.0f, 0.0f, -20.5f, 0.75f * mLandscape, 1.5f, 0.0f, 1.0f);
            if (mCondition == WeatherCondition.D5_RAIN_SHOWERS || mCondition == WeatherCondition.D9_SLEET) {
                drawSprite(mWaterdrop, -0.15f, -0.3f, -20.0f, 0.6f * mLandscape, 1.1f * mFillScaleY, 0.0f, 1.0f);
                if (mRainOn || mFrameCnt % 400 == 0) {
                    for (int i = 1; i < 8; i++) {
                        mRaindrop1Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                        mRaindrop2Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                        mRaindrop1X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                        mRaindrop1Y[i] = (float) (Math.random() * 3.0d);
                        mRaindrop2X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                        mRaindrop2Y[i] = (float) (Math.random() * 3.0d);
                        mRaindrop1Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                        mRaindrop2Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                    }
                    mRaindrop1Start[2] = mFrameCnt + 20;
                    mRaindrop2Start[2] = mFrameCnt + 55;
                    mRaindrop1X[2] = -1.5f;
                    mRaindrop1Y[2] = -1.0f;
                    mRaindrop2X[2] = 3.0f;
                    mRaindrop2Y[2] = -0.5f;
                    mRaindrop1Scale[2] = 1.0f;
                    mRaindrop2Scale[2] = 1.0f;
                    mRainOn = false;
                }
                for (int i = 0; i < 8; i++) {
                    if (mFrameCnt % 400 > mRaindrop1Start[i] && mFrameCnt % 400 < mRaindrop1Start[i] + 100) {
                        int idx = ((mFrameCnt % 400) - mRaindrop1Start[i]) / 4;
                        if (idx >= 0 && idx < mRaindrop1.length) {
                            drawSprite(mRaindrop1[idx], mRaindrop1X[i], mRaindrop1Y[i], -19.5f,
                                    mRaindrop1Scale[i] * 0.11f * mLandscape, mRaindrop1Scale[i] * 0.45f, 0.0f, 1.0f);
                        }
                    }
                    if (mFrameCnt % 400 > mRaindrop2Start[i] && mFrameCnt % 400 < mRaindrop2Start[i] + 100) {
                        int idx = ((mFrameCnt % 400) - mRaindrop2Start[i]) / 4;
                        if (idx >= 0 && idx < mRaindrop2.length) {
                            drawSprite(mRaindrop2[idx], mRaindrop2X[i], mRaindrop2Y[i], -19.5f,
                                    mRaindrop2Scale[i] * 0.11f * mLandscape, mRaindrop2Scale[i] * 0.9f, 0.0f, 1.0f);
                        }
                    }
                }
            }
        }
    }

    private void drawSnow() {
        if (mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET) {
            int frostTex = mCondition == WeatherCondition.D9_SLEET ? mFrostF : mFrostE;
            if (mCondition == WeatherCondition.D9_SLEET) {
                drawSprite(frostTex, -0.1f, -0.5f, -20.3f, 0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
            } else {
                drawSprite(frostTex, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 1.0f);
            }
        }

        if (mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET) {
            if (mSnowOn) {
                for (int i = 0; i < mSnow1X.length; i++) {
                    mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                    mSnow1Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                    int scale1 = ((int) (Math.random() * 100.0d)) % 2;
                    mSnow1Scale[i] = scale1 == 0 ? 1.0f : 0.5f;
                }
                for (int i = 0; i < mSnow2X.length; i++) {
                    mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                    mSnow2Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                    int scale2 = ((int) (Math.random() * 100.0d)) % 3;
                    mSnow2Scale[i] = scale2 == 0 ? 1.0f : scale2 == 1 ? 0.7f : 0.5f;
                }
                for (int i = 0; i < mSnow3X.length; i++) {
                    mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                    mSnow3Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                    int scale3 = ((int) (Math.random() * 100.0d)) % 4;
                    mSnow3Scale[i] = scale3 == 0 ? 1.0f : scale3 == 1 ? 0.5f : scale3 == 2 ? 0.3f : 0.2f;
                }
                mSnowOn = false;
            } else {
                for (int i = 0; i < mSnow1X.length; i++) {
                    if (mSnow1Y[i] < -8.0f) {
                        mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                        mSnow1Y[i] = 9.0f;
                        int scale1 = ((int) (Math.random() * 100.0d)) % 2;
                        mSnow1Scale[i] = scale1 == 0 ? 1.0f : 0.5f;
                    } else {
                        mSnow1Y[i] -= 0.04f;
                    }
                    drawSprite(mSnow1, (mSnow1X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow1Y[i], -20.0f,
                            mSnow1Scale[i] * 0.1f * mLandscape, mSnow1Scale[i] * 0.1f, mFrameCnt * 0.225f,
                            mCondition == WeatherCondition.D9_SLEET ? mSnow1Scale[i] / 2.0f : mSnow1Scale[i]);
                }
                for (int i = 0; i < mSnow2X.length; i++) {
                    if (mSnow2Y[i] < -8.0f) {
                        mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                        mSnow2Y[i] = 9.0f;
                        int scale2 = ((int) (Math.random() * 100.0d)) % 3;
                        mSnow2Scale[i] = scale2 == 0 ? 1.0f : scale2 == 1 ? 0.7f : 0.5f;
                    } else {
                        mSnow2Y[i] -= 0.02f;
                    }
                    drawSprite(mSnow2, (mSnow2X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow2Y[i], -20.0f,
                            mSnow2Scale[i] * 0.02f * mLandscape, mSnow2Scale[i] * 0.02f, 0.0f,
                            mCondition == WeatherCondition.D9_SLEET ? mSnow2Scale[i] / 2.0f : mSnow2Scale[i]);

                    if ((i & 273) == 1) {
                        drawSprite(mSnow4, (mSnow2X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow2Y[i] + 1.0f, -21.0f,
                                0.35f, 0.35f, 0.0f, 0.8f);
                    }
                }
                for (int i = 0; i < mSnow3X.length; i++) {
                    if (mSnow3Y[i] < -8.0f) {
                        mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                        mSnow3Y[i] = 9.0f;
                        int scale3 = ((int) (Math.random() * 100.0d)) % 4;
                        mSnow3Scale[i] = scale3 == 0 ? 1.0f : scale3 == 1 ? 0.5f : scale3 == 2 ? 0.3f : 0.2f;
                    } else {
                        mSnow3Y[i] -= 0.01f;
                    }
                    drawSprite(mSnow3, (mSnow3X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow3Y[i], -20.0f,
                            mSnow3Scale[i] * 0.01f * mLandscape, mSnow3Scale[i] * 0.01f, 0.0f,
                            mCondition == WeatherCondition.D9_SLEET ? mSnow3Scale[i] / 2.0f : mSnow3Scale[i]);
                }
            }
        }
    }

    private void drawThunder() {
        if (mCondition != WeatherCondition.D6_THUNDERSTORMS) return;
        if (mThunderOn || mFrameCnt % 400 == 0) {
            for (int i = 0; i < 40; i++) {
                mThunderStart[i] = (int) (Math.random() * 400.0d);
                mThunderDuration[i] = (int) (Math.random() * 5.0d);
                mThunderNum[i] = ((int) (Math.random() * 100.0d)) % 3;
                mThunderScale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                if (mThunderScale[i] > 0.75d) {
                    mThunderX[i] = (float) ((Math.random() * 16.0d) - 8.0d);
                    mThunderY[i] = (float) ((Math.random() * 3.0d) + 8.0d);
                } else {
                    mThunderX[i] = (float) ((Math.random() * 16.0d) - 8.0d);
                    mThunderY[i] = (float) ((Math.random() * 3.0d) + 3.0d);
                }
            }
            if (mThunderOn) {
                mThunderStart[0] = (mFrameCnt % 400) + 10;
                mThunderDuration[0] = 3;
                mThunderNum[0] = 2;
                mThunderScale[0] = 1.0f;
                mThunderX[0] = 2.0f;
                mThunderY[0] = 8.0f;
                mThunderStart[1] = (mFrameCnt % 400) + 15;
                mThunderDuration[1] = 5;
                mThunderNum[1] = 1;
                mThunderScale[1] = 0.7f;
                mThunderX[1] = -3.0f;
                mThunderY[1] = 8.0f;
                mThunderStart[2] = (mFrameCnt % 400) + 13;
                mThunderDuration[2] = 2;
                mThunderNum[2] = 3;
                mThunderScale[2] = 1.0f;
                mThunderX[2] = 3.0f;
                mThunderY[2] = 9.0f;
                mThunderStart[3] = (mFrameCnt % 400) + 8;
                mThunderDuration[3] = 5;
                mThunderNum[3] = 2;
                mThunderScale[3] = 1.0f;
                mThunderX[3] = 10.0f;
                mThunderY[3] = 10.0f;
                mThunderStart[4] = (mFrameCnt % 400) + 16;
                mThunderDuration[4] = 4;
                mThunderNum[4] = 1;
                mThunderScale[4] = 1.0f;
                mThunderX[4] = -2.0f;
                mThunderY[4] = 7.0f;
                mThunderStart[5] = (mFrameCnt % 400) + 5;
                mThunderDuration[5] = 5;
                mThunderNum[5] = 1;
                mThunderScale[5] = 1.0f;
                mThunderX[5] = -5.0f;
                mThunderY[5] = 8.0f;
            }
            mThunderOn = false;
            return;
        }

        for (int i = 0; i < 40; i++) {
            if (mFrameCnt % 400 > mThunderStart[i] && mFrameCnt % 400 < mThunderStart[i] + 8) {
                float alpha = (mFrameCnt % 400 < mThunderStart[i] + 4)
                        ? (0.8f + ((float) Math.random()) * 0.2f)
                        : (0.2f + ((float) Math.random()) * 0.2f);
                drawSprite(mSkyFlash, (-0.5f) + ((1.5f - mOffset) * 5.0f), 0.0f, -19.0f,
                        1.5f, 1.2f, 0.0f, alpha);
            }
            if (mFrameCnt % 400 > mThunderStart[i] && mFrameCnt % 400 < mThunderStart[i] + mThunderDuration[i]) {
                float alpha;
                if (mFrameCnt % 400 < mThunderStart[i] + (mThunderDuration[i] * 0.5d)) {
                    alpha = mThunderScale[i] > 0.75d
                            ? (0.8f + ((float) Math.random()) * 0.2f)
                            : (0.5f + ((float) Math.random()) * 0.2f);
                } else {
                    alpha = mThunderScale[i] > 0.75d
                            ? (0.3f + ((float) Math.random()) * 0.2f)
                            : (0.1f + ((float) Math.random()) * 0.2f);
                }
                int tex = mThunderNum[i] == 0 ? mLightning1 : mThunderNum[i] == 1 ? mLightning2 : mLightning3;
                drawSprite(tex, mThunderX[i] + ((1.5f - mOffset) * 5.0f), mThunderY[i], -26.0f,
                        mThunderScale[i] * mLandscape, mThunderScale[i], 0.0f, alpha);
            }
        }
    }

    private void drawSprite(int texture, float x, float y, float z, float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColored(texture, x, y, z, scaleX, scaleY, rotation, alpha, alpha, alpha, alpha);
    }

    private void drawSpriteRectOneToTwo(int texture, float x, float y, float z, float scaleX, float scaleY,
            float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                1.0f, 1.0f, 1.0f, alpha, mRectOneToTwoBuffer);
    }

        private void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z, float scaleX, float scaleY,
            float rotation, float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
            r, g, b, alpha, mRectOneToTwoBuffer);
        }

    private void drawSpriteRectOneToFour(int texture, float x, float y, float z, float scaleX, float scaleY,
            float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                1.0f, 1.0f, 1.0f, alpha, mRectVertexBuffer);
    }

    private void drawSpriteColored(int texture, float x, float y, float z, float scaleX, float scaleY,
            float rotation, float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                r, g, b, alpha, mVertexBuffer);
    }

    private void drawSpriteColoredWithBuffer(int texture, float x, float y, float z, float scaleX, float scaleY,
            float rotation, float r, float g, float b, float alpha, FloatBuffer vertexBuffer) {
        if (texture == 0) return;
        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glUniform4f(mColorHandle, r, g, b, alpha);

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, x, y, z);
        if (rotation != 0.0f) {
            Matrix.rotateM(mModelMatrix, 0, rotation, 0.0f, 0.0f, 1.0f);
        }
        Matrix.scaleM(mModelMatrix, 0, scaleX, scaleY, 1.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);

        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        mTexBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mTexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void updateWeatherFlags() {
        if (mCondition != mLastCondition || mIsNight != mLastNight) {
            if (mCondition == WeatherCondition.D1_CLEAR) {
                mClearOn = true;
            }
            if (mCondition == WeatherCondition.D5_RAIN_SHOWERS || mCondition == WeatherCondition.D9_SLEET) {
                mRainOn = true;
            }
            if (mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET) {
                mSnowOn = true;
            }
            if (mCondition == WeatherCondition.D6_THUNDERSTORMS) {
                mThunderOn = true;
            }
            mLastCondition = mCondition;
            mLastNight = mIsNight;
        }
    }

    private boolean shouldFastAnimate() {
        return mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET;
    }

    private void onWeatherUpdated(WeatherState state) {
        if (state == null) return;
        applyWeatherState(state);
    }

    private void applyWeatherState(WeatherState state) {
        mCondition = state.condition;
        mIsNight = state.isNight;
    }

    private void initPreviewCycle() {
        mPreviewActive = true;
        mPreviewIndex = 0;
        mPreviewNextMs = 0L;
        mIsNight = computePreviewIsNight();
        applyWeatherState(new WeatherState(WeatherCondition.D1_CLEAR, mIsNight, 0.0f, 0.0f, 0L, 0L, 0L));
    }

    private void updatePreviewCycle(long timeMs) {
        if (!mPreviewActive) {
            return;
        }
        if (mPreviewNextMs == 0L) {
            mPreviewNextMs = timeMs + 3000L;
            return;
        }
        if (timeMs < mPreviewNextMs) {
            return;
        }
        WeatherCondition[] order = {
                WeatherCondition.D1_CLEAR,
                WeatherCondition.D2_CLOUDY,
                WeatherCondition.D3_DREARY,
                WeatherCondition.D4_FOG,
                WeatherCondition.D5_RAIN_SHOWERS,
                WeatherCondition.D6_THUNDERSTORMS,
                WeatherCondition.D7_FLURRIES_SNOW,
                WeatherCondition.D8_ICE_COLD,
                WeatherCondition.D9_SLEET
        };
        if (mPreviewIndex >= order.length - 1) {
            mPreviewIndex = 0;
            mPreviewActive = false;
            applyWeatherState(new WeatherState(order[0], mIsNight, 0.0f, 0.0f, 0L, 0L, 0L));
        } else {
            mPreviewIndex++;
            applyWeatherState(new WeatherState(order[mPreviewIndex], mIsNight, 0.0f, 0.0f, 0L, 0L, 0L));
            mPreviewNextMs = timeMs + 3000L;
        }
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

    private void initMemory() {
        mWindmills = new WindMill[13];
        float[] windmillPosX = {-6.5f, -3.5f, -0.8f, 8.5f, 10.4f, -7.9f, -4.4f, -0.2f, 11.5f, 12.0f, -11.5f, -6.0f, -3.0f};
        float[] windmillPosY = {-2.8f, -1.3f, 2.2f, 0.3f, -1.1f, -2.7f, -2.75f, -2.75f, -2.5f, -2.8f, -3.5f, -3.3f, -3.2f};
        float[] windmillPosZ = {-23.0f, -23.0f, -23.0f, -23.0f, -23.0f, -24.05f, -24.05f, -24.05f, -23.95f, -23.95f, -25.0f, -25.0f, -25.0f};
        float[] windmillScaleX = {0.2f, 0.35f, 0.75f, 0.5f, 0.3f, 0.15f, 0.12f, 0.12f, 0.15f, 0.09f, 0.08f, 0.08f, 0.08f};
        float[] windmillScaleY = {0.2f, 0.35f, 0.75f, 0.5f, 0.3f, 0.15f, 0.12f, 0.12f, 0.15f, 0.09f, 0.08f, 0.08f, 0.08f};
        int[] windmillDistance = {0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 2, 2, 2};
        boolean[] windmillType = {true, true, true, true, true, false, false, false, false, false, false, false, false};
        boolean[] windmillFlip = {false, false, false, true, true, false, false, false, true, true, false, false, false};
        float[] windmillPillarOffsetX = {-0.05f, -0.1f, -0.15f, 0.1f, 0.05f, -0.05f, -0.05f, -0.05f, 0.05f, 0.05f, -0.05f, -0.05f, -0.05f};
        float[] windmillPillarOffsetY = {-1.55f, -2.7f, -5.9f, -3.9f, -2.32f, -1.2f, -0.9f, -0.9f, -1.1f, -0.7f, -0.6f, -0.6f, -0.6f};
        float[] windmillWingOffset = {0.0f, 20.0f, 40.0f, 60.0f, 80.0f, 20.0f, 40.0f, 60.0f, 80.0f, 100.0f, 40.0f, 60.0f, 80.0f};
        float[] windmillAlpha = {0.8f, 0.9f, 1.0f, 0.9f, 0.8f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.5f, 0.5f, 0.5f};

        for (int i = 0; i < mWindmills.length; i++) {
            WindMill mill = new WindMill();
            mill.center = new DrawingAttribute(windmillPosX[i], windmillPosY[i], windmillPosZ[i] - 0.1f,
                    windmillScaleX[i] * 0.04f, windmillScaleY[i] * 0.04f);
            mill.pillar = new DrawingAttribute(windmillPosX[i] + windmillPillarOffsetX[i],
                    windmillPosY[i] + windmillPillarOffsetY[i], windmillPosZ[i] + 0.1f,
                    windmillScaleX[i] * 0.08f, windmillScaleY[i]);
            mill.wing = new DrawingAttribute(windmillPosX[i], windmillPosY[i], windmillPosZ[i],
                    windmillScaleX[i], windmillScaleY[i]);
            mill.distance = windmillDistance[i];
            mill.isTypeA = windmillType[i];
            mill.alpha = windmillAlpha[i];
            mill.flip = windmillFlip[i];
            mill.wingOffset = windmillWingOffset[i];
            mWindmills[i] = mill;
        }

        mStarDraw = new boolean[7];
        mStarAlpha = new float[7];
        mStarStart = new int[7];
        mStarDuration = new int[7];
        mSnow1X = new float[5];
        mSnow1Y = new float[5];
        mSnow1Scale = new float[5];
        mSnow2X = new float[100];
        mSnow2Y = new float[100];
        mSnow2Scale = new float[100];
        mSnow3X = new float[200];
        mSnow3Y = new float[200];
        mSnow3Scale = new float[200];
        mThunderStart = new int[40];
        mThunderDuration = new int[40];
        mThunderNum = new int[40];
        mThunderScale = new float[40];
        mThunderX = new float[40];
        mThunderY = new float[40];
        mCloudLightStart = new int[20];
        mCloudLightNum = new int[20];
        mCloudLightPos = new int[20];
        mCloudLightDuration = new int[20];
        mRaindrop1Start = new int[8];
        mRaindrop1X = new float[8];
        mRaindrop1Y = new float[8];
        mRaindrop1Scale = new float[8];
        mRaindrop2Start = new int[8];
        mRaindrop2X = new float[8];
        mRaindrop2Y = new float[8];
        mRaindrop2Scale = new float[8];
    }

    private class DrawingAttribute {
        float x;
        float y;
        float z;
        float scaleX;
        float scaleY;

        DrawingAttribute(float x, float y, float z, float scaleX, float scaleY) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        float calcX(float weight) {
            return (x - 1.5f) + ((1.5f - (mOffset * weight)) * 5.0f);
        }
    }

    private class WindMill {
        DrawingAttribute pillar;
        DrawingAttribute center;
        DrawingAttribute wing;
        float fanAngle;
        float alpha;
        boolean isTypeA;
        boolean flip;
        int distance;
        float wingOffset;

        void draw() {
            float tone = mIsNight ? 0.0f : 1.0f;
            float weight = distance == 0 ? 1.2f : distance == 1 ? 0.5f : 0.2f;

            if (isTypeA) {
                int tex = flip ? mWindmillPillarFlip1 : mWindmillPillar1;
                drawSpriteColored(tex, pillar.calcX(weight), pillar.y, pillar.z,
                        pillar.scaleX * mLandscape, pillar.scaleY, 0.0f, tone, tone, tone, alpha);
            } else {
                int tex = flip ? mWindmillPillarFlip2 : mWindmillPillar2;
                drawSpriteColored(tex, pillar.calcX(weight), pillar.y, pillar.z,
                        pillar.scaleX * mLandscape, pillar.scaleY, 0.0f, tone, tone, tone, alpha);
            }

            int wingTex = distance == 0 ? mWindmillWing : mWindmillWingBlur;
            drawSpriteColored(wingTex, wing.calcX(weight), wing.y, wing.z,
                    wing.scaleX * mLandscape, wing.scaleY, fanAngle, tone, tone, tone, alpha);

            int centerTex = isTypeA ? mWindmillCenter1 : mWindmillCenter2;
            drawSpriteColored(centerTex, center.calcX(weight), center.y, center.z,
                    center.scaleX * mLandscape, center.scaleY, 0.0f, tone, tone, tone, alpha);
        }
    }

    private static int createProgram(String vertex, String fragment) {
        int vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vShader);
        GLES20.glAttachShader(program, fShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

}
