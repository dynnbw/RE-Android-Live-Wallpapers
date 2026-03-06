package com.reandroid.wallpaper.grass;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;
import android.os.SystemClock;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;
import com.reandroid.wallpaper.gles.GLESWallpaper;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Grass Wallpaper - OpenGL ES 2.0 Implementation (RenderScript-accurate)
 */
public class GrassGL extends GLESScene {
    // 复原MTK原版粒子模式
    private boolean mLegacyParticles = false;
    private static final int LEGACY_MAX_NORMAL = 10;
    private static final int LEGACY_MAX_EXTRAS = 50;
    private static final float LEGACY_SPEED = 0.1f / 100f; // 原版粒子速度（像素/毫秒）
    private static final float LEGACY_SPEED_VARIANCE = 0.3f / 1000f; // 速度随机波动范围（±30%）
    private static final int LEGACY_MAX_DELAY = 5000; // 0-5秒生成延迟
    private static final int LEGACY_MAX_STAY = 5000; // 0-5秒停留时间
    private static final int LEGACY_MAX_FLARE = 1000; // 1秒
    private static final int LEGACY_MAX_INTERVAL = 5000; // 5秒
    private static final float LEGACY_INTERVAL_VARIANCE = 0.3f; // 间隔随机波动范围（±30%）
    private static final int LEGACY_MAX_BLOW_INTERVAL = 180000; // 3分钟
    private static final int LEGACY_TYPE_DANDELION = 0; // 蒲公英
    private static final int LEGACY_TYPE_FIREFLY = 1; // 萤火虫
    private int legacyType = LEGACY_TYPE_DANDELION;
    private int legacyDirection = 0;
    private int legacyBlowTime = 0;
    private long legacyNow = 0;
    private LegacyParticle[] legacyNormal = new LegacyParticle[LEGACY_MAX_NORMAL];
    private LegacyParticle[] legacyExtras = new LegacyParticle[LEGACY_MAX_EXTRAS];

    private static class LegacyParticle {
        int type; // 0蒲公英 1萤火虫
        boolean active;
        float angle;
        int bladeNum;
        int sizeNum;
        int texture; // 0蒲公英 1/2萤火虫
        long startTime;
        long stayEndTime;
        long silentEndTime;
        long flareEndTime;
        float originX, originY;
        float dx, dy;

        // 显式构造函数，修复默认构造函数未定义问题
        public LegacyParticle() {
            // 初始化默认值
            this.type = 0;
            this.active = false;
            this.angle = 0.0f;
            this.bladeNum = -1;
            this.sizeNum = -1;
            this.texture = 0;
            this.startTime = 0;
            this.stayEndTime = 0;
            this.silentEndTime = 0;
            this.flareEndTime = 0;
            this.originX = 0.0f;
            this.originY = 0.0f;
            this.dx = 0.0f;
            this.dy = 0.0f;
        }
    } // 修复：闭合 LegacyParticle 内部类

    private static final String TAG = "GrassGL";

    private static final float TINT_STRENGTH = 0.6f; // 草地颜色调节强度

    private static final int DEFAULT_BLADE_COUNT = 200; // 默认草叶数量
    private static final float TESSELATION = 0.5f; // 草叶细分程度
    private static final float HALF_TESSELATION = 0.25f; // 草叶细分程度一半
    private static final float MAX_BEND = 0.09f; // 草叶最大弯曲程度
    private static final float SECONDS_IN_DAY = 86400.0f; // 一天的秒数
    private static final float HALF_PI = 1.570796326f; // π/2

    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;

    private final int[] p = new int[B + B + 2];
    private final float[][] g2 = new float[B + B + 2][2];

    private Resources mResources;
    private final Random mRandom = new Random(System.currentTimeMillis());

    private final Location mLocation = new Location("grass_wallpaper");
    private TimeZone mTimeZone = TimeZone.getDefault();
    private SunCalculator mSunCalculator;

    private float mDawn;
    private float mMorning;
    private float mAfternoon;
    private float mDusk;

    private float mXOffset = 0.0f;// 当前的水平偏移（0.0 - 1.0）

    private boolean mInitialized = false;
    private boolean mGLInitialized = false;

    private int mBackgroundProgram;
    private int mSkyProgram;
    private int mGrassProgram;
    private int mMoonProgram;

    private int mBgPositionHandle;
    private int mBgTexHandle;
    private int mBgMatrixHandle;
    private int mBgAlphaHandle;
    private int mBgSamplerHandle;

    private int mSkyPositionHandle;
    private int mSkyTexHandle;
    private int mSkyMatrixHandle;
    private int mSkySamplerNightHandle;
    private int mSkySamplerSunriseHandle;
    private int mSkySamplerSunsetHandle;
    private int mSkySamplerSkyHandle;
    private int mSkyWeightNightHandle;
    private int mSkyWeightSunriseHandle;
    private int mSkyWeightSunsetHandle;
    private int mSkyWeightSkyHandle;
    private int mSkyNightInvertHandle;

    private int mGrassPositionHandle;
    private int mGrassColorHandle;
    private int mGrassTexHandle;
    private int mGrassMatrixHandle;
    private int mGrassSamplerHandle;

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

    private int mTexNight;
    private int mTexSunrise;
    private int mTexSunset;
    private int mTexSky;
    private int mTexSun;
    private int mTexAA;
    private int mTexDandelion;
    private int mTexFirefly;
    private int mTexFirefly1;
    private int mTexFirefly2;
    private int mTexMoonBase;
    private int mTexMoonMask;

    private Blade[] mBlades;
    private int[] mBladeSizes;
    private int mBladeCount = DEFAULT_BLADE_COUNT;
    private int mVertexCount;
    private int mIndexCount;

    private FloatBuffer mGrassVertexBuffer;
    private ShortBuffer mGrassIndexBuffer;
    private FloatBuffer mSpriteBuffer;
    private FloatBuffer mSkyQuadBuffer;
    private FloatBuffer mMoonBuffer;

    private final float[] mProjectionMatrix = new float[16]; // 投影矩阵
    private final float[] mMVPMatrix = new float[16]; // Model-View-Projection 矩阵

    private long mLastSunUpdateMs = 0L; // 上次太阳位置更新时间

    private boolean mGrassEnabled = true; // 是否显示草地
    private boolean mNightInvert = false; // 夜晚天空是否反转
    private boolean mNightDesaturateGrass = false; // 夜晚草地去色
    private boolean mUseAccurateSun = false; // 是否使用精确日出日落时间
    private boolean mSunEnabled = false; // 是否显示太阳
    private boolean mMoonEnabled = false; // 是否显示月亮
    private float mGrassHeightScale = 1.0f; // 草地高度缩放
    private float mGrassWidthScale = 1.0f; // 草地宽度缩放
    private boolean mUseGrassTint = false; // 是否启用草地颜色调节
    private float mGrassTintR = 1.0f; // 草地颜色调节 RGB 分量
    private float mGrassTintG = 1.0f; // 草地颜色调节 RGB 分量
    private float mGrassTintB = 1.0f; // 草地颜色调节 RGB 分量
    private float mGrassTintH = 0.0f; // 草地颜色调节 HSB 分量
    private float mGrassTintS = 0.0f; // 草地颜色调节 HSB 分量
    private float mGrassTintV = 1.0f; // 草地颜色调节 HSB 分量
    private int mSettingsHash = 0; // 用于检测设置更改

