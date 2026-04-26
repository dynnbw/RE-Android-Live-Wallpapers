package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.GLTextureUtils;
import com.reandroid.wallpaper.weatherwallpapers.AnimationController;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SpriteDrawer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherFlagManager;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.gles.RawResourceLoader;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class WindmillGL extends GLESScene {
    private static final String TAG = "WindmillGL";
    private static final int MAX_FRAME = 2000;
    private static final float FOV = 45.0f;

    private final float[] mProjectionMatrix = new float[16];
    private final SpriteDrawer mSpriteDrawer = new SpriteDrawer();
    private final AnimationController mAnimationController = new AnimationController(MAX_FRAME);

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

    private WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    private boolean mIsNight = false;

    private WeatherStateManager mWeatherStateManager;

    private final WeatherFlagManager mWeatherFlagManager = new WeatherFlagManager();

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

    private final WindmillRenderer mWindmillRenderer = new WindmillRenderer();
    private final GroundRenderer mGroundRenderer = new GroundRenderer();
    private final CloudRenderer mCloudRenderer = new CloudRenderer();
    private final FogIceRenderer mFogIceRenderer = new FogIceRenderer();
    private final PrecipitationRenderer mPrecipitationRenderer = new PrecipitationRenderer();
    private final ThunderRenderer mThunderRenderer = new ThunderRenderer();
    private final SkyRenderer mSkyRenderer = new SkyRenderer();
    private final DrawerBridge mDrawerBridge = new DrawerBridge();
    private final WindmillRenderer.Drawer mWindmillDrawer = mDrawerBridge;
    private final GroundRenderer.Drawer mGroundDrawer = mDrawerBridge;
    private final CloudRenderer.Drawer mCloudDrawer = mDrawerBridge;
    private final FogIceRenderer.Drawer mFogIceDrawer = mDrawerBridge;
    private final SkyRenderer.Drawer mSkyDrawer = mDrawerBridge;
    private final PrecipitationRenderer.Drawer mPrecipitationDrawer = mDrawerBridge;
    private final ThunderRenderer.Drawer mThunderDrawer = mDrawerBridge;

    private final class DrawerBridge implements WindmillRenderer.Drawer,
            GroundRenderer.Drawer,
            CloudRenderer.Drawer,
            FogIceRenderer.Drawer,
            SkyRenderer.Drawer,
            PrecipitationRenderer.Drawer,
            ThunderRenderer.Drawer {
        @Override
        public void drawSprite(int texture, float x, float y, float z,
                               float scaleX, float scaleY, float rotation, float alpha) {
            mSpriteDrawer.drawSprite(texture, x, y, z, scaleX, scaleY, rotation, alpha);
        }

        @Override
        public void drawSpriteRectOneToTwo(int texture, float x, float y, float z,
                                           float scaleX, float scaleY, float rotation, float alpha) {
            mSpriteDrawer.drawSpriteRectOneToTwo(texture, x, y, z, scaleX, scaleY, rotation, alpha);
        }

        @Override
        public void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z,
                                                  float scaleX, float scaleY, float rotation,
                                                  float r, float g, float b, float alpha) {
            mSpriteDrawer.drawSpriteColoredRectOneToTwo(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha);
        }

        @Override
        public void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                            float scaleX, float scaleY, float rotation, float alpha) {
            mSpriteDrawer.drawSpriteRectOneToFour(texture, x, y, z, scaleX, scaleY, rotation, alpha);
        }

        @Override
        public void drawSpriteColored(int texture, float x, float y, float z,
                                      float scaleX, float scaleY, float rotation,
                                      float r, float g, float b, float alpha) {
            mSpriteDrawer.drawSpriteColored(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha);
        }
    }

    public WindmillGL(int width, int height) {
        super(width, height);
    }

    @Override
    protected void onCreate() {
        initMemory();
        Context appContext = com.reandroid.gles.GLESWallpaper.getAppContext();
        SharedPreferences prefs = appContext != null
                ? PreferenceManager.getDefaultSharedPreferences(appContext)
                : null;
        mWeatherStateManager = new WeatherStateManager(appContext, prefs);
    }

    @Override
    public void start() {
        if (mWeatherStateManager != null) {
            mWeatherStateManager.start(isPreview());
        }
    }

    @Override
    public void stop() {
        if (mWeatherStateManager != null) {
            mWeatherStateManager.stop();
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

        long deltaMs = mAnimationController.updateDelta(timeMs);

        if (mWeatherStateManager != null) {
            mWeatherStateManager.update(timeMs, isPreview());
            mCondition = mWeatherStateManager.getCondition();
            mIsNight = mWeatherStateManager.isNight();
        }

        float frameDuration = mWeatherStateManager != null && mWeatherStateManager.shouldFastAnimate()
                ? 16.666f
                : 20.0f;
        mAnimationController.setFrameDurationMs(frameDuration);
        mAnimationController.advanceFrame(deltaMs);
        mFrameCnt = mAnimationController.getFrameCount();

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

        mSpriteDrawer.configure(
            mProgram,
            mPositionHandle,
            mTexCoordHandle,
            mMatrixHandle,
            mColorHandle,
            mSamplerHandle,
            mProjectionMatrix,
            mVertexBuffer,
            mTexBuffer,
            mRectOneToTwoBuffer,
            mRectVertexBuffer
        );

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
            mRain1, mRain2, mRain3, mRain4, mFog02, mIce, mWaterdrop, mFrostE, mFrostF,
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

        GroundRenderer.GroundTextures groundTextures = mGroundRenderer.selectTextures(
                mCondition,
                mIsNight,
                mLand01,
                mLand02,
                mLand03,
                mLand04,
                mLand05,
                mLand06,
                mLand07,
                mLand08,
                mLand09,
                mLawn01,
                mLawn02,
                mLawn03,
                mLawn04,
                mLawn05
        );

        boolean clearOn = mWeatherFlagManager.isClearOn();
        boolean rainOn = mWeatherFlagManager.isRainOn();
        boolean snowOn = mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mWeatherFlagManager.isThunderOn();

        clearOn = mSkyRenderer.drawSkyAndCelestial(
                mSkyDrawer,
                mCondition,
                mIsNight,
                mFrameCnt,
                mOffset,
                mLandscape,
                mFillScaleY,
                mSky01,
                mSky02,
                mSky03,
                mSky04,
                mSky04,
                mSkyStars,
                mSun1,
                mSun2,
                mSun3,
                mMoon,
                mStar,
                mMeteor,
                clearOn,
                SkyRenderer.Config.WINDMILL
        );

        if (mIsNight && (mCondition == WeatherCondition.D3_DREARY
                || mCondition == WeatherCondition.D4_FOG
                || mCondition == WeatherCondition.D5_RAIN_SHOWERS
                || mCondition == WeatherCondition.D6_THUNDERSTORMS
                || mCondition == WeatherCondition.D9_SLEET)) {
            mSpriteDrawer.drawSprite(mNightcover, (-1.5f) + ((1.5f - mOffset) * 5.0f), -2.3f, -29.9f,
                    2.0f * mLandscape, 2.0f * mFillScaleY, 0.0f, 1.0f);
        }

        thunderOn = mCloudRenderer.drawClouds(
            mCloudDrawer,
            mCondition,
            mIsNight,
            mFrameCnt,
            mOffset,
            mLandscape,
            mCloudA01,
            mCloudA02,
            mCloudA03,
            mCloudB01,
            mCloudB02,
            mCloudB03,
            mCloudLightA1,
            mCloudLightA2,
            mCloudLightA3,
            mCloudLightB1,
            mCloudLightB2,
            mCloudLightB3,
            thunderOn,
            CloudRenderer.Config.WINDMILL
        );

        mWindmillRenderer.drawByDistance(
            mWindmillDrawer,
            2,
            mFrameCnt,
            mOffset,
            mLandscape,
            mIsNight,
            mWindmillWing,
            mWindmillWingBlur,
            mWindmillCenter1,
            mWindmillCenter2,
            mWindmillPillar1,
            mWindmillPillar2,
            mWindmillPillarFlip1,
            mWindmillPillarFlip2
        );

        mGroundRenderer.drawFarLand(mGroundDrawer, groundTextures.farLand, mOffset, mLandscape);

        mWindmillRenderer.drawByDistance(
            mWindmillDrawer,
            1,
            mFrameCnt,
            mOffset,
            mLandscape,
            mIsNight,
            mWindmillWing,
            mWindmillWingBlur,
            mWindmillCenter1,
            mWindmillCenter2,
            mWindmillPillar1,
            mWindmillPillar2,
            mWindmillPillarFlip1,
            mWindmillPillarFlip2
        );

        mGroundRenderer.drawNearLand(mGroundDrawer, groundTextures.nearLand, mOffset, mLandscape);

        mWindmillRenderer.drawByDistance(
            mWindmillDrawer,
            0,
            mFrameCnt,
            mOffset,
            mLandscape,
            mIsNight,
            mWindmillWing,
            mWindmillWingBlur,
            mWindmillCenter1,
            mWindmillCenter2,
            mWindmillPillar1,
            mWindmillPillar2,
            mWindmillPillarFlip1,
            mWindmillPillarFlip2
        );

        mGroundRenderer.drawLawn(mGroundDrawer, groundTextures.lawn, mOffset, mLandscape);

        clearOn = mSkyRenderer.drawSunlight(
                mSkyDrawer,
                mCondition,
                mIsNight,
                mFrameCnt,
                mOffset,
                mLandscape,
                mSun4,
                clearOn,
                SkyRenderer.Config.WINDMILL
        );
            mFogIceRenderer.drawFogIce(
                mFogIceDrawer,
                mCondition,
                mIsNight,
                mLandscape,
                mFog02,
                mFog02,
                mIce,
                FogIceRenderer.Config.WINDMILL
            );
            rainOn = drawRain(rainOn);
            snowOn = drawSnow(snowOn);
            thunderOn = drawThunder(thunderOn);

        mWeatherFlagManager.setClearOn(clearOn);
        mWeatherFlagManager.setRainOn(rainOn);
        mWeatherFlagManager.setSnowOn(snowOn);
        mWeatherFlagManager.setThunderOn(thunderOn);
    }

    private boolean drawRain(boolean rainOn) {
        return mPrecipitationRenderer.drawRain(
                mPrecipitationDrawer,
                mCondition,
                mFrameCnt,
                mLandscape,
                mFillScaleY,
                mRain1,
                mRain2,
                mRain3,
                mRain4,
                0,
                mWaterdrop,
                mRaindrop1,
                mRaindrop2,
                rainOn,
                PrecipitationRenderer.Config.WINDMILL
        );
    }

    private boolean drawSnow(boolean snowOn) {
        if (mCondition == WeatherCondition.D7_FLURRIES_SNOW || mCondition == WeatherCondition.D9_SLEET) {
            int frostTex = mCondition == WeatherCondition.D9_SLEET ? mFrostF : mFrostE;
            if (mCondition == WeatherCondition.D9_SLEET) {
                mSpriteDrawer.drawSprite(frostTex, -0.1f, -0.5f, -20.3f, 0.65f * mLandscape, 1.05f, 0.0f, 1.0f);
            } else {
                mSpriteDrawer.drawSprite(frostTex, 0.0f, -0.5f, -20.3f, 0.63f * mLandscape, 1.05f, 0.0f, 1.0f);
            }
        }

        return mPrecipitationRenderer.drawSnow(
                mPrecipitationDrawer,
                mCondition,
                mFrameCnt,
                mOffset,
                mLandscape,
                mSnow1,
                mSnow2,
                mSnow3,
                mSnow4,
                snowOn,
                PrecipitationRenderer.Config.WINDMILL
        );
    }

    private boolean drawThunder(boolean thunderOn) {
        return mThunderRenderer.drawThunder(
                mThunderDrawer,
                mCondition,
                mFrameCnt,
                mOffset,
                mLandscape,
                mSkyFlash,
                mLightning1,
                mLightning2,
                mLightning3,
                thunderOn,
                ThunderRenderer.Config.WINDMILL
        );
    }

    private void updateWeatherFlags() {
        mWeatherFlagManager.update(mCondition, mIsNight);
    }

    private void initMemory() {
        mWindmillRenderer.initInstances();
        mCloudRenderer.initMemory();
        mPrecipitationRenderer.initMemory(PrecipitationRenderer.Config.WINDMILL);
        mThunderRenderer.initMemory();
        mSkyRenderer.initMemory();

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
