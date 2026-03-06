package com.reandroid.wallpaper.ocean;

import android.opengl.GLES20;
import android.opengl.Matrix;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.GLTextureUtils;
import com.reandroid.wallpaper.weather.WeatherCondition;
import com.reandroid.wallpaper.weather.WeatherManager;
import com.reandroid.wallpaper.weather.WeatherState;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;

public class OceanWeatherGL extends GLESScene {
    private static final int MAX_FRAME = 1600;
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
    private FloatBuffer mTexBuffer;
    private FloatBuffer mRectOneToTwoBuffer;
    private FloatBuffer mRectOneToFourBuffer;

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

    private int mSkyA;
    private int mSkyB;
    private int mSkyC;
    private int mSkyD;
    private int mSkyG;
    private int mSkyStars;
    private int mCloudA01;
    private int mCloudA02;
    private int mCloudA03;
    private int mCloudB01;
    private int mCloudB02;
    private int mCloudB03;
    private int mCloudLightA1;
    private int mCloudLightA2;
    private int mCloudLightA3;
    private int mCloudLightB1;
    private int mCloudLightB2;
    private int mCloudLightB3;
    private int mSun1;
    private int mSun2;
    private int mSun3;
    private int mSun4;
    private int mStar;
    private int mMeteor;
    private int mMoon;
    private int mWatercover1;
    private int mWatercover2;
    private int mWatercover3;
    private int mWatercover4;
    private int mNightcover;
    private int mCapCover;
    private int mFog01;
    private int mFog02;
    private int mIce;
    private int mRain1;
    private int mRain2;
    private int mRain3;
    private int mRain4;
    private int mWaterdrop;
    private int mCloudcover;
    private int mFrostC;
    private int mFrostE;
    private int mFrostF;
    private int mSnow1;
    private int mSnow2;
    private int mSnow3;
    private int mSnow4;
    private int mSkyFlash;
    private int mLightning1;
    private int mLightning2;
    private int mLightning3;
    private int mWaveBack;
    private int[] mWave;
    private int[] mRaindrop1;
    private int[] mRaindrop2;

    private boolean[] mStarDraw;
    private float[] mStarAlpha;
    private int[] mStarStart;
    private int[] mStarDuration;

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

    private static final float[] STAR_X = {1.0f, -1.7f, 1.2f, -1.5f, -4.5f, -6.1f, -7.5f};
    private static final float[] STAR_Y = {5.4f, 4.5f, 3.2f, 3.0f, 4.7f, 5.2f, 4.8f};
    private static final float[] STAR_SIZE = {0.1f, 0.1f, 0.08f, 0.1f, 0.08f, 0.08f, 0.1f};

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

    private final int mSnowCount1 = 10;
    private final int mSnowCount2 = 150;
    private final int mSnowCount3 = 400;

    private float[] mSnow1X;
    private float[] mSnow1Y;
    private float[] mSnow1Scale;
    private float[] mSnow2X;
    private float[] mSnow2Y;
    private float[] mSnow2Scale;
    private float[] mSnow3X;
    private float[] mSnow3Y;
    private float[] mSnow3Scale;

    public OceanWeatherGL(int width, int height) {
        super(width, height);
    }

