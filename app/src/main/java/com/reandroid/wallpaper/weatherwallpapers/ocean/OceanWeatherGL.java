package com.reandroid.wallpaper.weatherwallpapers.ocean;

import android.opengl.GLES20;
import android.opengl.Matrix;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.GLTextureUtils;
import com.reandroid.weather.WeatherCondition;
import com.reandroid.gles.RawResourceLoader;
import com.reandroid.wallpaper.weatherwallpapers.AnimationController;
import com.reandroid.wallpaper.weatherwallpapers.CloudRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FogIceRenderer;
import com.reandroid.wallpaper.weatherwallpapers.FrostRenderer;
import com.reandroid.wallpaper.weatherwallpapers.PrecipitationRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SkyRenderer;
import com.reandroid.wallpaper.weatherwallpapers.SpriteDrawer;
import com.reandroid.wallpaper.weatherwallpapers.ThunderRenderer;
import com.reandroid.wallpaper.weatherwallpapers.WeatherFlagManager;
import com.reandroid.wallpaper.weatherwallpapers.WeatherStateManager;

import androidx.preference.PreferenceManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class OceanWeatherGL extends GLESScene {
    private static final float FOV = 45.0f;

    private final float[] mProjectionMatrix = new float[16];
    private final SpriteDrawer mSpriteDrawer = new SpriteDrawer();

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mSamplerHandle;

    private boolean mGlReady = false;

    private float mOffset = 1.25f;
    private float mLandscape = 1.0f;
    private float mFillScaleY = 1.0f;

    private final AnimationController mAnimationController = new AnimationController(1600);

    private WeatherCondition mCondition = WeatherCondition.D1_CLEAR;
    private boolean mIsNight = false;

    private WeatherStateManager mWeatherStateManager;
    private final SkyRenderer mSkyRenderer = new SkyRenderer();
    private final CloudRenderer mCloudRenderer = new CloudRenderer();
    private final OceanWaveRenderer mWaveRenderer = new OceanWaveRenderer();
    private final OceanWaterSurfaceRenderer mWaterSurfaceRenderer = new OceanWaterSurfaceRenderer();
    private final FogIceRenderer mFogIceRenderer = new FogIceRenderer();
    private final PrecipitationRenderer mPrecipitationRenderer = new PrecipitationRenderer();
    private final ThunderRenderer mThunderRenderer = new ThunderRenderer();
    private final FrostRenderer mFrostRenderer = new FrostRenderer();
    private final WeatherFlagManager mWeatherFlagManager = new WeatherFlagManager();
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
            CloudRenderer.Drawer,
            OceanWaveRenderer.Drawer,
            OceanWaterSurfaceRenderer.Drawer,
            FogIceRenderer.Drawer,
            PrecipitationRenderer.Drawer,
            ThunderRenderer.Drawer,
            FrostRenderer.Drawer {
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
        public void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                            float scaleX, float scaleY, float rotation, float alpha) {
            mSpriteDrawer.drawSpriteRectOneToFour(texture, x, y, z, scaleX, scaleY, rotation, alpha);
        }

        @Override
        public void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z,
                                                  float scaleX, float scaleY, float rotation,
                                                  float r, float g, float b, float alpha) {
            mSpriteDrawer.drawSpriteColoredRectOneToTwo(texture, x, y, z, scaleX, scaleY, rotation, r, g, b, alpha);
        }
    }

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

    public OceanWeatherGL(int width, int height) {
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
            : 40.0f;
        mAnimationController.setFrameDurationMs(frameDuration);
        mAnimationController.advanceFrame(deltaMs);
        mWeatherFlagManager.update(mCondition, mIsNight);

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

        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(quadVertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(quadVertices).position(0);

        FloatBuffer rectOneToTwoBuffer = ByteBuffer.allocateDirect(rectOneToTwoVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        rectOneToTwoBuffer.put(rectOneToTwoVertices).position(0);

        FloatBuffer rectOneToFourBuffer = ByteBuffer.allocateDirect(rectOneToFourVertices.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        rectOneToFourBuffer.put(rectOneToFourVertices).position(0);

        FloatBuffer texBuffer = ByteBuffer.allocateDirect(quadTex.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texBuffer.put(quadTex).position(0);

        mSpriteDrawer.configure(
            mProgram,
            mPositionHandle,
            mTexCoordHandle,
            mMatrixHandle,
            mColorHandle,
            mSamplerHandle,
            mProjectionMatrix,
            vertexBuffer,
            texBuffer,
            rectOneToTwoBuffer,
            rectOneToFourBuffer
        );

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
        mSkyRenderer.initMemory();
        mCloudRenderer.initMemory();
        mPrecipitationRenderer.initMemory();
        mThunderRenderer.initMemory();

    }

    private void drawObjects() {
        if (isPreview()) {
            mOffset = 1.25f;
        }
        int frameCnt = mAnimationController.getFrameCount();
        boolean clearOn = mWeatherFlagManager.isClearOn();
        boolean rainOn = mWeatherFlagManager.isRainOn();
        boolean snowOn = mWeatherFlagManager.isSnowOn();
        boolean thunderOn = mWeatherFlagManager.isThunderOn();

        clearOn = mSkyRenderer.drawSkyAndCelestial(
            mSkyDrawer,
                mCondition,
                mIsNight,
                frameCnt,
                mOffset,
                mLandscape,
            mFillScaleY,
                mSkyA,
                mSkyB,
                mSkyC,
                mSkyD,
                mSkyG,
                mSkyStars,
                mSun1,
                mSun2,
                mSun3,
                mMoon,
                mStar,
                mMeteor,
                clearOn
        );

            thunderOn = mCloudRenderer.drawClouds(
            mCloudDrawer,
            mCondition,
            mIsNight,
            frameCnt,
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
            thunderOn
        );
        mWaveRenderer.drawWaves(
            mWaveDrawer,
            frameCnt,
            mOffset,
            mLandscape,
            mWaveBack,
            mWave
        );
        clearOn = mSkyRenderer.drawSunlight(
            mSkyDrawer,
            mCondition,
            mIsNight,
            frameCnt,
            mOffset,
            mLandscape,
            mSun4,
            clearOn
        );
        mWaterSurfaceRenderer.drawWaterCover(
                mWaterDrawer,
                mCondition,
                mIsNight,
                mOffset,
                mLandscape,
                mWatercover1,
                mWatercover2,
                mWatercover3,
                mWatercover4,
                mNightcover,
                mCapCover
        );
        mFogIceRenderer.drawFogIce(
            mFogIceDrawer,
                mCondition,
                mIsNight,
                mLandscape,
                mFog01,
                mFog02,
            mIce,
            FogIceRenderer.Config.OCEAN
        );
        rainOn = mPrecipitationRenderer.drawRain(
                mPrecipitationDrawer,
                mCondition,
            frameCnt,
                mLandscape,
                mFillScaleY,
                mRain1,
                mRain2,
                mRain3,
                mRain4,
                mCloudcover,
                mWaterdrop,
                mRaindrop1,
                mRaindrop2,
                rainOn
        );
            mFrostRenderer.drawFrost(
                mFrostDrawer,
                mCondition,
                mLandscape,
                mFrostC,
                mFrostE,
                mFrostF
            );
            snowOn = mPrecipitationRenderer.drawSnow(
                mPrecipitationDrawer,
                mCondition,
            frameCnt,
                mOffset,
                mLandscape,
                mSnow1,
                mSnow2,
                mSnow3,
                mSnow4,
                snowOn
        );
            thunderOn = mThunderRenderer.drawThunder(
                mThunderDrawer,
                mCondition,
                frameCnt,
                mOffset,
                mLandscape,
                mSkyFlash,
                mLightning1,
                mLightning2,
                mLightning3,
                thunderOn
            );

            mWeatherFlagManager.setClearOn(clearOn);
            mWeatherFlagManager.setRainOn(rainOn);
            mWeatherFlagManager.setSnowOn(snowOn);
            mWeatherFlagManager.setThunderOn(thunderOn);
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
