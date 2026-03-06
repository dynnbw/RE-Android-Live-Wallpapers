package com.reandroid.wallpaper.nexus;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Nexus动态壁纸 - 基于RenderScript（nexus.rs）移植的OpenGL ES 2.0实现
 * 100%还原原版视觉效果，包含纹理脉冲、光晕和背景的渲染逻辑
 */
public class NexusGL extends GLESScene {
    // 日志标签
    private static final String TAG = "NexusGL";

    // 默认值常量
    private static final int DEFAULT_MAX_PULSES = 20;
    private static final int DEFAULT_MAX_EXTRAS = 40;
    private static final int DEFAULT_PULSE_SIZE = 14;
    private static final int DEFAULT_GLOW_SIZE = 64;
    private static final float DEFAULT_SPEED = 0.2f;
    private static final float DEFAULT_SPEED_DELTA_MIN = 0.7f;
    private static final float DEFAULT_SPEED_DELTA_MAX = 1.7f;
    private static final int DEFAULT_TRAIL_SIZE = 40;
    private static final int DEFAULT_MAX_DELAY = 2000;

    // 可配置参数（从SharedPreferences加载）
    private int MAX_PULSES = DEFAULT_MAX_PULSES;
    private int MAX_EXTRAS = DEFAULT_MAX_EXTRAS;
    private int PULSE_SIZE = DEFAULT_PULSE_SIZE;
    private int HALF_PULSE_SIZE = DEFAULT_PULSE_SIZE / 2;
    private int GLOW_SIZE = DEFAULT_GLOW_SIZE;
    private int HALF_GLOW_SIZE = DEFAULT_GLOW_SIZE / 2;
    private float SPEED = DEFAULT_SPEED;
    private float SPEED_DELTA_MIN = DEFAULT_SPEED_DELTA_MIN;
    private float SPEED_DELTA_MAX = DEFAULT_SPEED_DELTA_MAX;
    private int TRAIL_SIZE = DEFAULT_TRAIL_SIZE;
    private int MAX_DELAY = DEFAULT_MAX_DELAY;

    // 常规脉冲类型
    private static final int PULSE_NORMAL = 0;
    // 额外脉冲类型（点击触发）
    private static final int PULSE_EXTRA = 1;

    // 纹理UV坐标数据（从raw加载）
    private float[] mUv0;
    private float[] mUv90;
    private float[] mUv180;
    private float[] mUv270;

    // 随机数生成器
    private final Random mRandom = new Random();
    // OpenGL初始化完成标记
    private boolean mGLInitialized;
    // 上一帧渲染时间（毫秒）
    private long mLastTimeMs;

    // 初始宽高（创建时的尺寸）
    private int mInitialWidth;
    private int mInitialHeight;
    // 世界坐标系X/Y轴缩放比例（适配屏幕尺寸）
    private float mWorldScaleX = 1.0f;
    private float mWorldScaleY = 1.0f;
    // 壁纸横向偏移量（多屏滑动时）
    private float mXOffset;
    // 是否旋转（横屏/竖屏判断）
    private boolean mRotate;
    // 壁纸显示模式（配色模式）
    private int mMode;

    // OpenGL着色器程序句柄
    private int mProgram;
    // 顶点位置属性句柄
    private int mPositionHandle;
    // 纹理坐标属性句柄
    private int mTexCoordHandle;
    // MVP矩阵统一变量句柄
    private int mMatrixHandle;
    // 颜色统一变量句柄
    private int mColorHandle;
    // 纹理采样器句柄
    private int mTextureHandle;

    // 背景纹理ID
    private int mTexBackground;
    // 脉冲纹理ID
    private int mTexPulse;
    // 光晕纹理ID
    private int mTexGlow;

    // 背景设置缓存
    private String mLastBackgroundUri;
    private String mLastBackgroundPreset;
    private float mBackgroundAspect = 1.0f;

    // 顶点数据缓冲区
    private FloatBuffer mVertexBuffer;
    // 纹理坐标缓冲区
    private FloatBuffer mTexCoordBuffer;