    @Override
    protected void onCreate() {
        initMemory();
        Context appContext = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
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

        float frameDuration = shouldFastAnimate() ? 16.666f : 40.0f;
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

    @Override
    public void release() {
        if (!mGlReady) {
            return;
        }
        int[] textures = {
                mSkyA, mSkyB, mSkyC, mSkyD, mSkyG, mSkyStars, mCloudA01, mCloudA02, mCloudA03,
                mCloudB01, mCloudB02, mCloudB03, mCloudLightA1, mCloudLightA2, mCloudLightA3,
                mCloudLightB1, mCloudLightB2, mCloudLightB3, mSun1, mSun2, mSun3, mSun4, mStar,
                mMeteor, mMoon, mWatercover1, mWatercover2, mWatercover3, mWatercover4, mNightcover,
                mCapCover, mFog01, mFog02, mIce, mRain1, mRain2, mRain3, mRain4, mWaterdrop,
                mCloudcover, mFrostC, mFrostE, mFrostF, mSnow1, mSnow2, mSnow3, mSnow4, mSkyFlash,
                mLightning1, mLightning2, mLightning3, mWaveBack
        };
        GLES20.glDeleteTextures(textures.length, textures, 0);
        if (mWave != null) {
            GLES20.glDeleteTextures(mWave.length, mWave, 0);
        }
        if (mRaindrop1 != null) {
            GLES20.glDeleteTextures(mRaindrop1.length, mRaindrop1, 0);
        }
        if (mRaindrop2 != null) {
            GLES20.glDeleteTextures(mRaindrop2.length, mRaindrop2, 0);
        }
    }

    private void initGl() {
        String vs = RawResourceLoader.readRawText(mResources, R.raw.ocean_weather_vs);
        String fs = RawResourceLoader.readRawText(mResources, R.raw.ocean_weather_fs);
        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        float[] quadVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.ocean_quad_vertices);
        float[] rectOneToTwoVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.ocean_rect_one_to_two_vertices);
        float[] rectOneToFourVertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.ocean_rect_one_to_four_vertices);
        float[] quadTex = RawResourceLoader.readRawFloatArray(mResources, R.raw.ocean_quad_tex);

        mVertexBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertexBuffer.put(quadVertices).position(0);

        mRectOneToTwoBuffer = ByteBuffer.allocateDirect(rectOneToTwoVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mRectOneToTwoBuffer.put(rectOneToTwoVertices).position(0);

        mRectOneToFourBuffer = ByteBuffer.allocateDirect(rectOneToFourVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        mRectOneToFourBuffer.put(rectOneToFourVertices).position(0);

        mTexBuffer = ByteBuffer.allocateDirect(quadTex.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mTexBuffer.put(quadTex).position(0);

        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_BLEND);

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
        mSkyA = GLTextureUtils.loadTexture(mResources, R.drawable.a_sky);
        mSkyB = GLTextureUtils.loadTexture(mResources, R.drawable.b_sky);
        mSkyC = GLTextureUtils.loadTexture(mResources, R.drawable.c_sky);
        mSkyD = GLTextureUtils.loadTexture(mResources, R.drawable.d_sky);
        mSkyG = GLTextureUtils.loadTexture(mResources, R.drawable.g_sky);
        mSkyStars = GLTextureUtils.loadTexture(mResources, R.drawable.d_sky_stars);
        mCloudA01 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_01);
        mCloudA02 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_02);
        mCloudA03 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_03);
        mCloudB01 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_01);
        mCloudB02 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_02);
        mCloudB03 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_03);
        mCloudLightA1 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_01);
        mCloudLightA2 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_02);
        mCloudLightA3 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_a_light_03);
        mCloudLightB1 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_01);
        mCloudLightB2 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_02);
        mCloudLightB3 = GLTextureUtils.loadTexture(mResources, R.drawable.cloud_b_light_03);
        mSun1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_01);
        mSun2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_02);
        mSun3 = GLTextureUtils.loadTexture(mResources, R.drawable.a_sun_03);
        mSun4 = GLTextureUtils.loadTexture(mResources, R.drawable.ocean_a_sun_04);
        mStar = GLTextureUtils.loadTexture(mResources, R.drawable.d_star);
        mMeteor = GLTextureUtils.loadTexture(mResources, R.drawable.ocean_d_meteor);
        mMoon = GLTextureUtils.loadTexture(mResources, R.drawable.ocean_d_moon);
        mWatercover1 = GLTextureUtils.loadTexture(mResources, R.drawable.a_watercover_01);
        mWatercover2 = GLTextureUtils.loadTexture(mResources, R.drawable.a_watercover_02);
        mWatercover3 = GLTextureUtils.loadTexture(mResources, R.drawable.a_watercover_03);
        mWatercover4 = GLTextureUtils.loadTexture(mResources, R.drawable.a_watercover_04);
        mNightcover = GLTextureUtils.loadTexture(mResources, R.drawable.nightcover_01);
        mCapCover = GLTextureUtils.loadTexture(mResources, R.drawable.a_cap_01);
        mFog01 = GLTextureUtils.loadTexture(mResources, R.drawable.fog_01);
        mFog02 = GLTextureUtils.loadTexture(mResources, R.drawable.fog_02);
        mIce = GLTextureUtils.loadTexture(mResources, R.drawable.ice);
        mRain1 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_01);
        mRain2 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_02);
        mRain3 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_03);
        mRain4 = GLTextureUtils.loadTexture(mResources, R.drawable.c_rain_04);
        mWaterdrop = GLTextureUtils.loadTexture(mResources, R.drawable.c_waterdrop);
        mCloudcover = GLTextureUtils.loadTexture(mResources, R.drawable.ocean_c_cloudcover);
        mFrostC = GLTextureUtils.loadTexture(mResources, R.drawable.c_frost);
        mFrostE = GLTextureUtils.loadTexture(mResources, R.drawable.e_frost);
        mFrostF = GLTextureUtils.loadTexture(mResources, R.drawable.f_frost);
        mSnow1 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_01);
        mSnow2 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_02);
        mSnow3 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_03);
        mSnow4 = GLTextureUtils.loadTexture(mResources, R.drawable.e_snow_04);
        mSkyFlash = GLTextureUtils.loadTexture(mResources, R.drawable.g_sky_flash);
        mLightning1 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_01);
        mLightning2 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_02);
        mLightning3 = GLTextureUtils.loadTexture(mResources, R.drawable.g_lightning_03);
        mWaveBack = GLTextureUtils.loadTexture(mResources, R.drawable.sand);

        mWave = new int[32];
        for (int i = 0; i < 32; i++) {
            int resId = mResources.getIdentifier(String.format("sea_a_%02d", i + 1), "drawable", "com.reandroid.wallpaper");
            mWave[i] = GLTextureUtils.loadTexture(mResources, resId);
        }

        mRaindrop1 = new int[25];
        mRaindrop2 = new int[25];
        for (int i = 0; i < 25; i++) {
            int res1 = mResources.getIdentifier(String.format("waterdrop_a_%d", i), "drawable", "com.reandroid.wallpaper");
            int res2 = mResources.getIdentifier(String.format("waterdrop_b_%d", i), "drawable", "com.reandroid.wallpaper");
            mRaindrop1[i] = GLTextureUtils.loadTexture(mResources, res1);
            mRaindrop2[i] = GLTextureUtils.loadTexture(mResources, res2);
        }
    }

    private void initMemory() {
        mStarDraw = new boolean[7];
        mStarAlpha = new float[7];
        mStarStart = new int[7];
        mStarDuration = new int[7];

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

        mSnow1X = new float[mSnowCount1];
        mSnow1Y = new float[mSnowCount1];
        mSnow1Scale = new float[mSnowCount1];
        mSnow2X = new float[mSnowCount2];
        mSnow2Y = new float[mSnowCount2];
        mSnow2Scale = new float[mSnowCount2];
        mSnow3X = new float[mSnowCount3];
        mSnow3Y = new float[mSnowCount3];
        mSnow3Scale = new float[mSnowCount3];
    }

    private void onWeatherUpdated(WeatherState state) {
        applyWeatherState(state);
    }

    private void applyWeatherState(WeatherState state) {
        if (state == null) {
            return;
        }
        mCondition = state.condition;
        mIsNight = state.isNight;
    }

    private void updateWeatherFlags() {
        if (mCondition != mLastCondition || mIsNight != mLastNight) {
            mClearOn = mCondition == WeatherCondition.D1_CLEAR;
            mRainOn = mCondition == WeatherCondition.D5_RAIN_SHOWERS || mCondition == WeatherCondition.D6_THUNDERSTORMS
                    || mCondition == WeatherCondition.D9_SLEET;
            mSnowOn = mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET;
            mThunderOn = mCondition == WeatherCondition.D6_THUNDERSTORMS;
            mLastCondition = mCondition;
            mLastNight = mIsNight;
        }
    }

    private boolean shouldFastAnimate() {
        return mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET;
    }

    private void drawObjects() {
        if (isPreview()) {
            mOffset = 1.25f;
        }

        drawSpriteRectOneToTwo(selectSkyTexture(), (-2.0f) + ((1.5f - mOffset) * 5.0f), 4.7f, -30.0f,
            3.8f * mLandscape, 3.5f * mFillScaleY, 0.0f, 1.0f);

        if (mCondition == WeatherCondition.D1_CLEAR && mIsNight) {
            drawSpriteRectOneToFour(mSkyStars, 2.2f + ((1.5f - mOffset) * 5.0f), 7.0f, -29.9f,
                    2.3f * mLandscape, 2.3f, 0.0f, 1.0f);
        }

        if (mCondition == WeatherCondition.D1_CLEAR
                || mCondition == WeatherCondition.D2_CLOUDY
                || mCondition == WeatherCondition.D8_ICE_COLD) {
            if (!mIsNight) {
                if (mCondition == WeatherCondition.D1_CLEAR) {
                    float sunX = (((1.5f - mOffset) * 5.0f) * 0.2f) - 3.0f;
                    drawSprite(mSun1, sunX, 5.5f, -28.0f, 1.0f, 1.0f, mFrameCnt * 0.45f, 1.0f);
                    drawSprite(mSun2, sunX, 5.5f, -28.0f, 1.0f, 1.0f, mFrameCnt * 0.225f, 1.0f);
                    drawSprite(mSun3, sunX, 5.5f, -28.0f, 1.0f, 1.0f, mFrameCnt * -0.45f, 1.0f);
                }
            } else {
                if (mCondition != WeatherCondition.D2_CLOUDY) {
                    drawSprite(mMoon, 3.2f + ((1.5f - mOffset) * 5.0f), 7.0f, -28.5f,
                            0.25f * mLandscape, 0.25f, 0.0f, 1.0f);
                }
                if (mCondition != WeatherCondition.D2_CLOUDY) {
                    updateStars();
                    updateMeteor();
                }
            }
        }

        drawClouds();
        drawWaves();
        drawSunlight();
        drawWaterCover();
        drawFogIce();
        drawRain();
        drawFrost();
        drawSnow();
        drawThunder();
    }

    private int selectSkyTexture() {
        if (mIsNight) {
            return mSkyD;
        }
        switch (mCondition) {
            case D1_CLEAR:
                return mSkyA;
            case D2_CLOUDY:
            case D4_FOG:
            case D8_ICE_COLD:
                return mSkyB;
            case D5_RAIN_SHOWERS:
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return mSkyC;
            case D3_DREARY:
            case D6_THUNDERSTORMS:
            default:
                return mSkyG;
        }
    }

    private int selectCloudATexture() {
        switch (mCondition) {
            case D1_CLEAR:
                return mCloudA01;
            case D3_DREARY:
                return mCloudA03;
            default:
                return mCloudA02;
        }
    }

    private int selectCloudBTexture() {
        switch (mCondition) {
            case D1_CLEAR:
                return mCloudB01;
            case D3_DREARY:
                return mCloudB03;
            default:
                return mCloudB02;
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
                    } else if (mFrameCnt % 200 < mStarStart[i] + (mStarDuration[i] * 2)
                            && mStarAlpha[i] > 0.0f) {
                        mStarAlpha[i] = (float) (mStarAlpha[i] - 0.04d);
                    }
                    drawSprite(mStar, STAR_X[i] + ((1.5f - mOffset) * 5.0f), STAR_Y[i], -28.0f,
                            mLandscape * STAR_SIZE[i], STAR_SIZE[i], 0.0f, mStarAlpha[i]);
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
        int cloudA = selectCloudATexture();
        int cloudB = selectCloudBTexture();
        if ((mCondition == WeatherCondition.D1_CLEAR || mCondition == WeatherCondition.D8_ICE_COLD) && !mIsNight) {
            if (mFrameCnt < 300) {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) - 13.0d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * mFrameCnt) + 27.0d);
            }
            if (mFrameCnt < 360) {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) - 11.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * mFrameCnt) + 29.0d);
            }
            if (mFrameCnt < 300) {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) - 9.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * mFrameCnt) + 31.0d);
            }
            if (mFrameCnt < 900) {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) + 2.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * mFrameCnt) + 42.0d);
            }
            if (mFrameCnt < 940) {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 8.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * mFrameCnt) + 48.0d);
            }
            if (mFrameCnt < 900) {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) + 6.0d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * mFrameCnt) + 46.0d);
            }
                drawSpriteRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - mOffset) * 5.0f), 6.0f, -27.0f,
                    1.3f * mLandscape, 1.3f, 0.0f, 0.6f);
                drawSpriteRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - mOffset) * 5.0f), 1.0f, -27.0f,
                    2.0f * mLandscape, 2.0f, 0.0f, 0.6f);
                drawSpriteRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - mOffset) * 5.0f), 3.8f, -26.0f,
                    1.3f * mLandscape, 1.3f, 0.0f, 0.7f);
                drawSpriteRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - mOffset) * 5.0f), 3.8f, -26.0f,
                    1.2f * mLandscape, 1.2f, 0.0f, 0.9f);
                drawSpriteRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - mOffset) * 5.0f), -0.2f, -26.0f,
                    1.6f * mLandscape, 1.6f, 0.0f, 0.3f);
                drawSpriteRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - mOffset) * 5.0f), 4.7f, -27.0f,
                    1.1f * mLandscape, 1.1f, 0.0f, 0.8f);
            return;
        }

        if (mCondition == WeatherCondition.D2_CLOUDY || mCondition == WeatherCondition.D3_DREARY
                || mCondition == WeatherCondition.D4_FOG || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS || mCondition == WeatherCondition.D7_FLURRIES_SNOW
                || mCondition == WeatherCondition.D9_SLEET) {
            boolean darkCloud = mCondition == WeatherCondition.D3_DREARY
                    || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                    || mCondition == WeatherCondition.D6_THUNDERSTORMS
                    || mCondition == WeatherCondition.D7_FLURRIES_SNOW
                    || mCondition == WeatherCondition.D9_SLEET;

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

                float tint = darkCloud ? 0.2f : 0.0f;
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - mOffset) * 5.0f), 5.5f, -27.0f,
                    2.0f * mLandscape, 2.2f, 0.0f, 0.9f - tint, 0.9f - tint, 0.9f - tint, 0.9f);
                float alphaA2 = mIsNight ? 0.9f : 0.8f;
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - mOffset) * 5.0f), 4.8f, -27.3f,
                    1.8f * mLandscape, 1.8f, 0.0f, alphaA2 - tint, alphaA2 - tint, alphaA2 - tint, alphaA2);
                float alphaA3 = mIsNight ? 1.0f : 0.8f;
                drawSpriteColoredRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - mOffset) * 5.0f), 2.0f, -27.5f,
                    1.4f * mLandscape, 1.4f, 0.0f, alphaA3 - tint, alphaA3 - tint, alphaA3 - tint, alphaA3);
                float alphaB3 = mIsNight ? 0.9f : 0.7f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - mOffset) * 5.0f), 3.2f, -27.4f,
                    1.4f * mLandscape, 1.6f, 0.0f, alphaB3 - tint, alphaB3 - tint, alphaB3 - tint, alphaB3);
                float alphaB1 = mIsNight ? 1.0f : 0.9f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - mOffset) * 5.0f), 6.2f, -26.9f,
                    2.7f * mLandscape, 2.5f, 0.0f, alphaB1 - tint, alphaB1 - tint, alphaB1 - tint, alphaB1);
                float alphaB2 = mIsNight ? 0.9f : 0.9f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - mOffset) * 5.0f), 7.2f, -27.1f,
                    1.7f * mLandscape, 1.7f, 0.0f, alphaB2 - tint, alphaB2 - tint, alphaB2 - tint, alphaB2);
                float alphaB4 = mIsNight ? 0.9f : 0.4f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX4 + ((1.5f - mOffset) * 5.0f), 0.2f, -27.6f,
                    1.6f * mLandscape, 1.6f, 0.0f, alphaB4 - tint, alphaB4 - tint, alphaB4 - tint, alphaB4);
                float alphaB5 = mIsNight ? 0.8f : 0.4f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX5 + ((1.5f - mOffset) * 5.0f), 0.3f, -27.7f,
                    1.9f * mLandscape, 1.9f, 0.0f, alphaB5 - tint, alphaB5 - tint, alphaB5 - tint, alphaB5);
                float alphaB6 = mIsNight ? 0.9f : 0.7f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX6 + ((1.5f - mOffset) * 5.0f), -0.2f, -27.8f,
                    2.2f * mLandscape, 2.2f, 0.0f, alphaB6 - tint, alphaB6 - tint, alphaB6 - tint, alphaB6);
                float alphaB7 = mIsNight ? 0.8f : 0.6f;
                drawSpriteColoredRectOneToTwo(cloudB, mCloudBX7 + ((1.5f - mOffset) * 5.0f), 0.4f, -27.9f,
                    1.8f * mLandscape, 1.8f, 0.0f, alphaB7 - tint, alphaB7 - tint, alphaB7 - tint, alphaB7);

            if (mCondition == WeatherCondition.D6_THUNDERSTORMS) {
                drawCloudLights();
            }
        }
    }

    private void drawCloudLights() {
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
            mThunderOn = false;
            return;
        }

        for (int i = 0; i < 20; i++) {
            if (mFrameCnt % 400 > mCloudLightStart[i]
                    && mFrameCnt % 400 < mCloudLightStart[i] + mCloudLightDuration[i]) {
                float lightX = 0.0f;
                float lightY = 0.0f;
                float scale = 0.0f;
                switch (mCloudLightNum[i]) {
                    case 0:
                        lightX = mCloudAX1;
                        lightY = 5.5f;
                        scale = 2.2f;
                        break;
                    case 1:
                        lightX = mCloudAX2;
                        lightY = 6.0f;
                        scale = 1.5f;
                        break;
                    case 2:
                        lightX = mCloudAX3;
                        lightY = 3.5f;
                        scale = 1.2f;
                        break;
                    case 3:
                        lightX = mCloudBX1;
                        lightY = 6.5f;
                        scale = 2.0f;
                        break;
                    case 4:
                        lightX = mCloudBX2;
                        lightY = 8.0f;
                        scale = 1.2f;
                        break;
                    case 5:
                        lightX = mCloudBX3;
                        lightY = 5.8f;
                        scale = 1.0f;
                        break;
                    case 6:
                        lightX = mCloudBX4;
                        lightY = 1.8f;
                        scale = 1.6f;
                        break;
                    case 7:
                        lightX = mCloudBX5;
                        lightY = 0.8f;
                        scale = 2.2f;
                        break;
                    case 8:
                        lightX = mCloudBX6;
                        lightY = 0.5f;
                        scale = 1.2f;
                        break;
                    default:
                        break;
                }
                float alpha = (mFrameCnt % 400 < mCloudLightStart[i] + (mCloudLightDuration[i] * 0.5f))
                        ? 0.6f + (((float) Math.random()) * 0.4f)
                        : 0.0f + (((float) Math.random()) * 0.4f);
                if (mCloudLightPos[i] < 0 || mCloudLightPos[i] > 2) {
                    continue;
                }
                int tex;
                if (mCloudLightNum[i] < 3) {
                    if (mCloudLightPos[i] == 0) {
                        tex = mCloudLightA1;
                    } else if (mCloudLightPos[i] == 1) {
                        tex = mCloudLightA2;
                    } else {
                        tex = mCloudLightA3;
                    }
                } else {
                    if (mCloudLightPos[i] == 0) {
                        tex = mCloudLightB1;
                    } else if (mCloudLightPos[i] == 1) {
                        tex = mCloudLightB2;
                    } else {
                        tex = mCloudLightB3;
                    }
                }
                drawSpriteRectOneToTwo(tex, ((1.5f - mOffset) * 5.0f) + lightX, lightY, -26.0f,
                    mLandscape * scale, scale, 0.0f, alpha);
            }
        }
    }

    private void drawWaves() {
        drawSprite(mWaveBack, (1.25f - mOffset) * 5.0f, -6.2f, -22.0f,
                1.6f * mLandscape, 0.6f, 0.0f, 1.0f);

        float baseX = (-5.0f) + ((1.25f - (mOffset / 5.0f)) * 5.0f);
        int waveCnt = mFrameCnt % 200;
        if (waveCnt < 54) {
            drawSprite(mWave[(waveCnt / 3) + 4], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
        } else if (waveCnt < 84) {
            float alpha1 = (waveCnt - 54) / 30.0f;
            drawSprite(mWave[(waveCnt / 3) + 4], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
            drawSprite(mWave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, alpha1);
        } else if (waveCnt < 108) {
            drawSprite(mWave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
        } else if (waveCnt < 120) {
            float alpha2 = (waveCnt - 108) / 12.0f;
            drawSprite(mWave[((waveCnt - 54) / 3) + 10], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
            drawSprite(mWave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, alpha2);
        } else if (waveCnt < 188) {
            drawSprite(mWave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
        } else {
            float alpha3 = (waveCnt - 188) / 12.0f;
            drawSprite(mWave[(waveCnt - 108) / 3], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, 1.0f);
            drawSprite(mWave[(waveCnt - 188) / 3], baseX, -4.3f, -21.5f,
                    0.825f * mLandscape, 0.37f, 0.0f, alpha3);
        }
    }

    private void drawSunlight() {
        if (mCondition == WeatherCondition.D1_CLEAR && !mIsNight && mClearOn) {
            float sunlightCnt = (mFrameCnt + 50) % 200;
            float coeff = sunlightCnt < 100.0f
                    ? 2.0f - (sunlightCnt / 100.0f)
                    : ((sunlightCnt - 100.0f) / 100.0f) + 1.0f;
            float alpha = (float) Math.sqrt(coeff - 1.0f);
            drawSprite(mSun4, (0.6f - 3.0f) + ((1.5f - mOffset) * 5.0f * 0.15f), 5.5f - 1.75f, -20.5f,
                    mLandscape * coeff * 0.6f, 0.6f * coeff, -1.8f * sunlightCnt, alpha);
            mSunInitCnt++;
            if (mSunInitCnt > 400 && sunlightCnt == 100.0f) {
                mClearOn = false;
                mSunInitCnt = 0;
            }
        }
    }

    private void drawWaterCover() {
        if (mCondition == WeatherCondition.D1_CLEAR) {
            if (mIsNight) {
                drawSprite(mWatercover1, 0.0f + ((1.5f - mOffset) * 5.0f), -4.95f, -21.0f,
                        3.2f * mLandscape, 3.6f, 0.0f, 0.8f);
            }
            return;
        }

        if (mCondition == WeatherCondition.D2_CLOUDY
                || mCondition == WeatherCondition.D4_FOG
                || mCondition == WeatherCondition.D8_ICE_COLD) {
            if (!mIsNight) {
                drawSprite(mWatercover2, (-1.5f) + ((1.5f - mOffset) * 5.0f), -3.3f, -21.0f,
                        3.0f * mLandscape, 2.75f, 0.0f, 0.8f);
            } else {
                drawSprite(mWatercover1, 0.0f + ((1.5f - mOffset) * 5.0f), -4.95f, -21.0f,
                        3.2f * mLandscape, 3.6f, 0.0f, 0.8f);
                drawSprite(mCapCover, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 0.2f);
            }
            return;
        }

        if (mCondition == WeatherCondition.D3_DREARY
                || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS
                || mCondition == WeatherCondition.D7_FLURRIES_SNOW
                || mCondition == WeatherCondition.D9_SLEET) {
            if (!mIsNight) {
                drawSprite(selectWatercoverStorm(), (-1.5f) + ((1.5f - mOffset) * 5.0f), -4.95f, -21.0f,
                        3.0f * mLandscape, 3.6f, 0.0f, 0.8f);
            } else {
                drawSprite(mWatercover1, 0.0f + ((1.5f - mOffset) * 5.0f), -4.95f, -21.0f,
                        3.2f * mLandscape, 3.6f, 0.0f, 0.8f);
                int cover = (mCondition == WeatherCondition.D3_DREARY || mCondition == WeatherCondition.D6_THUNDERSTORMS)
                        ? mCapCover : mNightcover;
                drawSprite(cover, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 0.2f);
            }
        }
    }

    private int selectWatercoverStorm() {
        if (mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET) {
            return mWatercover4;
        }
        return mWatercover3;
    }

    private void drawFogIce() {
        if (mCondition == WeatherCondition.D4_FOG) {
            drawSprite(mIsNight ? mFog02 : mFog01, 0.0f, -0.5f, -20.0f,
                    0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
        }
        if (mCondition == WeatherCondition.D8_ICE_COLD) {
            drawSprite(mIce, 0.0f, -0.5f, -20.0f, 0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
        }
    }

    private void drawRain() {
        if (mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS
                || mCondition == WeatherCondition.D9_SLEET) {
            int rainTex = mRain1;
            switch (mFrameCnt % 4) {
                case 1:
                    rainTex = mRain2;
                    break;
                case 2:
                    rainTex = mRain3;
                    break;
                case 3:
                    rainTex = mRain4;
                    break;
                default:
                    break;
            }
            drawSprite(rainTex, 0.0f, 0.0f, -20.5f, 1.5f * mLandscape, 1.5f, 0.0f, 1.0f);
            drawSprite(mCloudcover, 0.0f, 7.2f, -25.0f, 2.0f * mLandscape, 2.0f, 0.0f, 1.0f);

            if (mCondition == WeatherCondition.D5_RAIN_SHOWERS
                    || mCondition == WeatherCondition.D9_SLEET) {
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
                        drawSprite(mRaindrop1[idx], mRaindrop1X[i], mRaindrop1Y[i], -19.5f,
                                mRaindrop1Scale[i] * 0.11f * mLandscape, mRaindrop1Scale[i] * 0.45f, 0.0f, 1.0f);
                    }
                    if (mFrameCnt % 400 > mRaindrop2Start[i] && mFrameCnt % 400 < mRaindrop2Start[i] + 100) {
                        int idx = ((mFrameCnt % 400) - mRaindrop2Start[i]) / 4;
                        drawSprite(mRaindrop2[idx], mRaindrop2X[i], mRaindrop2Y[i], -19.5f,
                                mRaindrop2Scale[i] * 0.11f * mLandscape, mRaindrop2Scale[i] * 0.9f, 0.0f, 1.0f);
                    }
                }
            }
        }
    }

    private void drawFrost() {
        if (mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D7_FLURRIES_SNOW
                || mCondition == WeatherCondition.D9_SLEET) {
            if (mCondition == WeatherCondition.D9_SLEET) {
                drawSprite(mFrostF, -0.1f, -0.5f, -20.3f, 0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
            } else if (mCondition == WeatherCondition.D5_RAIN_SHOWERS) {
                drawSprite(mFrostC, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 1.0f);
            } else {
                drawSprite(mFrostE, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 1.0f);
            }
        }
    }

    private void drawSnow() {
        if (mCondition != WeatherCondition.D7_FLURRIES_SNOW
                && mCondition != WeatherCondition.D9_SLEET) {
            return;
        }

        if (mSnowOn) {
            for (int i = 0; i < mSnowCount1; i++) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale == 0 ? 1.0f : 0.5f;
            }
            for (int i = 0; i < mSnowCount2; i++) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 3;
                if (scale == 0) {
                    mSnow2Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow2Scale[i] = 0.7f;
                } else {
                    mSnow2Scale[i] = 0.5f;
                }
            }
            for (int i = 0; i < mSnowCount3; i++) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 4;
                if (scale == 0) {
                    mSnow3Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow3Scale[i] = 0.5f;
                } else if (scale == 2) {
                    mSnow3Scale[i] = 0.3f;
                } else {
                    mSnow3Scale[i] = 0.2f;
                }
            }
            mSnowOn = false;
            return;
        }

        for (int i = 0; i < mSnowCount1; i++) {
            if (mSnow1Y[i] < -8.0f) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale == 0 ? 1.0f : 0.5f;
            } else {
                mSnow1Y[i] -= 0.04f;
            }
            float z = mCondition == WeatherCondition.D9_SLEET ? -22.0f : -20.0f;
            float alpha = mCondition == WeatherCondition.D9_SLEET ? mSnow1Scale[i] / 2.0f : mSnow1Scale[i];
            drawSprite(mSnow1, (mSnow1X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow1Y[i], z,
                    mSnow1Scale[i] * 0.1f * mLandscape, mSnow1Scale[i] * 0.1f, mFrameCnt * 0.225f, alpha);
        }

        for (int i = 0; i < mSnowCount2; i++) {
            if (mSnow2Y[i] < -8.0f) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 3;
                if (scale == 0) {
                    mSnow2Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow2Scale[i] = 0.7f;
                } else {
                    mSnow2Scale[i] = 0.5f;
                }
            } else {
                mSnow2Y[i] -= 0.02f;
            }
            float z = mCondition == WeatherCondition.D9_SLEET ? -22.5f : -20.0f;
            float alpha = mCondition == WeatherCondition.D9_SLEET ? mSnow2Scale[i] / 2.0f : mSnow2Scale[i];
            drawSprite(mSnow2, (mSnow2X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow2Y[i], z,
                    mSnow2Scale[i] * 0.02f * mLandscape, mSnow2Scale[i] * 0.02f, 0.0f, alpha);
            if ((i & 273) == 1 && mCondition != WeatherCondition.D9_SLEET) {
                drawSprite(mSnow4, (mSnow2X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow2Y[i] + 1.0f, -21.0f,
                        0.35f, 0.35f, 0.0f, 0.8f);
            }
        }

        for (int i = 0; i < mSnowCount3; i++) {
            if (mSnow3Y[i] < -8.0f) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 4;
                if (scale == 0) {
                    mSnow3Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow3Scale[i] = 0.5f;
                } else if (scale == 2) {
                    mSnow3Scale[i] = 0.3f;
                } else {
                    mSnow3Scale[i] = 0.2f;
                }
            } else {
                mSnow3Y[i] -= 0.01f;
            }
            float z = mCondition == WeatherCondition.D9_SLEET ? -23.0f : -20.0f;
            float alpha = mCondition == WeatherCondition.D9_SLEET ? mSnow3Scale[i] / 2.0f : mSnow3Scale[i];
            drawSprite(mSnow3, (mSnow3X[i] + ((1.5f - mOffset) * 5.0f)) - 1.0f, mSnow3Y[i], z,
                    mSnow3Scale[i] * 0.01f * mLandscape, mSnow3Scale[i] * 0.01f, 0.0f, alpha);
        }
    }

    private void drawThunder() {
        if (mCondition != WeatherCondition.D6_THUNDERSTORMS) {
            return;
        }
        if (mThunderOn || mFrameCnt % 400 == 0) {
            for (int i = 0; i < 40; i++) {
                mThunderStart[i] = (int) (Math.random() * 390.0d);
                mThunderDuration[i] = (int) (Math.random() * 20.0d);
                mThunderNum[i] = (int) (Math.random() * 3.0d);
                mThunderScale[i] = (float) (Math.random() * 0.5d);
                mThunderX[i] = (float) ((Math.random() * 15.0d) - 6.0d);
                mThunderY[i] = (float) ((Math.random() * 5.0d) + 6.0d);
            }
            if (mThunderOn) {
                mThunderStart[0] = (mFrameCnt % 400) + 10;
                mThunderDuration[0] = 5;
                mThunderNum[0] = 1;
                mThunderScale[0] = 1.0f;
                mThunderX[0] = 0.0f;
                mThunderY[0] = 10.0f;
                mThunderStart[1] = (mFrameCnt % 400) + 8;
                mThunderDuration[1] = 7;
                mThunderNum[1] = 2;
                mThunderScale[1] = 1.0f;
                mThunderX[1] = 5.0f;
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
                        ? 0.8f + (((float) Math.random()) * 0.2f)
                        : 0.2f + (((float) Math.random()) * 0.2f);
                drawSprite(mSkyFlash, (-0.5f) + ((1.5f - mOffset) * 5.0f), 0.0f, -19.0f,
                        1.5f, 1.2f, 0.0f, alpha);
            }
            if (mFrameCnt % 400 > mThunderStart[i]
                    && mFrameCnt % 400 < mThunderStart[i] + mThunderDuration[i]) {
                float alpha;
                if (mFrameCnt % 400 < mThunderStart[i] + (mThunderDuration[i] * 0.5f)) {
                    alpha = mThunderScale[i] > 0.75f
                            ? 0.8f + (((float) Math.random()) * 0.2f)
                            : 0.5f + (((float) Math.random()) * 0.2f);
                } else {
                    alpha = mThunderScale[i] > 0.75f
                            ? 0.3f + (((float) Math.random()) * 0.2f)
                            : 0.1f + (((float) Math.random()) * 0.2f);
                }
                int tex = 0;
                switch (mThunderNum[i]) {
                    case 0:
                        tex = mLightning1;
                        break;
                    case 1:
                        tex = mLightning2;
                        break;
                    case 2:
                        tex = mLightning3;
                        break;
                    default:
                        break;
                }
                if (tex != 0) {
                    drawSprite(tex, mThunderX[i] + ((1.5f - mOffset) * 5.0f), mThunderY[i], -26.0f,
                            mThunderScale[i] * mLandscape, mThunderScale[i], 0.0f, alpha);
                }
            }
        }
    }

    private void drawSprite(int texture, float x, float y, float z,
                            float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColored(texture, x, y, z, scaleX, scaleY, rotation, alpha, alpha, alpha, alpha);
    }

    private void drawSpriteRectOneToTwo(int texture, float x, float y, float z,
                                        float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                alpha, alpha, alpha, alpha, mRectOneToTwoBuffer);
    }

        private void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z,
                               float scaleX, float scaleY, float rotation,
                               float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
            r, g, b, alpha, mRectOneToTwoBuffer);
        }

    private void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                         float scaleX, float scaleY, float rotation, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation,
                alpha, alpha, alpha, alpha, mRectOneToFourBuffer);
    }

    private void drawSpriteColored(int texture, float x, float y, float z,
                                   float scaleX, float scaleY, float rotation,
                                   float r, float g, float b, float alpha) {
        drawSpriteColoredWithBuffer(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha, mVertexBuffer);
    }

    private void drawSpriteColoredWithBuffer(int texture, float x, float y, float z,
                                             float scaleX, float scaleY, float rotation,
                                             float r, float g, float b, float alpha,
                                             FloatBuffer vertexBuffer) {
        if (texture == 0) {
            return;
        }
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, x, y, z);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0f, 0f, 1f);
        Matrix.scaleM(mModelMatrix, 0, scaleX, scaleY, 1.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);

        GLES20.glUseProgram(mProgram);

        vertexBuffer.position(0);
        mTexBuffer.position(0);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexBuffer);

        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform4f(mColorHandle, r, g, b, alpha);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
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

    private int createShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = createShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = createShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }
}
