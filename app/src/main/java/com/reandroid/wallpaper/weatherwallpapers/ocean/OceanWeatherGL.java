package com.reandroid.wallpaper.weatherwallpapers.ocean;

import android.opengl.GLES20;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.utils.GLTextureUtils;
import com.reandroid.wallpaper.weatherwallpapers.AnimationController;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FrostRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SpriteDrawer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class OceanWeatherGL extends GLESScene {

    private final Context mContext;

    // ---- 场景逻辑层（非 GL）----
    private final OceanWeatherScene mScene;

    private final SpriteDrawer mSpriteDrawer = new SpriteDrawer();

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mSamplerHandle;

    private boolean mGlReady = false;

    private final SkyRenderer mSkyRenderer = new SkyRenderer();
    private final CloudRenderer mCloudRenderer = new CloudRenderer();
    private final OceanWaveRenderer mWaveRenderer = new OceanWaveRenderer();
    private final OceanWaterSurfaceRenderer mWaterSurfaceRenderer = new OceanWaterSurfaceRenderer();
    private final FogIceRenderer mFogIceRenderer = new FogIceRenderer();
    private final PrecipitationRenderer mPrecipitationRenderer = new PrecipitationRenderer();
    private final ThunderRenderer mThunderRenderer = new ThunderRenderer();
    private final FrostRenderer mFrostRenderer = new FrostRenderer();
    private final DrawerBridge mDrawerBridge = new DrawerBridge();
    private final SkyRenderer.Drawer mSkyDrawer = mDrawerBridge;
    private final CloudRenderer.Drawer mCloudDrawer = mDrawerBridge;
    private final OceanWaveRenderer.Drawer mWaveDrawer = mDrawerBridge;
    private final OceanWaterSurfaceRenderer.Drawer mWaterDrawer = mDrawerBridge;
    private final FogIceRenderer.Drawer mFogIceDrawer = mDrawerBridge;
    private final PrecipitationRenderer.Drawer mPrecipitationDrawer = mDrawerBridge;
    private final ThunderRenderer.Drawer mThunderDrawer = mDrawerBridge;
    private final FrostRenderer.Drawer mFrostDrawer = mDrawerBridge;

    private final class DrawerBridge implements SkyRenderer.Drawer,
            CloudRenderer.Drawer, OceanWaveRenderer.Drawer, OceanWaterSurfaceRenderer.Drawer,
            FogIceRenderer.Drawer, PrecipitationRenderer.Drawer, ThunderRenderer.Drawer,
            FrostRenderer.Drawer {
        @Override public void drawSprite(int t, float x, float y, float z, float sx, float sy, float r, float a) {
            mSpriteDrawer.drawSprite(t, x, y, z, sx, sy, r, a);
        }
        @Override public void drawSpriteRectOneToTwo(int t, float x, float y, float z, float sx, float sy, float r, float a) {
            mSpriteDrawer.drawSpriteRectOneToTwo(t, x, y, z, sx, sy, r, a);
        }
        @Override public void drawSpriteRectOneToFour(int t, float x, float y, float z, float sx, float sy, float r, float a) {
            mSpriteDrawer.drawSpriteRectOneToFour(t, x, y, z, sx, sy, r, a);
        }
        @Override public void drawSpriteColoredRectOneToTwo(int t, float x, float y, float z, float sx, float sy, float r, float cr, float cg, float cb, float a) {
            mSpriteDrawer.drawSpriteColoredRectOneToTwo(t, x, y, z, sx, sy, r, cr, cg, cb, a);
        }
    }

    private int mSkyA, mSkyB, mSkyC, mSkyD, mSkyG, mSkyStars;
    private int mCloudA01, mCloudA02, mCloudA03, mCloudB01, mCloudB02, mCloudB03;
    private int mCloudLightA1, mCloudLightA2, mCloudLightA3, mCloudLightB1, mCloudLightB2, mCloudLightB3;
    private int mSun1, mSun2, mSun3, mSun4, mStar, mMeteor, mMoon;
    private int mWatercover1, mWatercover2, mWatercover3, mWatercover4, mNightcover, mCapCover;
    private int mFog01, mFog02, mIce;
    private int mRain1, mRain2, mRain3, mRain4, mWaterdrop, mCloudcover;
    private int mFrostC, mFrostE, mFrostF;
    private int mSnow1, mSnow2, mSnow3, mSnow4;
    private int mSkyFlash, mLightning1, mLightning2, mLightning3;
    private int mWaveBack;
    private int[] mWave;
    private int[] mRaindrop1, mRaindrop2;

    public OceanWeatherGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new OceanWeatherScene();
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        mSkyRenderer.initMemory();
        mCloudRenderer.initMemory();
        mPrecipitationRenderer.initMemory();
        mThunderRenderer.initMemory();
        Context appContext = com.reandroid.gles.GLESWallpaper.getAppContext();
        mScene.onCreate(appContext);
    }

    @Override
    public void start() { mScene.start(isPreview()); }

    @Override
    public void stop() { mScene.stop(); }

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
        if (!mGlReady) initGl();

        long deltaMs = mScene.mAnimationController.updateDelta(timeMs);

        WeatherStateManager wsm = mScene.mWeatherStateManager;
        if (wsm != null) {
            wsm.update(timeMs, isPreview());
            mScene.mCondition = wsm.getCondition();
            mScene.mIsNight = wsm.isNight();
        }

        float frameDuration = wsm != null && wsm.shouldFastAnimate() ? 16.666f : 40.0f;
        mScene.mAnimationController.setFrameDurationMs(frameDuration);
        mScene.mAnimationController.advanceFrame(deltaMs);
        mScene.mWeatherFlagManager.update(mScene.mCondition, mScene.mIsNight);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawObjects();
    }

    @Override
    public void release() {
        if (!mGlReady) return;
        int[] textures = {mSkyA,mSkyB,mSkyC,mSkyD,mSkyG,mSkyStars,mCloudA01,mCloudA02,mCloudA03,mCloudB01,mCloudB02,mCloudB03,mCloudLightA1,mCloudLightA2,mCloudLightA3,mCloudLightB1,mCloudLightB2,mCloudLightB3,mSun1,mSun2,mSun3,mSun4,mStar,mMeteor,mMoon,mWatercover1,mWatercover2,mWatercover3,mWatercover4,mNightcover,mCapCover,mFog01,mFog02,mIce,mRain1,mRain2,mRain3,mRain4,mWaterdrop,mCloudcover,mFrostC,mFrostE,mFrostF,mSnow1,mSnow2,mSnow3,mSnow4,mSkyFlash,mLightning1,mLightning2,mLightning3,mWaveBack};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        if (mWave != null) GLES20.glDeleteTextures(mWave.length, mWave, 0);
        if (mRaindrop1 != null) GLES20.glDeleteTextures(mRaindrop1.length, mRaindrop1, 0);
        if (mRaindrop2 != null) GLES20.glDeleteTextures(mRaindrop2.length, mRaindrop2, 0);
    }

    private void initGl() {
        String vs = AssetLoader.readText(mContext, "ocean/shaders/GLES/ocean_weather_vs.glsl");
        String fs = AssetLoader.readText(mContext, "ocean/shaders/GLES/ocean_weather_fs.glsl");
        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        float[] quadVertices = AssetLoader.readFloatArray(mContext, "ocean/data/ocean_quad_vertices.csv");
        float[] rectOneToTwoVertices = AssetLoader.readFloatArray(mContext, "ocean/data/ocean_rect_one_to_two_vertices.csv");
        float[] rectOneToFourVertices = AssetLoader.readFloatArray(mContext, "ocean/data/ocean_rect_one_to_four_vertices.csv");
        float[] quadTex = AssetLoader.readFloatArray(mContext, "ocean/data/ocean_quad_tex.csv");

        FloatBuffer vb = ByteBuffer.allocateDirect(quadVertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(quadVertices).position(0);
        FloatBuffer r12b = ByteBuffer.allocateDirect(rectOneToTwoVertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        r12b.put(rectOneToTwoVertices).position(0);
        FloatBuffer r14b = ByteBuffer.allocateDirect(rectOneToFourVertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        r14b.put(rectOneToFourVertices).position(0);
        FloatBuffer tb = ByteBuffer.allocateDirect(quadTex.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tb.put(quadTex).position(0);

        mSpriteDrawer.configure(mProgram, mPositionHandle, mTexCoordHandle, mMatrixHandle,
                mColorHandle, mSamplerHandle, mScene.mProjectionMatrix, vb, tb, r12b, r14b);

        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_BLEND);
        mScene.updateProjection(mWidth, mHeight);
        loadTextures();
        mGlReady = true;
    }

    private void loadTextures() {
        mSkyA = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_sky.jpg");
        mSkyB = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/b_sky.jpg");
        mSkyC = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/c_sky.jpg");
        mSkyD = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/d_sky.png");
        mSkyG = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/g_sky.jpg");
        mSkyStars = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/d_sky_stars.png");
        mCloudA01 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_01.png");
        mCloudA02 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_02.png");
        mCloudA03 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_03.png");
        mCloudB01 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_01.png");
        mCloudB02 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_02.png");
        mCloudB03 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_03.png");
        mCloudLightA1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_light_01.png");
        mCloudLightA2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_light_02.png");
        mCloudLightA3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_a_light_03.png");
        mCloudLightB1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_light_01.png");
        mCloudLightB2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_light_02.png");
        mCloudLightB3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/cloud_b_light_03.png");
        mSun1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/a_sun_01.png");
        mSun2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/a_sun_02.png");
        mSun3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/a_sun_03.png");
        mSun4 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/ocean_a_sun_04.png");
        mStar = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/d_star.png");
        mMeteor = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/ocean_d_meteor.png");
        mMoon = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/ocean_d_moon.png");
        mWatercover1 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_watercover_01.png");
        mWatercover2 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_watercover_02.png");
        mWatercover3 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_watercover_03.png");
        mWatercover4 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_watercover_04.png");
        mNightcover = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/nightcover_01.png");
        mCapCover = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/a_cap_01.png");
        mFog01 = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/fog_01.png");
        mFog02 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/fog_02.png");
        mIce = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/ice.png");
        mRain1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/c_rain_01.png");
        mRain2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/c_rain_02.png");
        mRain3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/c_rain_03.png");
        mRain4 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/c_rain_04.png");
        mWaterdrop = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/c_waterdrop.png");
        mCloudcover = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/ocean_c_cloudcover.png");
        mFrostC = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/c_frost.png");
        mFrostE = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/e_frost.png");
        mFrostF = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/f_frost.png");
        mSnow1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/e_snow_01.png");
        mSnow2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/e_snow_02.png");
        mSnow3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/e_snow_03.png");
        mSnow4 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/e_snow_04.png");
        mSkyFlash = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/g_sky_flash.png");
        mLightning1 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/g_lightning_01.png");
        mLightning2 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/g_lightning_02.png");
        mLightning3 = GLTextureUtils.loadTextureFromAsset(mContext, "weatherwallpapers/common/drawable/g_lightning_03.png");
        mWaveBack = GLTextureUtils.loadTextureFromAsset(mContext, "ocean/drawable/sand.png");

        mWave = new int[32];
        for (int i = 0; i < 32; i++) {
            mWave[i] = GLTextureUtils.loadTextureFromAsset(mContext, String.format("ocean/drawable/sea_a_%02d.png", i+1));
        }
        mRaindrop1 = new int[25];
        mRaindrop2 = new int[25];
        for (int i = 0; i < 25; i++) {
            mRaindrop1[i] = GLTextureUtils.loadTextureFromAsset(mContext, String.format("weatherwallpapers/common/drawable/waterdrop_a_%d.png", i));
            mRaindrop2[i] = GLTextureUtils.loadTextureFromAsset(mContext, String.format("weatherwallpapers/common/drawable/waterdrop_b_%d.png", i));
        }
    }

    private void drawObjects() {
        if (isPreview()) mScene.mOffset = 1.25f;
        int frameCnt = mScene.mAnimationController.getFrameCount();
        boolean clearOn = mScene.mWeatherFlagManager.isClearOn();
        boolean rainOn = mScene.mWeatherFlagManager.isRainOn();
        boolean snowOn = mScene.mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mScene.mWeatherFlagManager.isThunderOn();

        clearOn = mSkyRenderer.drawSkyAndCelestial(mSkyDrawer, mScene.mCondition, mScene.mIsNight,
                frameCnt, mScene.mOffset, mScene.mLandscape, mScene.mFillScaleY,
                mSkyA,mSkyB,mSkyC,mSkyD,mSkyG,mSkyStars,mSun1,mSun2,mSun3,mMoon,mStar,mMeteor,clearOn);

        thunderOn = mCloudRenderer.drawClouds(mCloudDrawer, mScene.mCondition, mScene.mIsNight,
                frameCnt, mScene.mOffset, mScene.mLandscape,
                mCloudA01,mCloudA02,mCloudA03,mCloudB01,mCloudB02,mCloudB03,
                mCloudLightA1,mCloudLightA2,mCloudLightA3,mCloudLightB1,mCloudLightB2,mCloudLightB3,thunderOn);

        mWaveRenderer.drawWaves(mWaveDrawer, frameCnt, mScene.mOffset, mScene.mLandscape, mWaveBack, mWave);

        clearOn = mSkyRenderer.drawSunlight(mSkyDrawer, mScene.mCondition, mScene.mIsNight,
                frameCnt, mScene.mOffset, mScene.mLandscape, mSun4, clearOn);

        mWaterSurfaceRenderer.drawWaterCover(mWaterDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mOffset, mScene.mLandscape,
                mWatercover1,mWatercover2,mWatercover3,mWatercover4,mNightcover,mCapCover);

        mFogIceRenderer.drawFogIce(mFogIceDrawer, mScene.mCondition, mScene.mIsNight,
                mScene.mLandscape, mFog01, mFog02, mIce, FogIceRenderer.Config.OCEAN);

        rainOn = mPrecipitationRenderer.drawRain(mPrecipitationDrawer, mScene.mCondition,
                frameCnt, mScene.mLandscape, mScene.mFillScaleY,
                mRain1,mRain2,mRain3,mRain4,mCloudcover,mWaterdrop,mRaindrop1,mRaindrop2,rainOn);

        mFrostRenderer.drawFrost(mFrostDrawer, mScene.mCondition, mScene.mLandscape, mFrostC, mFrostE, mFrostF);

        snowOn = mPrecipitationRenderer.drawSnow(mPrecipitationDrawer, mScene.mCondition,
                frameCnt, mScene.mOffset, mScene.mLandscape, mSnow1,mSnow2,mSnow3,mSnow4,snowOn);

        thunderOn = mThunderRenderer.drawThunder(mThunderDrawer, mScene.mCondition,
                frameCnt, mScene.mOffset, mScene.mLandscape,
                mSkyFlash,mLightning1,mLightning2,mLightning3,thunderOn);

        mScene.mWeatherFlagManager.setClearOn(clearOn);
        mScene.mWeatherFlagManager.setRainOn(rainOn);
        mScene.mWeatherFlagManager.setSnowOn(snowOn);
        mScene.mWeatherFlagManager.setThunderOn(thunderOn);
    }

    private int createShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

}