    // 投影矩阵（正交投影）
    private final float[] mProjectionMatrix = new float[16];
    // 模型矩阵（模型变换）
    private final float[] mModelMatrix = new float[16];
    // MVP矩阵（投影*视图*模型）
    private final float[] mMVPMatrix = new float[16];

    // 当前渲染颜色（RGBA）
    private final float[] mColor = new float[4];

    /**
     * 脉冲实体类
     * 存储单个脉冲的位置、速度、颜色、状态等属性
     */
    private static class Pulse {
        // 脉冲类型（常规/额外）
        int pulseType;
        // 初始X坐标
        float originX;
        // 初始Y坐标
        float originY;
        // 颜色索引（0-3对应不同配色）
        int color;
        // 开始时间（毫秒）
        int startTime;
        // X轴移动增量
        float dx;
        // Y轴移动增量
        float dy;
        // 缩放系数
        float scale;
        // 激活状态（0=未激活，1=激活）
        int active;
    }

    // 常规脉冲数组
    private Pulse[] mPulses;
    // 额外脉冲数组（点击触发）
    private Pulse[] mExtras;

    /**
     * 构造方法
     * @param width 初始宽度
     * @param height 初始高度
     */
    public NexusGL(int width, int height) {
        super(width, height);
        mInitialWidth = width;
        mInitialHeight = height;
        mLastTimeMs = SystemClock.uptimeMillis();
    }

