package com.reandroid.wallpaper.weatherwallpapers.windmill;

import android.opengl.GLES20;
import android.util.Log;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;
import com.reandroid.utils.GLTextureUtils;
import com.reandroid.wallpaper.weatherwallpapers.AnimationController;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SpriteDrawer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class WindmillGL extends GLESScene {
    private static final String TAG = "WindmillGL";

    private final Context mContext;
    // ---- 场景逻辑层（非 GL）----
    private final WindmillScene mScene;

    private final SpriteDrawer mSpriteDrawer = new SpriteDrawer();

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

    private int mSky01, mSky02, mSky03, mSky04, mSkyStars;
    private int mCloudA01, mCloudA02, mCloudA03, mCloudB01, mCloudB02, mCloudB03;
    private int mSun1, mSun2, mSun3, mSun4, mStar, mMeteor, mMoon;
    private int mRain1, mRain2, mRain3, mRain4, mFog02, mIce;
    private int[] mRaindrop1, mRaindrop2;
    private int mWaterdrop, mFrostE, mFrostF;
    private int mSnow1, mSnow2, mSnow3, mSnow4;
    private int mNightcover, mSkyFlash, mLightning1, mLightning2, mLightning3;
    private int mCloudLightA1, mCloudLightA2, mCloudLightA3, mCloudLightB1, mCloudLightB2, mCloudLightB3;
    private int mWindmillWing, mWindmillWingBlur, mWindmillCenter1, mWindmillCenter2;
    private int mWindmillPillar1, mWindmillPillar2, mWindmillPillarFlip1, mWindmillPillarFlip2;
    private int mLand01, mLand02, mLand03, mLand04, mLand05, mLand06, mLand07, mLand08, mLand09;
    private int mLawn01, mLawn02, mLawn03, mLawn04, mLawn05;

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
        mContext = GLESWallpaper.getAppContext();
        mScene = new WindmillScene();
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        mWindmillRenderer.initInstances();
        mCloudRenderer.initMemory();
        mPrecipitationRenderer.initMemory(PrecipitationRenderer.Config.WINDMILL);
        mThunderRenderer.initMemory();
        mSkyRenderer.initMemory();
        Context appContext = com.reandroid.gles.GLESWallpaper.getAppContext();
        mScene.onCreate(appContext);
    }

    @Override
    public void start() {
        mScene.start(isPreview());
    }

    @Override
    public void stop() {
        mScene.stop();
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
            mScene.updateProjection(mWidth, mHeight);
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.mOffset = 2.5f * xOffset;
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) {
            initGl();
        }

        long deltaMs = mScene.mAnimationController.updateDelta(timeMs);

        WeatherStateManager wsm = mScene.mWeatherStateManager;
        if (wsm != null) {
            wsm.update(timeMs, isPreview());
            mScene.mCondition = wsm.getCondition();
            mScene.mIsNight = wsm.isNight();
        }

        float frameDuration = wsm != null && wsm.shouldFastAnimate() ? 16.666f : 20.0f;
        mScene.mAnimationController.setFrameDurationMs(frameDuration);
        mScene.mAnimationController.advanceFrame(deltaMs);
        mScene.mFrameCnt = mScene.mAnimationController.getFrameCount();

        mScene.updateWeatherFlags();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        drawObjects();
    }

    private void initGl() {
        String vs = AssetLoader.readText(mContext, "windmill/shaders/GLES/windmill_vs.glsl");
        String fs = AssetLoader.readText(mContext, "windmill/shaders/GLES/windmill_fs.glsl");
        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        float[] quadVertices = AssetLoader.readFloatArray(mContext, "windmill/data/windmill_quad_vertices.csv");
        float[] rectOneToTwoVertices = AssetLoader.readFloatArray(mContext, "windmill/data/windmill_rect_one_to_two_vertices.csv");
        float[] rectOneToFourVertices = AssetLoader.readFloatArray(mContext, "windmill/data/windmill_rect_one_to_four_vertices.csv");
        float[] quadTex = AssetLoader.readFloatArray(mContext, "windmill/data/windmill_quad_tex.csv");

        mVertexBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertexBuffer.put(quadVertices).position(0);
        mRectOneToTwoBuffer = ByteBuffer.allocateDirect(rectOneToTwoVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mRectOneToTwoBuffer.put(rectOneToTwoVertices).position(0);
        mRectVertexBuffer = ByteBuffer.allocateDirect(rectOneToFourVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mRectVertexBuffer.put(rectOneToFourVertices).position(0);
        mTexBuffer = ByteBuffer.allocateDirect(quadTex.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexBuffer.put(quadTex).position(0);

        mSpriteDrawer.configure(mProgram, mPositionHandle, mTexCoordHandle, mMatrixHandle,
                mColorHandle, mSamplerHandle, mScene.mProjectionMatrix,
                mVertexBuffer, mTexBuffer, mRectOneToTwoBuffer, mRectVertexBuffer);

        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_BLEND);

        mScene.updateProjection(mWidth, mHeight);
        loadTextures();
        mGlReady = true;
    }

    private void loadTextures() {
        mSky01 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/sky_01.jpg");
        mSky02 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/sky_02.jpg");
        mSky03 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/sky_03.png");
        mSky04 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/sky_04.png");
        mSkyStars = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/d_sky_stars.png");
        mCloudA01 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_01.png");
        mCloudA02 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_02.png");
        mCloudA03 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_03.png");
        mCloudB01 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_01.png");
        mCloudB02 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_02.png");
        mCloudB03 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_03.png");
        mSun1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/a_sun_01.png");
        mSun2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/a_sun_02.png");
        mSun3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/a_sun_03.png");
        mSun4 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_sun_04.png");
        mStar = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/d_star.png");
        mMeteor = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/d_meteor.png");
        mMoon = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/d_moon.png");
        mRain1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/c_rain_01.png");
        mRain2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/c_rain_02.png");
        mRain3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/c_rain_03.png");
        mRain4 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/c_rain_04.png");
        mFog02 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/fog_02.png");
        mIce = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/ice.png");
        mWaterdrop = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/c_waterdrop.png");
        mFrostE = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/e_frost.png");
        mFrostF = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/f_frost.png");
        mSnow1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/e_snow_01.png");
        mSnow2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/e_snow_02.png");
        mSnow3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/e_snow_03.png");
        mSnow4 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/e_snow_04.png");
        mNightcover = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/nightcover_01.png");
        mSkyFlash = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/g_sky_flash.png");
        mLightning1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/g_lightning_01.png");
        mLightning2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/g_lightning_02.png");
        mLightning3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/g_lightning_03.png");
        mCloudLightA1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_light_01.png");
        mCloudLightA2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_light_02.png");
        mCloudLightA3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_a_light_03.png");
        mCloudLightB1 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_light_01.png");
        mCloudLightB2 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_light_02.png");
        mCloudLightB3 = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/cloud_b_light_03.png");
        mWindmillWing = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_wing.png");
        mWindmillWingBlur = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_wing_blur2.png");
        mWindmillCenter1 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_center_01.png");
        mWindmillCenter2 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_center_02.png");
        mWindmillPillar1 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_pillar_01.png");
        mWindmillPillar2 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_pillar_02.png");
        mWindmillPillarFlip1 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_pillar_flip_01.png");
        mWindmillPillarFlip2 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_windmill_pillar_flip_blur2_02.png");
        mLand01 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_01.png");
        mLand02 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_02.png");
        mLand03 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_03.png");
        mLand04 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_04.png");
        mLand05 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_05.png");
        mLand06 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_06.png");
        mLand07 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_07.png");
        mLand08 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_08.png");
        mLand09 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_land_09.png");
        mLawn01 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_lawn_01.png");
        mLawn02 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_lawn_02.png");
        mLawn03 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_lawn_03.png");
        mLawn04 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_lawn_04.png");
        mLawn05 = GLTextureUtils.loadTextureNearestPremult(mContext, "windmill/drawable/a_lawn_05.png");

        mRaindrop1 = new int[25];
        mRaindrop2 = new int[25];
        for (int i = 0; i < 25; i++) {
            mRaindrop1[i] = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/waterdrop_a_" + i + ".png");
            mRaindrop2[i] = GLTextureUtils.loadTextureNearestPremult(mContext, "weatherwallpapers/common/drawable/waterdrop_b_" + i + ".png");
        }
    }

    private void deleteTextures() {
        int[] textures = {mSky01,mSky02,mSky03,mSky04,mSkyStars,mCloudA01,mCloudA02,mCloudA03,mCloudB01,mCloudB02,mCloudB03,mSun1,mSun2,mSun3,mSun4,mStar,mMeteor,mMoon,mRain1,mRain2,mRain3,mRain4,mFog02,mIce,mWaterdrop,mFrostE,mFrostF,mSnow1,mSnow2,mSnow3,mSnow4,mNightcover,mSkyFlash,mLightning1,mLightning2,mLightning3,mCloudLightA1,mCloudLightA2,mCloudLightA3,mCloudLightB1,mCloudLightB2,mCloudLightB3,mWindmillWing,mWindmillWingBlur,mWindmillCenter1,mWindmillCenter2,mWindmillPillar1,mWindmillPillar2,mWindmillPillarFlip1,mWindmillPillarFlip2,mLand01,mLand02,mLand03,mLand04,mLand05,mLand06,mLand07,mLand08,mLand09,mLawn01,mLawn02,mLawn03,mLawn04,mLawn05};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        if (mRaindrop1 != null) GLES20.glDeleteTextures(mRaindrop1.length, mRaindrop1, 0);
        if (mRaindrop2 != null) GLES20.glDeleteTextures(mRaindrop2.length, mRaindrop2, 0);
    }

    private void drawObjects() {
        if (isPreview()) mScene.mOffset = 1.25f;

        GroundRenderer.GroundTextures groundTextures = mGroundRenderer.selectTextures(
                mScene.mCondition, mScene.mIsNight,
                mLand01,mLand02,mLand03,mLand04,mLand05,mLand06,mLand07,mLand08,mLand09,
                mLawn01,mLawn02,mLawn03,mLawn04,mLawn05);

        boolean clearOn = mScene.mWeatherFlagManager.isClearOn();
        boolean rainOn = mScene.mWeatherFlagManager.isRainOn();
        boolean snowOn = mScene.mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mScene.mWeatherFlagManager.isThunderOn();

        clearOn = mSkyRenderer.drawSkyAndCelestial(mSkyDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mFrameCnt, mScene.mOffset, mScene.mLandscape, mScene.mFillScaleY,
                mSky01,mSky02,mSky03,mSky04,mSky04,mSkyStars,
                mSun1,mSun2,mSun3,mMoon,mStar,mMeteor, clearOn, SkyRenderer.Config.WINDMILL);

        if (mScene.mIsNight && (mScene.mCondition == com.reandroid.weather.WeatherCondition.D3_DREARY
                || mScene.mCondition == com.reandroid.weather.WeatherCondition.D4_FOG
                || mScene.mCondition == com.reandroid.weather.WeatherCondition.D5_RAIN_SHOWERS
                || mScene.mCondition == com.reandroid.weather.WeatherCondition.D6_THUNDERSTORMS
                || mScene.mCondition == com.reandroid.weather.WeatherCondition.D9_SLEET)) {
            mSpriteDrawer.drawSprite(mNightcover, (-1.5f) + ((1.5f - mScene.mOffset) * 5.0f), -2.3f, -29.9f,
                    2.0f * mScene.mLandscape, 2.0f * mScene.mFillScaleY, 0.0f, 1.0f);
        }

        thunderOn = mCloudRenderer.drawClouds(mCloudDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mFrameCnt, mScene.mOffset, mScene.mLandscape,
                mCloudA01,mCloudA02,mCloudA03,mCloudB01,mCloudB02,mCloudB03,
                mCloudLightA1,mCloudLightA2,mCloudLightA3,mCloudLightB1,mCloudLightB2,mCloudLightB3,
                thunderOn, CloudRenderer.Config.WINDMILL);

        mWindmillRenderer.drawByDistance(mWindmillDrawer, 2, mScene.mFrameCnt, mScene.mOffset,
                mScene.mLandscape, mScene.mIsNight,
                mWindmillWing,mWindmillWingBlur,mWindmillCenter1,mWindmillCenter2,
                mWindmillPillar1,mWindmillPillar2,mWindmillPillarFlip1,mWindmillPillarFlip2);

        mGroundRenderer.drawFarLand(mGroundDrawer, groundTextures.farLand, mScene.mOffset, mScene.mLandscape);

        mWindmillRenderer.drawByDistance(mWindmillDrawer, 1, mScene.mFrameCnt, mScene.mOffset,
                mScene.mLandscape, mScene.mIsNight,
                mWindmillWing,mWindmillWingBlur,mWindmillCenter1,mWindmillCenter2,
                mWindmillPillar1,mWindmillPillar2,mWindmillPillarFlip1,mWindmillPillarFlip2);

        mGroundRenderer.drawNearLand(mGroundDrawer, groundTextures.nearLand, mScene.mOffset, mScene.mLandscape);

        mWindmillRenderer.drawByDistance(mWindmillDrawer, 0, mScene.mFrameCnt, mScene.mOffset,
                mScene.mLandscape, mScene.mIsNight,
                mWindmillWing,mWindmillWingBlur,mWindmillCenter1,mWindmillCenter2,
                mWindmillPillar1,mWindmillPillar2,mWindmillPillarFlip1,mWindmillPillarFlip2);

        mGroundRenderer.drawLawn(mGroundDrawer, groundTextures.lawn, mScene.mOffset, mScene.mLandscape);

        clearOn = mSkyRenderer.drawSunlight(mSkyDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mFrameCnt, mScene.mOffset, mScene.mLandscape, mSun4, clearOn, SkyRenderer.Config.WINDMILL);

        mFogIceRenderer.drawFogIce(mFogIceDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mLandscape, mFog02, mFog02, mIce, FogIceRenderer.Config.WINDMILL);

        rainOn = drawRain(rainOn);
        snowOn = drawSnow(snowOn);
        thunderOn = drawThunder(thunderOn);

        mScene.mWeatherFlagManager.setClearOn(clearOn);
        mScene.mWeatherFlagManager.setRainOn(rainOn);
        mScene.mWeatherFlagManager.setSnowOn(snowOn);
        mScene.mWeatherFlagManager.setThunderOn(thunderOn);
    }

    private boolean drawRain(boolean rainOn) {
        return mPrecipitationRenderer.drawRain(mPrecipitationDrawer, mScene.mCondition,
                mScene.mFrameCnt, mScene.mLandscape, mScene.mFillScaleY,
                mRain1,mRain2,mRain3,mRain4,0,mWaterdrop,mRaindrop1,mRaindrop2,
                rainOn, PrecipitationRenderer.Config.WINDMILL);
    }

    private boolean drawSnow(boolean snowOn) {
        if (mScene.mCondition == com.reandroid.weather.WeatherCondition.D7_FLURRIES_SNOW
                || mScene.mCondition == com.reandroid.weather.WeatherCondition.D9_SLEET) {
            int frostTex = mScene.mCondition == com.reandroid.weather.WeatherCondition.D9_SLEET ? mFrostF : mFrostE;
            if (mScene.mCondition == com.reandroid.weather.WeatherCondition.D9_SLEET) {
                mSpriteDrawer.drawSprite(frostTex, -0.1f, -0.5f, -20.3f, 0.65f * mScene.mLandscape, 1.05f, 0.0f, 1.0f);
            } else {
                mSpriteDrawer.drawSprite(frostTex, 0.0f, -0.5f, -20.3f, 0.63f * mScene.mLandscape, 1.05f, 0.0f, 1.0f);
            }
        }
        return mPrecipitationRenderer.drawSnow(mPrecipitationDrawer, mScene.mCondition,
                mScene.mFrameCnt, mScene.mOffset, mScene.mLandscape,
                mSnow1,mSnow2,mSnow3,mSnow4, snowOn, PrecipitationRenderer.Config.WINDMILL);
    }

    private boolean drawThunder(boolean thunderOn) {
        return mThunderRenderer.drawThunder(mThunderDrawer, mScene.mCondition,
                mScene.mFrameCnt, mScene.mOffset, mScene.mLandscape,
                mSkyFlash,mLightning1,mLightning2,mLightning3, thunderOn, ThunderRenderer.Config.WINDMILL);
    }

    
    }