    private final float[] mAccurateWeights = new float[] { 1.0f, 0.0f, 0.0f, 0.0f }; // 夜晚、日出、日落、白天权重
    private long mLastWeightUpdateMs = 0L; // 上次权重更新时间
    private double mLastSunAltitude = 0.0; // 上次太阳高度角
    private double mLastSunriseHour = -1.0; // 精确日出日落时间
    private double mLastSunsetHour = -1.0; // 精确日出日落时间
    private double mLastSunriseOfficialHour = -1.0; // 官方日出日落时间
    private double mLastSunsetOfficialHour = -1.0; // 官方日出日落时间
    private long mLastLocationUpdateMs = 0L; // 上次位置更新时间

    private static final int DEFAULT_DANDELION_COUNT = 10; // 蒲公英数量
    private static final int DEFAULT_FIREFLY_COUNT = 16; // 萤火虫数量
    private static final float DANDELION_SIZE_SCALE = 2.2f; // 蒲公英大小
    private static final float FIREFLY_SIZE_SCALE = 6.0f; // 萤火虫大小
    private static final float DANDELION_SPEED_SCALE = 1.6f; // 蒲公英速度
    private Dandelion[] mDandelions; // 蒲公英
    private Firefly[] mFireflies; // 萤火虫
    private int mDandelionCount = DEFAULT_DANDELION_COUNT; // 蒲公英数量
    private int mFireflyCount = DEFAULT_FIREFLY_COUNT; // 萤火虫数量
    private float mDandelionSpeedScale = 1.0f; // 蒲公英速度缩放
    private long mLastAnimTimeMs = 0L; // 上次动画更新时间
    private boolean mDandelionEnabled = false; // 是否启用蒲公英（默认关闭）
    private boolean mFireflyEnabled = false; // 是否启用萤火虫（默认关闭）

    private static class Blade {
        float angle;
        int size;
        float xPos;
        float yPos;
        float offset;
        float scale;
        float lengthX;
        float lengthY;
        float hardness;
        float h;
        float s;
        float b;
        float turbulencex;
    }

    private static class Dandelion {
        float x;
        float y;
        float speed;
        float size;
        float swayPhase;
        float swaySpeed;
        float rotationDeg;
    }

    private static class Firefly {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        float phase;
        float flickerSpeed;
    }

    // 显式构造函数，调用父类构造函数
    public GrassGL(int width, int height) {
        super(width, height);
        mLocation.setLatitude(37.7749f);
        mLocation.setLongitude(-122.4194f);
    }

    public void setResources(Resources resources) {
        mResources = resources;
    }

    @Override
    protected void onCreate() {
        if (mInitialized)
            return;
        mInitialized = true;

        initNoise();
        updateSettingsFromPrefs();
        initBlades();
        buildBladeIndexBuffer();
        initDandelions();
        initFireflies();

        mSunCalculator = new SunCalculator(mLocation, mTimeZone.getID());
        updateSunTimes();

        // Match RenderScript's ortho window (origin at top-left, Y increases downward)
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        // 修复：非静态调用 isPreview() 方法
        mXOffset = this.isPreview() ? 0.5f : 0.0f;
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] {
                mTexNight, mTexSunrise, mTexSunset, mTexSky,
                mTexSun, mTexAA, mTexDandelion, mTexFirefly,
                mTexMoonBase, mTexMoonMask
        };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexNight = 0;
        mTexSunrise = 0;
        mTexSunset = 0;
        mTexSky = 0;
        mTexSun = 0;
        mTexAA = 0;
        mTexDandelion = 0;
        mTexFirefly = 0;
        mTexMoonBase = 0;
        mTexMoonMask = 0;

        // 释放着色器程序
        if (mBackgroundProgram != 0) {
            GLES20.glDeleteProgram(mBackgroundProgram);
            mBackgroundProgram = 0;
        }
        if (mSkyProgram != 0) {
            GLES20.glDeleteProgram(mSkyProgram);
            mSkyProgram = 0;
        }
        if (mGrassProgram != 0) {
            GLES20.glDeleteProgram(mGrassProgram);
            mGrassProgram = 0;
        }
        if (mMoonProgram != 0) {
            GLES20.glDeleteProgram(mMoonProgram);
            mMoonProgram = 0;
        }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // Match RenderScript's ortho window (origin at top-left, Y increases downward)
        Matrix.orthoM(mProjectionMatrix, 0, 0, width, height, 0, -1.0f, 1.0f);
        updateBlades();
        initDandelions();
        initFireflies();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    private void initGL() {
        if (mGLInitialized)
            return;
        mGLInitialized = true;

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
        mSkyWeightNightHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightNight");
        mSkyWeightSunriseHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSunrise");
        mSkyWeightSunsetHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSunset");
        mSkyWeightSkyHandle = GLES20.glGetUniformLocation(mSkyProgram, "uWeightSky");
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
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0)
            return 0;

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