    /**
     * 场景创建回调（GLESScene生命周期）
     * 延迟初始化OpenGL相关资源
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 延迟GL初始化");
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBackground, mTexPulse, mTexGlow };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBackground = 0;
        mTexPulse = 0;
        mTexGlow = 0;

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    /**
     * 设置壁纸偏移量（多屏滑动时调用）
     * @param xOffset 横向偏移比例
     * @param yOffset 纵向偏移比例（未使用）
     * @param xPixels 横向偏移像素值（未使用）
     * @param yPixels 纵向偏移像素值（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    /**
     * 屏幕尺寸变化回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (width > 0 && height > 0) {
            // 计算缩放比例，适配不同屏幕尺寸
            mWorldScaleX = (float) mInitialWidth / width;
            mWorldScaleY = (float) mInitialHeight / height;
            // 更新投影矩阵
            updateProjection();
        }
    }

    /**
     * 壁纸指令处理（点击/触摸等）
     * @param action 指令类型（如点击、二次点击）
     * @param x 点击X坐标
     * @param y 点击Y坐标
     * @param z 额外参数（未使用）
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        if (WallpaperManager.COMMAND_TAP.equals(action)
                || WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)
                || WallpaperManager.COMMAND_DROP.equals(action)) {
            // 竖屏时修正X坐标（适配偏移）
            if (mWidth < mHeight) {
                x = (int) (x + (mXOffset * mWidth / mWorldScaleX));
            }
            // 添加点击触发的脉冲
            addTap(x, y);
        }
    }

    /**
     * 绘制每一帧
     * @param timeMs 当前时间（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        // 初始化OpenGL资源
        initGL();
        if (!mGLInitialized) {
            return;
        }

        // 背景设置变化时重新加载纹理
        checkAndReloadBackground();

        // 判断是否横屏（宽>高则旋转渲染）
        mRotate = mWidth > mHeight;
        // 设置视口大小
        GLES20.glViewport(0, 0, mWidth, mHeight);
        // 清空颜色缓冲区（黑色背景）
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 绘制背景
        drawBackground();

        // 获取当前时间，用于计算脉冲动画进度
        int now = (int) SystemClock.uptimeMillis();
        // 绘制常规脉冲
        drawPulses(mPulses, MAX_PULSES, now);
        // 绘制额外脉冲（点击触发）
        drawPulses(mExtras, MAX_EXTRAS, now);

        // 更新上一帧时间
        mLastTimeMs = timeMs;
    }

    /**
     * 初始化OpenGL相关资源
     * 包括着色器程序、纹理、脉冲数据等
     */
    private void initGL() {
        if (mGLInitialized || mResources == null) {
            return;
        }
        mGLInitialized = true;

        // 禁用深度测试（2D渲染不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合（实现透明/光晕效果）
        GLES20.glEnable(GLES20.GL_BLEND);

        // 加载可配置参数
        loadParameters();
        // 创建着色器程序
        createProgram();
        // 加载纹理资源
        loadTextures();
        // 加载UV坐标
        mUv0 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_0);
        mUv90 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_90);
        mUv180 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_180);
        mUv270 = RawResourceLoader.readRawFloatArray(mResources, R.raw.nexus_uv_270);
        // 初始化脉冲数据
        initPulses();
        // 更新投影矩阵
        updateProjection();
        // 加载壁纸显示模式
        loadMode();

        // 初始化顶点缓冲区（4个顶点，每个顶点4个浮点值）
        mVertexBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        // 初始化纹理坐标缓冲区（4个顶点，每个顶点2个浮点值）
        mTexCoordBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /**
     * 加载壁纸显示模式（配色模式）
     * 从资源文件读取，默认0
     */
    private void loadMode() {
        Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
        if (ctx != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            try {
                String modeStr = prefs.getString("nexus_mode", "0");
                mMode = Integer.parseInt(modeStr);
                return;
            } catch (Exception e) {
                // 继续尝试从资源文件读取
            }
        }
        try {
            mMode = mResources.getInteger(R.integer.nexus_mode);
        } catch (Resources.NotFoundException exc) {
            mMode = 0;
        }
    }

    /**
     * 从SharedPreferences加载可配置参数
     */
    private void loadParameters() {
        Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
        SharedPreferences prefs = ctx != null 
            ? PreferenceManager.getDefaultSharedPreferences(ctx) 
            : null;
        
        try {
            MAX_PULSES = prefs != null ? prefs.getInt("nexus_max_pulses", DEFAULT_MAX_PULSES) : DEFAULT_MAX_PULSES;
        } catch (Exception e) {
            MAX_PULSES = DEFAULT_MAX_PULSES;
        }
        
        try {
            MAX_EXTRAS = prefs != null ? prefs.getInt("nexus_max_extras", DEFAULT_MAX_EXTRAS) : DEFAULT_MAX_EXTRAS;
        } catch (Exception e) {
            MAX_EXTRAS = DEFAULT_MAX_EXTRAS;
        }
        
        try {
            PULSE_SIZE = prefs != null ? prefs.getInt("nexus_pulse_size", DEFAULT_PULSE_SIZE) : DEFAULT_PULSE_SIZE;
            HALF_PULSE_SIZE = PULSE_SIZE / 2;
        } catch (Exception e) {
            PULSE_SIZE = DEFAULT_PULSE_SIZE;
            HALF_PULSE_SIZE = DEFAULT_PULSE_SIZE / 2;
        }
        
        try {
            GLOW_SIZE = prefs != null ? prefs.getInt("nexus_glow_size", DEFAULT_GLOW_SIZE) : DEFAULT_GLOW_SIZE;
            HALF_GLOW_SIZE = GLOW_SIZE / 2;
        } catch (Exception e) {
            GLOW_SIZE = DEFAULT_GLOW_SIZE;
            HALF_GLOW_SIZE = DEFAULT_GLOW_SIZE / 2;
        }
        
        try {
            // 从整数值转换为浮点数 (0-100 -> 0.0-1.0)
            int speedInt = prefs != null ? prefs.getInt("nexus_speed", 20) : 20;
            SPEED = speedInt / 100.0f;
        } catch (Exception e) {
            SPEED = DEFAULT_SPEED;
        }
        
        try {
            // 从整数值转换为浮点数 (0-200 -> 0.0-2.0)
            int speedMinInt = prefs != null ? prefs.getInt("nexus_speed_delta_min", 70) : 70;
            SPEED_DELTA_MIN = speedMinInt / 100.0f;
        } catch (Exception e) {
            SPEED_DELTA_MIN = DEFAULT_SPEED_DELTA_MIN;
        }
        
        try {
            // 从整数值转换为浮点数 (0-300 -> 0.0-3.0)
            int speedMaxInt = prefs != null ? prefs.getInt("nexus_speed_delta_max", 170) : 170;
            SPEED_DELTA_MAX = speedMaxInt / 100.0f;
        } catch (Exception e) {
            SPEED_DELTA_MAX = DEFAULT_SPEED_DELTA_MAX;
        }
        
        try {
            TRAIL_SIZE = prefs != null ? prefs.getInt("nexus_trail_size", DEFAULT_TRAIL_SIZE) : DEFAULT_TRAIL_SIZE;
        } catch (Exception e) {
            TRAIL_SIZE = DEFAULT_TRAIL_SIZE;
        }

        // 初始化数组
        if (mPulses == null || mPulses.length != MAX_PULSES) {
            mPulses = new Pulse[MAX_PULSES];
        }
        if (mExtras == null || mExtras.length != MAX_EXTRAS) {
            mExtras = new Pulse[MAX_EXTRAS];
        }
    }

    /**
     * 更新正交投影矩阵
     * 适配当前屏幕尺寸
     */
    private void updateProjection() {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        // 创建正交投影矩阵（左下角为(0,height)，右上角为(width,0)）
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1f, 1f);
    }

    /**
     * 创建OpenGL着色器程序
     * 包括顶点着色器和片元着色器的编译、链接
     */
    private void createProgram() {
        // 顶点着色器源码：处理MVP矩阵变换，传递纹理坐标
        String vertexShader = RawResourceLoader.readRawText(mResources, R.raw.nexus_vs);

        // 片元着色器源码：采样纹理并叠加颜色
        String fragmentShader = RawResourceLoader.readRawText(mResources, R.raw.nexus_fs);

        // 编译顶点着色器
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        // 编译片元着色器
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);

        // 创建程序并附加着色器
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vs);
        GLES20.glAttachShader(mProgram, fs);
        // 链接程序
        GLES20.glLinkProgram(mProgram);

        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "程序链接错误: " + GLES20.glGetProgramInfoLog(mProgram));
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        // 获取着色器属性/统一变量句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");

        // 编译完成后删除着色器（释放资源）
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
    }

    /**
     * 编译单个着色器
     * @param type 着色器类型（顶点/片元）
     * @param source 着色器源码
     * @return 编译后的着色器句柄（失败返回0）
     */
    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        // 检查编译状态
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "着色器编译错误: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /**
     * 加载纹理资源
     * 包括背景、脉冲、光晕纹理
     */
    private void loadTextures() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 禁用自动缩放（保持原始尺寸）
        options.inScaled = false;

        // 加载背景纹理
        mTexBackground = loadBackgroundTexture(options);
        // 加载脉冲纹理
        mTexPulse = loadTexture(R.drawable.pulse, options);
        // 加载光晕纹理
        mTexGlow = loadTexture(R.drawable.glow, options);
    }

    private int loadBackgroundTexture(BitmapFactory.Options options) {
        Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
        if (ctx == null) {
            return loadBackgroundResourceTexture(R.drawable.pyramid_background, options);
        }

        String customUri = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("nexus_custom_background_uri", null);
        if (customUri != null) {
            int tex = loadCustomBackgroundTexture(ctx, customUri);
            if (tex != 0) {
                mLastBackgroundUri = customUri;
                return tex;
            }
        }

        String preset = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("nexus_background_preset", "pyramid_background");
        mLastBackgroundPreset = preset;
        return loadBackgroundResourceTexture(resolvePresetDrawable(preset), options);
    }

    private void checkAndReloadBackground() {
        Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
        if (ctx == null || mResources == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String preset = prefs.getString("nexus_background_preset", "pyramid_background");
        String customUri = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("nexus_custom_background_uri", null);

        boolean useCustom = customUri != null;
        boolean changed;
        if (useCustom) {
            changed = mLastBackgroundUri == null || !customUri.equals(mLastBackgroundUri);
        } else {
            changed = mLastBackgroundUri != null || mLastBackgroundPreset == null
                    || !preset.equals(mLastBackgroundPreset);
        }

        if (!changed) return;

        if (mTexBackground != 0) {
            int[] tex = new int[] { mTexBackground };
            GLES20.glDeleteTextures(1, tex, 0);
            mTexBackground = 0;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;

        if (useCustom) {
            mTexBackground = loadCustomBackgroundTexture(ctx, customUri);
        }
        if (mTexBackground == 0) {
            mTexBackground = loadBackgroundResourceTexture(resolvePresetDrawable(preset), options);
        }

        mLastBackgroundUri = customUri;
        mLastBackgroundPreset = preset;
    }

    private int resolvePresetDrawable(String preset) {
        if ("pyramid_background1".equals(preset)) return R.drawable.pyramid_background1;
        if ("pyramid_background2".equals(preset)) return R.drawable.pyramid_background2;
        if ("pyramid_background3".equals(preset)) return R.drawable.pyramid_background3;
        if ("pyramid_background4".equals(preset)) return R.drawable.pyramid_background4;
        return R.drawable.pyramid_background;
    }

    private int loadCustomBackgroundTexture(Context ctx, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            if (uri == null) return 0;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            java.io.InputStream stream = ctx.getContentResolver().openInputStream(uri);
            if (stream == null) return 0;
            Bitmap bmp = BitmapFactory.decodeStream(stream, null, options);
            stream.close();
            if (bmp == null) return 0;

            if (bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                mBackgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
            }

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.e(TAG, "自定义背景加载失败", e);
            return 0;
        }
    }

    private int loadBackgroundResourceTexture(int resourceId, BitmapFactory.Options options) {
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        if (bitmap == null) return 0;
        if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            mBackgroundAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        }

        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureHandle[0];
    }

    /**
     * 加载单个纹理资源
     * @param resourceId 资源ID
     * @param options 位图加载选项
     * @return 纹理ID
     */
    private int loadTexture(int resourceId, BitmapFactory.Options options) {
        // 从资源解码位图
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        int[] textureHandle = new int[1];
        // 生成纹理ID
        GLES20.glGenTextures(1, textureHandle, 0);
        // 绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        // 设置纹理过滤模式（线性过滤）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // 设置纹理环绕模式（边缘夹紧）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // 将位图数据上传到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        // 回收位图（释放内存）
        bitmap.recycle();
        return textureHandle[0];
    }

    /**
     * 初始化脉冲数组
     * 初始化常规脉冲和额外脉冲的初始状态
     */
    private void initPulses() {
        // 初始化常规脉冲
        for (int i = 0; i < MAX_PULSES; i++) {
            mPulses[i] = new Pulse();
            initPulse(mPulses[i], PULSE_NORMAL);
        }
        // 初始化额外脉冲（默认未激活）
        for (int i = 0; i < MAX_EXTRAS; i++) {
            mExtras[i] = new Pulse();
            mExtras[i].pulseType = PULSE_EXTRA;
            mExtras[i].active = 0;
        }
    }

    /**
     * 初始化单个脉冲的属性
     * @param pulse 脉冲实例
     * @param pulseType 脉冲类型（常规/额外）
     */
    private void initPulse(Pulse pulse, int pulseType) {
        // 随机生成速度缩放系数
        float scale = rsRand(SPEED_DELTA_MIN, SPEED_DELTA_MAX);
        pulse.scale = scale;
        
        // 随机决定脉冲移动方向（水平/垂直）
        if (rsRand(1.f) > 0.5f) {
            // 水平方向移动
            pulse.originX = rsRand(mWidth * 2f / PULSE_SIZE) * PULSE_SIZE;
            pulse.dx = 0;
            // 随机决定从上/下边缘进入
            if (rsRand(1.f) > 0.5f) {
                pulse.originY = 0;
                pulse.dy = scale;
            } else {
                pulse.originY = mHeight / scale;
                pulse.dy = -scale;
            }
        } else {
            // 垂直方向移动
            pulse.originY = rsRand(mHeight / (float) PULSE_SIZE) * PULSE_SIZE;
            pulse.dy = 0;
            // 随机决定从左/右边缘进入
            if (rsRand(1.f) > 0.5f) {
                pulse.originX = 0;
                pulse.dx = scale;
            } else {
                pulse.originX = mWidth * 2f / scale;
                pulse.dx = -scale;
            }
        }

        // 设置初始延迟时间
        pulse.startTime = (int) SystemClock.uptimeMillis() + (int) rsRand(MAX_DELAY);
        // 随机颜色索引
        pulse.color = (int) rsRand(7);
        pulse.pulseType = pulseType;
        // 常规脉冲默认激活，额外脉冲默认未激活
        pulse.active = pulseType == PULSE_EXTRA ? 0 : 1;
    }

    /**
     * 绘制背景纹理
     * 适配屏幕缩放和横向偏移
     */
    private void drawBackground() {
        // 背景不需要混合（不透明）
        GLES20.glDisable(GLES20.GL_BLEND);
        // 使用着色器程序
        GLES20.glUseProgram(mProgram);

        // 初始化模型矩阵
        Matrix.setIdentityM(mModelMatrix, 0);
        // 应用缩放（适配屏幕尺寸）
        Matrix.scaleM(mModelMatrix, 0, mWorldScaleX, mWorldScaleY, 1.0f);
        // 竖屏时应用横向偏移（多屏滑动）
        if (!mRotate) {
            Matrix.translateM(mModelMatrix, 0, -(mXOffset * mWidth), 0f, 0f);
        }
        // 计算MVP矩阵（投影*模型）
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);

        // 设置背景颜色（白色不透明）
        setColor(1f, 1f, 1f, 1f);
        // 计算背景绘制范围（横屏/竖屏适配）
        float right = mRotate ? mHeight * 2f : mWidth * 2f;
        float bottom = mRotate ? mWidth : mHeight;
        float[] bgUv = buildBackgroundUv(right, bottom);
        // 绘制背景矩形
        drawTexturedRect(mTexBackground, 0f, 0f, right, bottom, bgUv, mMVPMatrix);
    }

    private float[] buildBackgroundUv(float drawWidth, float drawHeight) {
        float viewAspect = drawHeight > 0f ? drawWidth / drawHeight : 1f;
        float bgAspect = mBackgroundAspect > 0f ? mBackgroundAspect : 1f;

        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;

        if (viewAspect > bgAspect) {
            float scale = bgAspect / viewAspect;
            float pad = (1f - scale) * 0.5f;
            v0 = pad;
            v1 = 1f - pad;
        } else if (viewAspect < bgAspect) {
            float scale = viewAspect / bgAspect;
            float pad = (1f - scale) * 0.5f;
            u0 = pad;
            u1 = 1f - pad;
        }

        return new float[] {
                u0, v0,
                u1, v0,
                u0, v1,
                u1, v1
        };
    }

    /**
     * 绘制脉冲数组
     * @param pulseSet 脉冲数组
     * @param setSize 数组长度
     * @param now 当前时间（毫秒）
     */
    private void drawPulses(Pulse[] pulseSet, int setSize, int now) {
        // 启用混合（脉冲/光晕需要透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置混合模式（加法混合，增强光晕效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // 使用着色器程序
        GLES20.glUseProgram(mProgram);

        // 遍历所有脉冲
        for (int i = 0; i < setSize; i++) {
            Pulse p = pulseSet[i];
            // 计算脉冲已运行时间
            int delta = now - p.startTime;
            // 仅绘制激活且已到开始时间的脉冲
            if (p.active != 0 && delta >= 0) {
                // 初始化模型矩阵
                Matrix.setIdentityM(mModelMatrix, 0);
                // 竖屏时应用横向偏移
                if (!mRotate) {
                    Matrix.translateM(mModelMatrix, 0, -(mXOffset * mWidth), 0f, 0f);
                }
                // 应用脉冲缩放和屏幕适配缩放
                Matrix.scaleM(mModelMatrix, 0, p.scale * mWorldScaleX, p.scale * mWorldScaleY, 1.0f);
                // 计算MVP矩阵
                Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mModelMatrix, 0);

                // 计算脉冲当前位置
                float x = p.originX + (p.dx * SPEED * delta);
                float y = p.originY + (p.dy * SPEED * delta);

                // 设置脉冲颜色
                setColorForPulse(p.color);

                // 根据移动方向绘制脉冲和光晕
                if (p.dx < 0) {
                    // 向左移动
                    float xx = x + (TRAIL_SIZE * PULSE_SIZE);
                    if (xx <= 0) {
                        // 超出屏幕，重新初始化脉冲
                        initPulse(p, p.pulseType);
                    } else {
                        // 绘制脉冲纹理
                        drawTexturedRect(mTexPulse, x, y, xx, y + PULSE_SIZE, mUv0, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐）
                        drawTexturedRect(mTexGlow,
                                x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                mUv0, mMVPMatrix);
                    }
                } else if (p.dx > 0) {
                    // 向右移动
                    x += PULSE_SIZE;
                    float xx = x - (TRAIL_SIZE * PULSE_SIZE);
                    if (xx >= mWidth * 2f) {
                        // 超出屏幕，重新初始化脉冲
                        initPulse(p, p.pulseType);
                    } else {
                        // 绘制脉冲纹理（180度旋转）
                        drawTexturedRect(mTexPulse, xx, y, x, y + PULSE_SIZE, mUv180, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，180度旋转）
                        drawTexturedRect(mTexGlow,
                                x - HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                x - HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                mUv180, mMVPMatrix);
                    }
                } else if (p.dy < 0) {
                    // 向上移动
                    float yy = y + (TRAIL_SIZE * PULSE_SIZE);
                    if (yy <= 0) {
                        // 超出屏幕，重新初始化脉冲
                        initPulse(p, p.pulseType);
                    } else {
                        // 绘制脉冲纹理（270度旋转）
                        drawTexturedRect(mTexPulse, x, y, x + PULSE_SIZE, yy, mUv270, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，270度旋转）
                        drawTexturedRect(mTexGlow,
                                x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                y + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                mUv270, mMVPMatrix);
                    }
                } else if (p.dy > 0) {
                    // 向下移动
                    y += PULSE_SIZE;
                    float yy = y - (TRAIL_SIZE * PULSE_SIZE);
                    if (yy >= mHeight) {
                        // 超出屏幕，重新初始化脉冲
                        initPulse(p, p.pulseType);
                    } else {
                        // 绘制脉冲纹理（90度旋转）
                        drawTexturedRect(mTexPulse, x, yy, x + PULSE_SIZE, y, mUv90, mMVPMatrix);
                        // 绘制光晕纹理（居中对齐，90度旋转）
                        drawTexturedRect(mTexGlow,
                                x + HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                y - HALF_PULSE_SIZE - HALF_GLOW_SIZE,
                                x + HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                y - HALF_PULSE_SIZE + HALF_GLOW_SIZE,
                                mUv90, mMVPMatrix);
                    }
                }
            }
        }
    }

    /**
     * 设置脉冲颜色
     * @param c 颜色索引（0-3）
     */
    private void setColorForPulse(int c) {
        if (mMode == 1) {
            setColor(0.9f, 0.1f, 0.1f, 0.8f);
            return;
        }
        if (mMode == 2) {
            setColor(0.1f, 0.9f, 0.1f, 0.8f);
            return;
        }
         if (mMode == 3) {
            setColor(0.1f, 0.1f, 0.9f, 0.8f);
            return;
        }
         if (mMode == 4) {
            setColor(0.9f, 0.9f, 0.9f, 0.8f);
            return;
        }
         if (mMode == 5) {
            setColor(0.3f, 0.9f, 0.9f, 0.8f);
            return;
        }
         if (mMode == 6) {
            setColor(0.9f, 0.3f, 0.9f, 0.8f);
            return;
        }
         if (mMode == 7) {
            setColor(0.95f, 0.7f, 0.75f, 0.8f);
            return;
        }
        // 根据颜色索引设置RGBA
        if (c == 0) {
            setColor(1.0f, 0.0f, 0.0f, 0.8f);
        } else if (c == 1) {
            setColor(0.0f, 0.8f, 0.0f, 0.8f);
        } else if (c == 2) {
            setColor(0.0f, 0.4f, 0.9f, 0.8f);
        } else if (c == 3) {
            setColor(1.0f, 0.8f, 0.0f, 0.8f);
        }else if (c == 4) {
            setColor(0.9f, 0.3f, 0.9f, 0.8f);
        }else if (c == 5) {
            setColor(0.9f, 0.9f, 0.9f, 0.8f);
        }else if (c == 6) {
            setColor(0.3f, 0.9f, 0.9f, 0.8f);
        }else {
            setColor(0.95f, 0.7f, 0.75f, 0.8f);
        }
    }

    /**
     * 设置当前渲染颜色
     * @param r 红色分量（0-1）
     * @param g 绿色分量（0-1）
     * @param b 蓝色分量（0-1）
     * @param a 透明度分量（0-1）
     */
    private void setColor(float r, float g, float b, float a) {
        mColor[0] = r;
        mColor[1] = g;
        mColor[2] = b;
        mColor[3] = a;
    }

    /**
     * 绘制带纹理的矩形
     * @param textureId 纹理ID
     * @param left 左边界坐标
     * @param top 上边界坐标
     * @param right 右边界坐标
     * @param bottom 下边界坐标
     * @param uv 纹理UV坐标数组
     * @param mvp MVP矩阵
     */
    private void drawTexturedRect(int textureId, float left, float top, float right, float bottom,
                                  float[] uv, float[] mvp) {
        // 构建顶点数据（矩形，三角带方式绘制）
        float[] vertices = {
                left, top, 0f, 1f,
                right, top, 0f, 1f,
                left, bottom, 0f, 1f,
                right, bottom, 0f, 1f
        };

        // 填充顶点缓冲区
        mVertexBuffer.clear();
        mVertexBuffer.put(vertices).position(0);
        // 填充纹理坐标缓冲区
        mTexCoordBuffer.clear();
        mTexCoordBuffer.put(uv).position(0);

        // 设置MVP矩阵统一变量
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mvp, 0);
        // 设置颜色统一变量
        GLES20.glUniform4f(mColorHandle, mColor[0], mColor[1], mColor[2], mColor[3]);

        // 激活并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(mTextureHandle, 0);

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 4, GLES20.GL_FLOAT, false, 16, mVertexBuffer);

        // 启用纹理坐标属性
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mTexCoordBuffer);

        // 绘制三角带（4个顶点）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 禁用属性（优化性能）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /**
     * 处理点击事件，添加额外脉冲
     * @param x 点击X坐标
     * @param y 点击Y坐标
     */
    private void addTap(int x, int y) {
        int count = 0;
        // 随机初始颜色
        int color = (int) rsRand(4);
        // 随机缩放系数
        float scale = rsRand(0.9f, 1.9f);
        // 对齐到脉冲尺寸网格（避免偏移）
        x = (x / PULSE_SIZE) * PULSE_SIZE;
        y = (y / PULSE_SIZE) * PULSE_SIZE;

        // 遍历额外脉冲数组，找未激活的脉冲
        for (int i = 0; i < MAX_EXTRAS; i++) {
            Pulse p = mExtras[i];
            if (p.active == 0) {
                // 设置脉冲初始位置（适配缩放）
                p.originX = x / scale;
                p.originY = y / scale;
                p.scale = scale;

                // 为前4个未激活脉冲设置不同方向
                if (count == 0) {
                    // 向右
                    p.dx = scale;
                    p.dy = 0.0f;
                } else if (count == 1) {
                    // 向左
                    p.dx = -scale;
                    p.dy = 0.0f;
                } else if (count == 2) {
                    // 向下
                    p.dx = 0.0f;
                    p.dy = scale;
                } else if (count == 3) {
                    // 向上
                    p.dx = 0.0f;
                    p.dy = -scale;
                }

                // 激活脉冲
                p.active = 1;
                // 设置颜色
                p.color = color;
                color++;
                if (color >= 7) {
                    color = 0;
                }
                // 设置开始时间为当前时间
                p.startTime = (int) SystemClock.uptimeMillis();
                p.pulseType = PULSE_EXTRA;
                count++;
                // 最多添加4个脉冲
                if (count == 4) {
                    break;
                }
            }
        }
    }

    /**
     * 生成0到max之间的随机浮点数
     * @param max 最大值
     * @return 随机数
     */
    private float rsRand(float max) {
        return mRandom.nextFloat() * max;
    }

    /**
     * 生成min到max之间的随机浮点数
     * @param min 最小值
     * @param max 最大值
     * @return 随机数
     */
    private float rsRand(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }
}