    private void loadTextures() {
        mTexNight = loadTexture(R.drawable.night, true, false);
        mTexSunrise = loadTexture(R.drawable.sunrise, true, false);
        mTexSunset = loadTexture(R.drawable.sunset, true, false);
        mTexSky = loadTexture(R.drawable.sky, true, false);
        mTexSun = loadTexture(R.drawable.sun, false, false);
        mTexAA = createAlphaTexture();
        mTexDandelion = loadTexture(R.drawable.dandelion, false, false);
        mTexFirefly = loadTexture(R.drawable.firefly, false, false);
        mTexFirefly1 = loadTexture(R.drawable.firefly1, false, false);
        mTexFirefly2 = loadTexture(R.drawable.firefly2, false, false);
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
        float cx = (size - 1) * 0.5f;
        float cy = (size - 1) * 0.5f;
        float radius = size * 0.5f - 1.0f;
        float edge = radius * 0.08f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - cx;
                float dy = y - cy;
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
        if (mipmap) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        }
        bitmap.recycle();
        return tex[0];
    }

    private int createAlphaTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST_MIPMAP_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);

        byte[] mip0 = new byte[] { 0, (byte) 255, (byte) 255, 0 };
        byte[] mip1 = new byte[] { 64, 64 };
        byte[] mip2 = new byte[] { 0 };

        ByteBuffer b0 = ByteBuffer.allocateDirect(mip0.length).order(ByteOrder.nativeOrder());
        b0.put(mip0).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 4, 1, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE,
                b0);

        ByteBuffer b1 = ByteBuffer.allocateDirect(mip1.length).order(ByteOrder.nativeOrder());
        b1.put(mip1).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 1, GLES20.GL_ALPHA, 2, 1, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE,
                b1);

        ByteBuffer b2 = ByteBuffer.allocateDirect(mip2.length).order(ByteOrder.nativeOrder());
        b2.put(mip2).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 2, GLES20.GL_ALPHA, 1, 1, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE,
                b2);

        return tex[0];
    }

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
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
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
        int bx0, bx1, by0, by1;
        float rx0, rx1, ry0, ry1, sx, sy, a, b, u, v;

        float t = x + N;
        bx0 = ((int) t) & BM;
        bx1 = (bx0 + 1) & BM;
        rx0 = t - (int) t;
        rx1 = rx0 - 1.0f;

        t = y + N;
        by0 = ((int) t) & BM;
        by1 = (by0 + 1) & BM;
        ry0 = t - (int) t;
        ry1 = ry0 - 1.0f;

        int i = p[bx0];
        int j = p[bx1];

        int b00 = p[i + by0];
        int b10 = p[j + by0];
        int b01 = p[i + by1];
        int b11 = p[j + by1];

        sx = noiseSCurve(rx0);
        sy = noiseSCurve(ry0);

        u = rx0 * g2[b00][0] + ry0 * g2[b00][1];
        v = rx1 * g2[b10][0] + ry0 * g2[b10][1];
        a = mix(u, v, sx);

        u = rx0 * g2[b01][0] + ry1 * g2[b01][1];
        v = rx1 * g2[b11][0] + ry1 * g2[b11][1];
        b = mix(u, v, sx);

        return 1.5f * mix(a, b, sy);
    }

    private float noiseSCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private void initBlades() {
        mBlades = new Blade[mBladeCount];
        mBladeSizes = new int[mBladeCount];

        for (int i = 0; i < mBladeCount; i++) {
            Blade blade = new Blade();
            createBlade(blade);
            mBlades[i] = blade;
            mBladeSizes[i] = blade.size;
        }
    }

    private void updateBlades() {
        for (Blade blade : mBlades) {
            float xpos = random(-mWidth, mWidth);
            blade.xPos = xpos;
            blade.turbulencex = xpos * 0.006f;
            blade.yPos = mHeight;
        }
    }

    private void updateSettingsFromPrefs() {
        // 读取原版粒子开关
        boolean legacy = com.reandroid.wallpaper.settings.WallpaperSettings.getBoolean("pref_grass_legacy_particles",
                false);
        if (legacy != mLegacyParticles) {
            mLegacyParticles = legacy;
            if (mLegacyParticles) {
                initLegacyParticles();
            }
        }
        com.reandroid.wallpaper.settings.WallpaperSettings.GrassTint tint = com.reandroid.wallpaper.settings.WallpaperSettings
                .getGrassTint();

        int newBladeCount = com.reandroid.wallpaper.settings.WallpaperSettings
                .getGrassBladeCount(DEFAULT_BLADE_COUNT);
        boolean newEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isGrassEnabled(true);
        boolean newNightInvert = com.reandroid.wallpaper.settings.WallpaperSettings
                .isNightInvert(false);
        boolean newNightDesaturate = com.reandroid.wallpaper.settings.WallpaperSettings
                .isGrassNightDesaturateEnabled(false);
        boolean newAccurateSun = com.reandroid.wallpaper.settings.WallpaperSettings
                .isAccurateSunEnabled(false);
        boolean newSunEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isSunEnabled(true);
        boolean newMoonEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isMoonEnabled(true);
        float newHeightScale = com.reandroid.wallpaper.settings.WallpaperSettings
                .getGrassHeightScale(1.0f);
        float newWidthScale = com.reandroid.wallpaper.settings.WallpaperSettings
                .getGrassWidthScale(1.0f);
        boolean newDandelionEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isDandelionEnabled(false);
        boolean newFireflyEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isFireflyEnabled(false);
        int newDandelionCount = com.reandroid.wallpaper.settings.WallpaperSettings
                .getDandelionCount(DEFAULT_DANDELION_COUNT);
        int newFireflyCount = com.reandroid.wallpaper.settings.WallpaperSettings
                .getFireflyCount(DEFAULT_FIREFLY_COUNT);
        float newDandelionSpeedScale = com.reandroid.wallpaper.settings.WallpaperSettings
                .getDandelionSpeedScale(2.0f);

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
        hash = 31 * hash + (newDandelionEnabled ? 1 : 0);
        hash = 31 * hash + (newFireflyEnabled ? 1 : 0);
        hash = 31 * hash + newDandelionCount;
        hash = 31 * hash + newFireflyCount;
        hash = 31 * hash + Float.floatToIntBits(newDandelionSpeedScale);
        hash = 31 * hash + (tint.enabled ? 1 : 0);
        hash = 31 * hash + tint.color;

        if (hash == mSettingsHash) {
            return;
        }

        mSettingsHash = hash;
        mGrassEnabled = newEnabled;
        mNightInvert = newNightInvert;
        mNightDesaturateGrass = newNightDesaturate;
        mUseAccurateSun = newAccurateSun;
        mSunEnabled = newSunEnabled;
        mMoonEnabled = newMoonEnabled;
        mGrassHeightScale = newHeightScale;
        mGrassWidthScale = newWidthScale;
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
            buildBladeIndexBuffer();
        }

        if (!mLegacyParticles) {
            if (mDandelions == null || mDandelions.length != mDandelionCount) {
                initDandelions();
            }
            if (mFireflies == null || mFireflies.length != mFireflyCount) {
                initFireflies();
            }
        }
    }

    // 复原MTK原版粒子初始化
    private void initLegacyParticles() {
        legacyNow = SystemClock.uptimeMillis();
        legacyType = LEGACY_TYPE_DANDELION;
        legacyDirection = 0;
        legacyBlowTime = (int) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            legacyNormal[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
        }
        for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
            legacyExtras[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
            legacyExtras[i].active = true; // 修复：白天extras应全部激活
        }
    }

    private LegacyParticle createLegacyParticle(int type) {
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

    private void flyLegacyFirefly(LegacyParticle p, boolean isInit) {
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

    private void flyLegacyDandelion(LegacyParticle p, boolean isInit) {
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
                // 仅支持西风/东风，其他方向保留原逻辑
                p.originX = 0.0f;
                p.originY = (float) (Math.random() * mHeight);
            }
        }
    }

    private void initDandelions() {
        mDandelions = new Dandelion[mDandelionCount];
        for (int i = 0; i < mDandelionCount; i++) {
            Dandelion d = new Dandelion();
            resetDandelion(d, true);
            mDandelions[i] = d;
        }
    }

    private void resetDandelion(Dandelion d, boolean randomX) {
        float size = random(32.0f, 72.0f) * DANDELION_SIZE_SCALE;
        d.size = size;
        d.y = random(0.1f * mHeight, 0.9f * mHeight);
        d.speed = random(20.0f, 60.0f);
        d.swayPhase = random(0.0f, 6.28318f);
        d.swaySpeed = random(0.6f, 1.4f);
        d.rotationDeg = random(-15.0f, 15.0f);
        if (randomX) {
            d.x = random(-mWidth, 0.0f) - size;
        } else {
            d.x = -size - random(0.0f, mWidth * 0.2f);
        }
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

    private void buildBladeIndexBuffer() {
        mVertexCount = 0;
        mIndexCount = 0;
        for (int size : mBladeSizes) {
            mIndexCount += size * 2 * 3;
            mVertexCount += size + 2;
        }

        int vertexTotal = mVertexCount * 2; // 2 vertices per segment
        int stride = 8; // x,y + r,g,b,a + s,t

        mGrassVertexBuffer = ByteBuffer.allocateDirect(vertexTotal * stride * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        short[] idx = new short[mIndexCount];
        int idxIdx = 0;
        int vtxIdx = 0;
        for (int size : mBladeSizes) {
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

    private float random(float range) {
        return mRandom.nextFloat() * range;
    }

    private float random(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    private float timeFraction() {
        if (!isPreview()) {
            Calendar now = Calendar.getInstance(mTimeZone);
            return (now.get(Calendar.HOUR_OF_DAY) * 3600.0f
                    + now.get(Calendar.MINUTE) * 60.0f
                    + now.get(Calendar.SECOND)) / SECONDS_IN_DAY;
        }
        float t = (System.currentTimeMillis() % 30000L) / 30000.0f;
        return t - (int) t;
    }

    private void updateSunTimes() {
        // Defaults matching original RS fallback
        float dawn = 0.3f;
        float dusk = 0.75f;

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

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (!mGLInitialized) {
            if (mResources == null)
                return;
            initGL();
        }

        updateSettingsFromPrefs();

        long animNow = SystemClock.uptimeMillis();
        float dt = 0.016f;
        if (mLastAnimTimeMs > 0) {
            dt = (animNow - mLastAnimTimeMs) / 1000.0f;
            dt = clamp(dt, 0.0f, 0.05f);
        }
        mLastAnimTimeMs = animNow;

        // Refresh dawn/dusk periodically to avoid getting stuck on an invalid value
        if (mLastSunUpdateMs == 0L || (System.currentTimeMillis() - mLastSunUpdateMs) > 3600000L) {
            updateSunTimes();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        float x = mix(mWidth, 0.0f, mXOffset);
        float now = timeFraction();

        float newB;
        boolean isNight;
        if (mUseAccurateSun && mSunCalculator != null) {
            updateAccurateWeights();
            GLES20.glUseProgram(mSkyProgram);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniformMatrix4fv(mSkyMatrixHandle, 1, false, mProjectionMatrix, 0);
            drawAccurateBackground();
            // 只有开启精确日出日落时才显示太阳
            if (mSunEnabled && mTexSun != 0) {
                drawSun();
            }
            newB = clamp(mAccurateWeights[3] + 0.6f * (mAccurateWeights[1] + mAccurateWeights[2]), 0.0f, 1.0f);
            isNight = mLastSunAltitude < 0.0;
        } else {
            GLES20.glUseProgram(mBackgroundProgram);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, mProjectionMatrix, 0);
            newB = drawBackground(now);
            isNight = (now < mDawn || now > mDusk);
        }

        drawMoon();

        GLES20.glUseProgram(mGrassProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mGrassMatrixHandle, 1, false, mProjectionMatrix, 0);

        float grassBrightness = newB;
        float nightDesat = 0.0f;
        if (mNightDesaturateGrass) {
            grassBrightness = 1.0f;
            nightDesat = mUseAccurateSun ? mAccurateWeights[0] : clamp(1.0f - newB, 0.0f, 1.0f);
        }
        drawBlades(grassBrightness, x, nightDesat);

        drawSprites(dt, animNow, isNight);
    }

    private void drawSprites(float dt, long animNowMs, boolean isNight) {
        if (mLegacyParticles) {
            drawLegacyParticles(animNowMs, isNight);
            return;
        }
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        GLES20.glUseProgram(mBackgroundProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, mProjectionMatrix, 0);

        if (!isNight && mDandelionEnabled && mTexDandelion != 0 && mDandelions != null) {
            for (Dandelion d : mDandelions) {
                d.x += d.speed * DANDELION_SPEED_SCALE * mDandelionSpeedScale * dt;
                float sway = (float) Math.sin(d.swayPhase + animNowMs * 0.001f * d.swaySpeed) * 6.0f;
                float drawX = d.x;
                float drawY = d.y + sway;
                if (drawX > mWidth + d.size) {
                    resetDandelion(d, false);
                    drawX = d.x;
                }
                drawSprite(mTexDandelion, drawX, drawY, d.size, 0.9f, true, d.rotationDeg);
            }
        }

        if (isNight && mFireflyEnabled && mTexFirefly != 0 && mFireflies != null) {
            float time = animNowMs * 0.001f;
            for (Firefly f : mFireflies) {
                f.x += f.vx * dt;
                f.y += f.vy * dt;
                if (f.x < 0)
                    f.x = mWidth;
                if (f.x > mWidth)
                    f.x = 0;
                if (f.y < 0)
                    f.y = mHeight;
                if (f.y > mHeight)
                    f.y = 0;

                float flicker = 0.5f + 0.5f * (float) Math.sin(f.phase + time * f.flickerSpeed);
                float alpha = 0.2f + 0.8f * flicker;
                float size = f.size * (0.8f + 0.4f * flicker);
                drawSprite(mTexFirefly, f.x, f.y, size, alpha, false, 0.0f);
            }
        }
    }

    // 复原MTK原版粒子渲染与运动
    private void drawLegacyParticles(long animNowMs, boolean isNight) {
        // 粒子绘制前切换到正确GL状态
        GLES20.glUseProgram(mBackgroundProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, mProjectionMatrix, 0);
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        legacyNow = animNowMs;
        // 风向切换
        if (legacyBlowTime < legacyNow) {
            legacyDirection = (legacyDirection == 0) ? 1 : 0;
            legacyBlowTime = (int) (legacyNow + Math.random() * LEGACY_MAX_BLOW_INTERVAL);
        }
        // 蒲公英/萤火虫切换
        int newType = isNight ? LEGACY_TYPE_FIREFLY : LEGACY_TYPE_DANDELION;
        if (legacyType != newType) {
            legacyType = newType;
            for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
                legacyNormal[i] = createLegacyParticle(legacyType);
                legacyNormal[i].active = true;
            }
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                legacyExtras[i] = createLegacyParticle(legacyType);
                // 修复：切换到蒲公英模式时，extras只在夜晚激活，白天应全部激活
                legacyExtras[i].active = (legacyType == LEGACY_TYPE_DANDELION) ? true : true;
            }
        } else if (legacyType == LEGACY_TYPE_DANDELION) {
            // 修复：白天切换时extras未激活导致蒲公英数量不足
            for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                if (legacyExtras[i] != null) legacyExtras[i].active = true;
            }
        }
        // 更新normal粒子
        for (int i = 0; i < LEGACY_MAX_NORMAL; i++) {
            LegacyParticle p = legacyNormal[i];
            if (p == null) continue;
            if (!p.active) continue;
            long delta = legacyNow - p.startTime;
            boolean outOfBounds = false;
            if (legacyType == LEGACY_TYPE_DANDELION) {
                outOfBounds = (p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0 || p.originY > mHeight);
            } else {
                outOfBounds = (p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0);
            }
            if (outOfBounds) {
                legacyNormal[i] = createLegacyParticle(legacyType);
                legacyNormal[i].active = true;
                continue;
            }
            if (delta > LEGACY_MAX_STAY) {
                if (legacyType == LEGACY_TYPE_DANDELION) {
                    flyLegacyDandelion(p, false);
                } else {
                    flyLegacyFirefly(p, false);
                }
                p.startTime = legacyNow;
            }
            p.originX += p.dx * LEGACY_SPEED * delta;
            p.originY += p.dy * LEGACY_SPEED * delta;
            if (legacyType == LEGACY_TYPE_FIREFLY) {
                if (legacyNow > p.silentEndTime) {
                    p.flareEndTime = legacyNow + LEGACY_MAX_FLARE;
                    p.silentEndTime = legacyNow
                            + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE) * LEGACY_MAX_INTERVAL);
                }
                int tex = mTexFirefly1;
                if (legacyNow < p.flareEndTime) {
                    tex = mTexFirefly2;
                }
                float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + i * 1234) * 0.002);
                float alpha = 0.2f + 0.8f * flicker;
                float size = 72.0f * (0.8f + 0.4f * flicker); // 增大萤火虫尺寸
                drawSprite(tex, p.originX, p.originY, size, alpha, false, 0.0f);
            } else {
                drawSprite(mTexDandelion, p.originX, p.originY, 96.0f, 0.9f, true, p.angle); // 增大蒲公英尺寸
            }
        }

        // 自动生成蒲公英extras粒子（仅白天）
        if (legacyType == LEGACY_TYPE_DANDELION) {
            // 每帧最多激活1个未激活的extras，概率5%
            if (Math.random() < 0.05) {
                for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
                    LegacyParticle p = legacyExtras[i];
                    if (p == null || p.active) continue;
                    legacyExtras[i] = createLegacyParticle(LEGACY_TYPE_DANDELION);
                    legacyExtras[i].active = true;
                    break;
                }
            }
        }
        // 更新extras粒子
        for (int i = 0; i < LEGACY_MAX_EXTRAS; i++) {
            LegacyParticle p = legacyExtras[i];
            if (p == null) continue;
            if (!p.active) continue;
            long delta = legacyNow - p.startTime;
            boolean outOfBounds = false;
            if (legacyType == LEGACY_TYPE_DANDELION) {
                outOfBounds = (p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0 || p.originY > mHeight);
            } else {
                outOfBounds = (p.originX < 0 || p.originX > mWidth * 2 || p.originY < 0);
            }
            if (outOfBounds) {
                legacyExtras[i] = createLegacyParticle(legacyType);
                legacyExtras[i].active = true;
                continue;
            }
            if (delta > LEGACY_MAX_STAY) {
                if (legacyType == LEGACY_TYPE_DANDELION) {
                    flyLegacyDandelion(p, false);
                } else {
                    flyLegacyFirefly(p, false);
                }
                p.startTime = legacyNow;
            }
            p.originX += p.dx * LEGACY_SPEED * delta;
            p.originY += p.dy * LEGACY_SPEED * delta;
            if (legacyType == LEGACY_TYPE_FIREFLY) {
                if (legacyNow > p.silentEndTime) {
                    p.flareEndTime = legacyNow + LEGACY_MAX_FLARE;
                    p.silentEndTime = legacyNow
                            + (long) ((1.0 + (Math.random() * 2 - 1) * LEGACY_INTERVAL_VARIANCE) * LEGACY_MAX_INTERVAL);
                }
                int tex = mTexFirefly1;
                if (legacyNow < p.flareEndTime) {
                    tex = mTexFirefly2;
                }
                float flicker = 0.5f + 0.5f * (float) Math.sin((animNowMs + (i + 100) * 1234) * 0.002);
                float alpha = 0.2f + 0.8f * flicker;
                float size = 48.0f * (0.8f + 0.4f * flicker);
                drawSprite(tex, p.originX, p.originY, size, alpha, false, 0.0f);
            } else {
                drawSprite(mTexDandelion, p.originX, p.originY, 64.0f, 0.9f, true, p.angle);
            }
        }
    }

    private void drawSprite(int texture, float cx, float cy, float size, float alpha, boolean flipV,
            float rotationDeg) {
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

        float[] verts = new float[] {
                x0, y0, 0.0f, v0,
                x1, y1, 0.0f, v1,
                x2, y2, 1.0f, v1,
                x3, y3, 1.0f, v0
        };

        mSpriteBuffer.clear();
        mSpriteBuffer.put(verts).position(0);

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

    private float drawBackground(float now) {
        float newB = 1.0f;
        if (now >= 0.0f && now < mDawn) {
            setAlpha(1.0f);
            drawNight();
            newB = 0.0f;
        } else if (now >= mDawn && now <= mMorning) {
            float half = mDawn + (mMorning - mDawn) * 0.5f;
            if (now <= half) {
                setAlpha(1.0f);
                drawNight();
                newB = normf(mDawn, half, now);
                setAlpha(newB);
                drawSunrise();
            } else {
                setAlpha(1.0f);
                drawSunrise();
                setAlpha(normf(half, mMorning, now));
                drawNoon();
            }
        } else if (now > mMorning && now < mAfternoon) {
            setAlpha(1.0f);
            drawNoon();
        } else if (now >= mAfternoon && now <= mDusk) {
            float half = mAfternoon + (mDusk - mAfternoon) * 0.5f;
            if (now <= half) {
                setAlpha(1.0f);
                drawNoon();
                newB = normf(mAfternoon, half, now);
                setAlpha(newB);
                newB = 1.0f - newB;
                drawSunset();
            } else {
                setAlpha(1.0f);
                drawSunset();
                setAlpha(normf(half, mDusk, now));
                drawNight();
                newB = 0.0f;
            }
        } else if (now > mDusk) {
            setAlpha(1.0f);
            drawNight();
            newB = 0.0f;
        }
        return newB;
    }

    private void setAlpha(float a) {
        GLES20.glUniform1f(mBgAlphaHandle, a);
    }

    private void drawNight() {
        if (mNightInvert) {
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

    private void drawSunrise() {
        drawRect(mTexSunrise);
    }

    private void drawNoon() {
        drawRect(mTexSky);
    }

    private void drawSunset() {
        drawRect(mTexSunset);
    }

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

        float[] verts = new float[] {
                x0, y0, u0, v0,
                x1, y1, u1, v1,
                x2, y2, u2, v2,
                x3, y3, u3, v3
        };

        FloatBuffer buf = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(verts).position(0);

        GLES20.glEnableVertexAttribArray(mBgPositionHandle);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 2, GLES20.GL_FLOAT, false, 16, buf);

        buf.position(2);
        GLES20.glEnableVertexAttribArray(mBgTexHandle);
        GLES20.glVertexAttribPointer(mBgTexHandle, 2, GLES20.GL_FLOAT, false, 16, buf);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mBgSamplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgTexHandle);
    }

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
            mAccurateWeights[0] = 1.0f;
            mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f;
            mAccurateWeights[3] = 0.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = calc.computeSunAltitude(now); // 使用当前时刻的高度角
            return;
        }
        if (noonAlt > 0.0 && midnightAlt > 0.0) {
            mAccurateWeights[0] = 0.0f;
            mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f;
            mAccurateWeights[3] = 1.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = calc.computeSunAltitude(now); // 使用当前时刻的高度角
            return;
        }
        if (sunrise <= 0.0 && sunset <= 0.0) {
            mAccurateWeights[0] = 1.0f;
            mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f;
            mAccurateWeights[3] = 0.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = -90.0;
            return;
        }
        if (sunrise >= 24.0 && sunset >= 24.0) {
            mAccurateWeights[0] = 0.0f;
            mAccurateWeights[1] = 0.0f;
            mAccurateWeights[2] = 0.0f;
            mAccurateWeights[3] = 1.0f;
            mLastWeightUpdateMs = nowMs;
            mLastSunAltitude = 90.0;
            return;
        }

        double altitude = calc.computeSunAltitude(now);
        boolean rising = calc.isSunRising(now);

        // 总是更新太阳高度角，即使跳过权重更新
        mLastSunAltitude = altitude;

        long intervalMs = (altitude >= -6.0 && altitude <= 5.0) ? 30000L : 60000L;
        if (mLastWeightUpdateMs != 0L && (nowMs - mLastWeightUpdateMs) < intervalMs) {
            return;
        }

        float wNight = 0.0f;
        float wSunrise = 0.0f;
        float wSunset = 0.0f;
        float wSky = 0.0f;

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
                wNight = 1.0f - t;
                wSunrise = t;
            } else if (isBetweenClock(nowMinutes, dawnToSunriseEnd, dawnHoldEnd)) {
                wSunrise = 1.0f;
            } else if (isBetweenClock(nowMinutes, dawnHoldEnd, dawnToDayEnd)) {
                float t = clockProgress(nowMinutes, dawnHoldEnd, dawnToDayEnd);
                wSunrise = 1.0f - t;
                wSky = t;
            } else if (isBetweenClock(nowMinutes, duskStart, duskHoldStart)) {
                float t = clockProgress(nowMinutes, duskStart, duskHoldStart);
                wSky = 1.0f - t;
                wSunset = t;
            } else if (isBetweenClock(nowMinutes, duskHoldStart, duskToNightStart)) {
                wSunset = 1.0f;
            } else if (isBetweenClock(nowMinutes, duskToNightStart, duskEnd)) {
                float t = clockProgress(nowMinutes, duskToNightStart, duskEnd);
                wSunset = 1.0f - t;
                wNight = t;
            } else if (isBetweenClock(nowMinutes, dawnToDayEnd, duskStart)) {
                wSky = 1.0f;
            } else {
                wNight = 1.0f;
            }
        } else {
            if (rising) {
                if (altitude <= -6.0) {
                    wNight = 1.0f;
                } else if (altitude <= 0.0) {
                    wNight = 1.0f - (float) ((altitude + 6.0) / 6.0);
                    wSunrise = 1.0f - wNight;
                } else if (altitude <= 5.0) {
                    wSunrise = (float) ((5.0 - altitude) / 5.0);
                    wSky = 1.0f - wSunrise;
                } else {
                    wSky = 1.0f;
                }
            } else {
                if (altitude >= 5.0) {
                    wSky = 1.0f;
                } else if (altitude >= 0.0) {
                    wSky = (float) (altitude / 5.0);
                    wSunset = 1.0f - wSky;
                } else if (altitude >= -6.0) {
                    wSunset = (float) ((altitude + 6.0) / 6.0);
                    wNight = 1.0f - wSunset;
                } else {
                    wNight = 1.0f;
                }
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
        if (start <= end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private float clockProgress(float now, float start, float end) {
        float duration;
        float elapsed;
        if (start <= end) {
            duration = end - start;
            elapsed = now - start;
        } else {
            duration = (1440.0f - start) + end;
            elapsed = now >= start ? (now - start) : (1440.0f - start + now);
        }
        if (duration <= 0.0f)
            return 0.0f;
        return clamp(elapsed / duration, 0.0f, 1.0f);
    }

    private void updateLocationFromSystem(long nowMs) {
        if (!mUseAccurateSun)
            return;
        if (mLastLocationUpdateMs != 0L && (nowMs - mLastLocationUpdateMs) < 300000L) {
            return;
        }
        Context ctx = GLESWallpaper.getAppContext();
        if (ctx == null)
            return;

        boolean hasFine = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(ctx,
                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse)
            return;

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null)
            return;

        Location best = null;
        try {
            Location gps = hasFine ? lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location passive = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            best = pickBestLocation(gps, net);
            best = pickBestLocation(best, passive);
        } catch (SecurityException ignored) {
        }

        if (best != null) {
            mLocation.setLatitude(best.getLatitude());
            mLocation.setLongitude(best.getLongitude());
            mSunCalculator = new SunCalculator(mLocation, mTimeZone.getID());
            mLastLocationUpdateMs = nowMs;
        }
    }

    private Location pickBestLocation(Location a, Location b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return a.getTime() >= b.getTime() ? a : b;
    }

    private void drawAccurateBackground() {
        if (mSkyQuadBuffer == null) {
            mSkyQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        float[] verts = new float[] {
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, mHeight, 0.0f, 1.0f,
                mWidth, mHeight, 1.0f, 1.0f,
                mWidth, 0.0f, 1.0f, 0.0f
        };

        mSkyQuadBuffer.clear();
        mSkyQuadBuffer.put(verts).position(0);

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

        GLES20.glUniform1f(mSkyWeightNightHandle, mAccurateWeights[0]);
        GLES20.glUniform1f(mSkyWeightSunriseHandle, mAccurateWeights[1]);
        GLES20.glUniform1f(mSkyWeightSunsetHandle, mAccurateWeights[2]);
        GLES20.glUniform1f(mSkyWeightSkyHandle, mAccurateWeights[3]);
        GLES20.glUniform1f(mSkyNightInvertHandle, mNightInvert ? 1.0f : 0.0f);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mSkyPositionHandle);
        GLES20.glDisableVertexAttribArray(mSkyTexHandle);
    }

    private void drawSun() {
        if (mSunCalculator == null)
            return;
        if (mSpriteBuffer == null) {
            mSpriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        Calendar now = Calendar.getInstance(mTimeZone);
        float sunX;
        double sunrise = mLastSunriseOfficialHour;
        double sunset = mLastSunsetOfficialHour;
        if (sunrise >= 0.0 && sunrise < 24.0 && sunset > 0.0 && sunset <= 24.0 && sunset > sunrise) {
            float nowHours = now.get(Calendar.HOUR_OF_DAY)
                    + now.get(Calendar.MINUTE) / 60.0f// 当前时间分钟数
                    + now.get(Calendar.SECOND) / 3600.0f;// 当前时间小时数
            float ratio = clamp((nowHours - (float) sunrise) / (float) (sunset - sunrise), -0.1f, 1.1f);// 太阳X位置
            float ratioX = ratio * 1.4f - 0.1f;
            sunX = mWidth * clamp(ratioX, -0.1f, 1.1f);
        } else {
            double hourAngleDeg = mSunCalculator.computeHourAngle(now);
            float ratioX = clamp((float) ((hourAngleDeg + 180.0) / 360.0), -0.1f, 1.1f);// 太阳X位置
            float ratioExtended = ratioX * 1.4f - 0.1f;
            sunX = mWidth * clamp(ratioExtended, -0.1f, 1.1f);
        }
        float clampedAlt = clamp((float) mLastSunAltitude, 0.0f, 90.0f);// 太阳高度角
        float sunY = mHeight * (1.0f - clampedAlt / 90.0f);// 太阳Y位置

        float size = mWidth * 0.32f; // 太阳大小
        float alpha = clamp((float) ((mLastSunAltitude + 6.0) / 12.0), 0.0f, 1.0f);// 太阳透明度

        GLES20.glUseProgram(mBackgroundProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mBgMatrixHandle, 1, false, mProjectionMatrix, 0);// 设置矩阵
        drawSprite(mTexSun, sunX, sunY, size, alpha, false, 0.0f);// 绘制太阳
    }

    private void drawMoon() {
        if (!mUseAccurateSun || !mMoonEnabled) {
            return;
        }
        if (mMoonProgram == 0 || mTexMoonBase == 0 || mTexMoonMask == 0) {
            return;
        }
        Calendar now = Calendar.getInstance(mTimeZone);
        double lat = mLocation.getLatitude();
        double lon = mLocation.getLongitude();
        MoonCalculator.MoonData data = MoonCalculator.compute(now, lat, lon);

        if (data == null)
            return;
        if (data.moonAltitudeDeg <= -2.0)
            return;

        double sunAlt = mUseAccurateSun ? mLastSunAltitude : data.sunAltitudeDeg;
        boolean isDaytime = sunAlt > 0.0;
        float baseBrightness = clamp((float) ((data.moonAltitudeDeg + 2.0) / 30.0), 0.0f, 1.0f);
        float brightness = baseBrightness;
        float moonAlpha = 1.0f;
        float contrast = 1.0f;
        float saturation = 1.0f;
        float blueTint = 0.0f;

        MoonEclipse eclipse = computeMoonEclipse(data);

        float ratioX = (float) ((data.moonHourAngleDeg + 180.0) / 360.0);
        float moonX = mWidth * clamp(ratioX, -0.1f, 1.1f);
        float clampedAlt = clamp((float) data.moonAltitudeDeg, 0.0f, 90.0f);
        float moonY = mHeight * (1.0f - clampedAlt / 90.0f);
        float size = mWidth * 0.24f;

        GLES20.glUseProgram(mMoonProgram);
        if (isDaytime) {
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR);
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }
        GLES20.glUniformMatrix4fv(mMoonMatrixHandle, 1, false, mProjectionMatrix, 0);
        GLES20.glUniform1f(mMoonPhaseHandle, (float) data.phaseAngleUtcDeg);
        GLES20.glUniform1f(mMoonBrightnessHandle, brightness);
        GLES20.glUniform1f(mMoonAlphaHandle, moonAlpha);
        GLES20.glUniform1i(mMoonIsDaytimeHandle, isDaytime ? 1 : 0);
        GLES20.glUniform1f(mMoonContrastHandle, contrast);
        GLES20.glUniform1f(mMoonSaturationHandle, saturation);
        GLES20.glUniform1f(mMoonBlueTintHandle, blueTint);
        GLES20.glUniform1i(mMoonEclipseTypeHandle, eclipse.type);
        GLES20.glUniform1f(mMoonEclipseFractionHandle, eclipse.fraction);
        GLES20.glUniform1f(mMoonEclipsePhaseHandle, eclipse.phase);
        GLES20.glUniform2f(mMoonShadowOffsetHandle, eclipse.shadowOffsetX, eclipse.shadowOffsetY);
        GLES20.glUniform3f(mMoonShadowColorHandle, 0.6f, 0.2f, 0.1f);
        GLES20.glUniform3f(mMoonPenumbraColorHandle, 0.2f, 0.2f, 0.2f);

        drawMoonSprite(moonX, moonY, size);
    }

    private void drawMoonSprite(float cx, float cy, float size) {
        if (mMoonBuffer == null) {
            mMoonBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        float half = size * 0.5f;
        float x0 = cx - half;
        float y0 = cy - half;
        float x1 = cx + half;
        float y1 = cy + half;

        float[] verts = new float[] {
                x0, y0, 0.0f, 0.0f,
                x0, y1, 0.0f, 1.0f,
                x1, y1, 1.0f, 1.0f,
                x1, y0, 1.0f, 0.0f
        };

        mMoonBuffer.clear();
        mMoonBuffer.put(verts).position(0);

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

    private double computeDaytimeContrast(MoonCalculator.MoonData data, double sunAltDeg) {
        if (sunAltDeg <= 0.0)
            return 1.0;
        double deltaLambdaRad = Math.toRadians(data.phaseAngleUtcDeg);
        double moonMag = 0.23 + 12.5 * (1.0 - Math.cos(deltaLambdaRad)) / 2.0
                - 0.02 * data.moonAltitudeDeg;

        double sunMag = -26.7;
        double cosAlt = Math.cos(Math.toRadians(sunAltDeg));
        double skyFactor = Math.max(1e-4, 1.0 - cosAlt);
        double skyMag = -2.5 * Math.log10(Math.pow(10.0, -0.4 * sunMag) * skyFactor);

        return Math.max(0.0, (skyMag - moonMag) / 10.0);
    }

    private static class MoonEclipse {
        final int type;
        final float fraction;
        final float phase;
        final float shadowOffsetX;
        final float shadowOffsetY;

        MoonEclipse(int type, float fraction, float phase, float shadowOffsetX, float shadowOffsetY) {
            this.type = type;
            this.fraction = fraction;
            this.phase = phase;
            this.shadowOffsetX = shadowOffsetX;
            this.shadowOffsetY = shadowOffsetY;
        }
    }

    private MoonEclipse computeMoonEclipse(MoonCalculator.MoonData data) {
        double delta = Math.abs(data.phaseAngleUtcDeg - 180.0);
        if (delta > 10.0) {
            return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        double beta = Math.abs(data.moonLatitudeUtcDeg);
        if (beta > 10.2) {
            return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        double umbra = (1.0 - beta) / 0.27;
        double penumbra = (1.5 - beta) / 0.27;
        float pen = clamp((float) penumbra, 0.0f, 1.0f);
        float umb = clamp((float) umbra, 0.0f, 1.0f);
        float total = clamp((float) (umbra - 1.0), 0.0f, 1.0f);
        double phaseRad = Math.toRadians(data.phaseAngleUtcDeg);

        float basePhase;
        if (umbra <= 0.0) {
            basePhase = pen * 0.25f;
        } else if (umbra < 1.0) {
            basePhase = 0.25f + umb * 0.35f;
        } else {
            basePhase = 0.60f + total * 0.20f;
        }
        float phase = basePhase;
        float easedPhase = smoothstep(0.0f, 1.0f, phase);
        float offsetMag = mix(1.6f, -1.6f, easedPhase);
        float dirX = Math.sin(phaseRad) >= 0.0 ? 1.0f : -1.0f;
        float offX = dirX * offsetMag;
        float offY = 0.0f;

        if (umbra > 1.0) {
            return new MoonEclipse(3, (float) umbra, phase, offX, offY);
        }
        if (umbra > 0.0) {
            return new MoonEclipse(2, (float) umbra, phase, offX, offY);
        }
        if (penumbra > 0.0) {
            return new MoonEclipse(1, clamp((float) penumbra, 0.0f, 1.0f), phase, offX, offY);
        }
        return new MoonEclipse(0, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    private float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private void drawBlades(float brightness, float xOffset, float nightDesat) {
        if (!mGrassEnabled) {
            return;
        }
        if (mGrassVertexBuffer == null || mGrassIndexBuffer == null)
            return;

        mGrassVertexBuffer.clear();
        float now = SystemClock.uptimeMillis() * 0.00004f;

        for (Blade blade : mBlades) {
            appendBladeVertices(blade, brightness, xOffset, now, nightDesat, mGrassVertexBuffer);
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
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mIndexCount, GLES20.GL_UNSIGNED_SHORT, mGrassIndexBuffer);

        GLES20.glDisableVertexAttribArray(mGrassPositionHandle);
        GLES20.glDisableVertexAttribArray(mGrassColorHandle);
        GLES20.glDisableVertexAttribArray(mGrassTexHandle);
    }

    private void appendBladeVertices(Blade blade, float brightness, float xOffset, float now,
            float nightDesat, FloatBuffer out) {
        float scale = blade.scale * mGrassWidthScale;
        float angle = blade.angle;
        float xpos = blade.xPos + xOffset;
        int size = blade.size;

        float h = blade.h;
        float s = blade.s;
        float v = mix(0.0f, blade.b, brightness);
        if (mUseGrassTint) {
            h = mGrassTintH;
            s = mGrassTintS;
            v = clamp(v * mGrassTintV, 0.0f, 1.0f);
        }
        if (mNightDesaturateGrass && nightDesat > 0.0f) {
            s = mix(s, 0.0f, clamp(nightDesat, 0.0f, 1.0f));
        }
        int color = hsbToRgb(h, s, v);
        float r = Color.red(color) / 255.0f;
        float g = Color.green(color) / 255.0f;
        float b = Color.blue(color) / 255.0f;

        float newAngle = (turbulencef2(blade.turbulencex, now, 4.0f) - 0.5f) * 0.5f;
        angle = clamp(angle + (newAngle + blade.offset - angle) * 0.15f, -MAX_BEND, MAX_BEND);

        float currentAngle = HALF_PI;
        float bottomX = xpos;
        float bottomY = blade.yPos;
        float d = angle * blade.hardness;

        float si = size * scale;
        float bottomLeft = bottomX - si;
        float bottomRight = bottomX + si;
        float bottom = bottomY + HALF_TESSELATION;

        putVertex(out, bottomLeft, bottom, r, g, b, 1.0f, 0.0f, 0.0f);
        putVertex(out, bottomRight, bottom, r, g, b, 1.0f, 1.0f, 0.0f);

        for (; size > 0; size -= 1) {
            float lengthX = blade.lengthX * mGrassHeightScale;
            float lengthY = blade.lengthY * mGrassHeightScale;
            float topX = bottomX - (float) Math.cos(currentAngle) * lengthX;
            float topY = bottomY - (float) Math.sin(currentAngle) * lengthY;

            si = (float) size * scale;
            float spi = si - scale;
            float topLeft = topX - spi;
            float topRight = topX + spi;

            putVertex(out, topLeft, topY, r, g, b, 1.0f, 0.0f, 0.0f);
            putVertex(out, topRight, topY, r, g, b, 1.0f, 1.0f, 0.0f);

            bottomX = topX;
            bottomY = topY;
            currentAngle += d;
        }

        blade.angle = angle;
    }

    private void putVertex(FloatBuffer out, float x, float y, float r, float g, float b, float a, float s, float t) {
        out.put(x);
        out.put(y);
        out.put(r);
        out.put(g);
        out.put(b);
        out.put(a);
        out.put(s);
        out.put(t);
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    private float normf(float start, float stop, float value) {
        return (value - start) / (stop - start);
    }

    private int hsbToRgb(float h, float s, float b) {
        float red = 0.0f;
        float green = 0.0f;
        float blue = 0.0f;

        float hf = (h - (int) h) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = b * (1.0f - s);
        float qv = b * (1.0f - s * f);
        float tv = b * (1.0f - s * (1.0f - f));

        switch (ihf) {
            case 0:
                red = b;
                green = tv;
                blue = pv;
                break;
            case 1:
                red = qv;
                green = b;
                blue = pv;
                break;
            case 2:
                red = pv;
                green = b;
                blue = tv;
                break;
            case 3:
                red = pv;
                green = qv;
                blue = b;
                break;
            case 4:
                red = tv;
                green = pv;
                blue = b;
                break;
            case 5:
                red = b;
                green = pv;
                blue = qv;
                break;
        }

        return Color.argb(255, (int) (red * 255), (int) (green * 255), (int) (blue * 255));
    }

    private float[] rgbToHsb(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float h;
        if (delta == 0.0f) {
            h = 0.0f;
        } else if (max == r) {
            h = ((g - b) / delta) % 6.0f;
        } else if (max == g) {
            h = ((b - r) / delta) + 2.0f;
        } else {
            h = ((r - g) / delta) + 4.0f;
        }
        h /= 6.0f;
        if (h < 0.0f)
            h += 1.0f;

        float s = max == 0.0f ? 0.0f : (delta / max);
        float v = max;

        return new float[] { h, s, v };
    }
}