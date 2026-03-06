package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
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
 * Galaxy4动态壁纸的OpenGL ES 2.0渲染核心类
 * 完全从RenderScript移植到OpenGL ES 2.0，100%还原原版Galaxy4动态壁纸的视觉效果
 * 负责壁纸的OpenGL初始化、着色器编译、纹理加载、粒子系统（星星/星云）的绘制与动画
 */
public class Galaxy4GL extends GLESScene {
    // 日志标签
    private static final String TAG = "Galaxy4GL";
    
    // 背景星星数量配置
    private static final int DEFAULT_BG_STAR_COUNT = 11000;  // 默认背景星星数量
    private static final int MIN_BG_STAR_COUNT = 1000;       // 背景星星最小数量
    private static final int MAX_BG_STAR_COUNT = 20000;      // 背景星星最大数量
    private int mBgStarCount = DEFAULT_BG_STAR_COUNT;        // 当前背景星星数量
    
    // 太空星云数量配置
    private static final int DEFAULT_SPACE_CLOUD_COUNT = 25; // 默认太空星云数量
    private static final int MIN_SPACE_CLOUD_COUNT = 5;      // 太空星云最小数量
    private static final int MAX_SPACE_CLOUD_COUNT = 100;    // 太空星云最大数量
    private int mSpaceCloudCount = DEFAULT_SPACE_CLOUD_COUNT;// 当前太空星云数量
    
    private Context mContext;  // 上下文对象，用于获取资源和SharedPreferences
    
    // 原版壁纸定义的粒子数量常量（参考值）
    private static final int BG_STAR_COUNT = 11000;    // 背景星星数量（原版）
    private static final int SPACE_CLOUD_COUNT = 25;   // 太空星云数量（原版）
    private static final int STATIC_STAR_COUNT = 50;   // 静态星星数量（原版）
    private static final int GALAXY_RADIUS = 300;      // 银河半径（布局计算用）
    
    private boolean mGLInitialized = false;  // OpenGL初始化状态标记
    
    // 着色器程序句柄
    private int mBgProgram;          // 背景纹理绘制着色器程序
    private int mCloudProgram;       // 星云粒子绘制着色器程序
    private int mBgStarProgram;      // 背景星星绘制着色器程序
    private int mStaticStarProgram;  // 静态星星（脉冲效果）绘制着色器程序
    
    // 纹理句柄
    private int mTexBg;              // 背景纹理
    private int mTexCloud;           // 星云纹理
    private int mTexStaticStar;      // 静态星星纹理1
    private int mTexStaticStar2;     // 静态星星纹理2（用于混合动画）
    
    // 粒子数据数组（存储每个粒子的位置+大小等信息）
    private float[] mSpaceClouds;    // 星云粒子：x, y, z, size 每个粒子4个浮点数
    private float[] mBgStars;        // 背景星星粒子：x, y, z 每个粒子3个浮点数
    private float[] mStaticStars;    // 静态星星粒子：x, y, size 每个粒子3个浮点数
    
    // 粒子数据缓冲区（OpenGL绘制用，直接映射到原生内存提升性能）
    private FloatBuffer mSpaceCloudBuffer;  // 星云粒子缓冲区
    private FloatBuffer mBgStarBuffer;      // 背景星星缓冲区
    private FloatBuffer mStaticStarBuffer;  // 静态星星缓冲区
    
    // 矩阵相关
    private float[] mMVPMatrix = new float[16];  // 模型视图投影矩阵（MVP）
    private float[] mProjMatrix = new float[16]; // 投影矩阵
    
    private Random mRandom = new Random();  // 随机数生成器，用于粒子位置随机化
    
    // 屏幕尺寸
    private float mScreenWidth;  // 屏幕宽度（适配后）
    private float mScreenHeight; // 屏幕高度（适配后）
    
    /**
     * 构造方法：初始化Galaxy4GL渲染器
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param context 上下文对象，用于获取资源和SharedPreferences
     */
    public Galaxy4GL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        loadParticleCountsFromPreferences(); // 从配置文件加载粒子数量
    }
    
    /**
     * 生命周期方法：创建时回调（GL线程）
     * 标记OpenGL初始化待执行
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 将在GL线程初始化");
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBg, mTexCloud, mTexStaticStar, mTexStaticStar2 };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBg = 0;
        mTexCloud = 0;
        mTexStaticStar = 0;
        mTexStaticStar2 = 0;

        // 释放着色器程序
        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mCloudProgram != 0) {
            GLES20.glDeleteProgram(mCloudProgram);
            mCloudProgram = 0;
        }
        if (mBgStarProgram != 0) {
            GLES20.glDeleteProgram(mBgStarProgram);
            mBgStarProgram = 0;
        }
        if (mStaticStarProgram != 0) {
            GLES20.glDeleteProgram(mStaticStarProgram);
            mStaticStarProgram = 0;
        }

        mSpaceCloudBuffer = null;
        mBgStarBuffer = null;
        mStaticStarBuffer = null;
        mGLInitialized = false;
    }
    
    /**
     * 从SharedPreferences加载粒子数量配置
     * 读取用户设置的背景星星/星云数量，并限制在有效范围内
     */
    private void loadParticleCountsFromPreferences() {
        if (mContext != null) {
            Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
            SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appContext);

            if (defaultPrefs.contains("galaxy4_bg_star_count")) {
                mBgStarCount = defaultPrefs.getInt("galaxy4_bg_star_count", DEFAULT_BG_STAR_COUNT);
            } else {
                SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
                mBgStarCount = legacyPrefs.getInt("galaxy4_bg_star_count", DEFAULT_BG_STAR_COUNT);
                if (legacyPrefs.contains("galaxy4_bg_star_count")) {
                    defaultPrefs.edit().putInt("galaxy4_bg_star_count", mBgStarCount).apply();
                }
            }

            if (defaultPrefs.contains("galaxy4_space_cloud_count")) {
                mSpaceCloudCount = defaultPrefs.getInt("galaxy4_space_cloud_count", DEFAULT_SPACE_CLOUD_COUNT);
            } else {
                SharedPreferences legacyPrefs = appContext.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE);
                mSpaceCloudCount = legacyPrefs.getInt("galaxy4_space_cloud_count", DEFAULT_SPACE_CLOUD_COUNT);
                if (legacyPrefs.contains("galaxy4_space_cloud_count")) {
                    defaultPrefs.edit().putInt("galaxy4_space_cloud_count", mSpaceCloudCount).apply();
                }
            }
            
            // 限制数值在有效范围内（最小-最大值之间）
            mBgStarCount = Math.max(MIN_BG_STAR_COUNT, Math.min(MAX_BG_STAR_COUNT, mBgStarCount));
            mSpaceCloudCount = Math.max(MIN_SPACE_CLOUD_COUNT, Math.min(MAX_SPACE_CLOUD_COUNT, mSpaceCloudCount));
            
            Log.d(TAG, "加载粒子数量 - 背景星星: " + mBgStarCount + ", 星云: " + mSpaceCloudCount);
        }
    }
    
    /**
     * 设置背景星星数量并保存到配置
     * @param count 目标星星数量（会自动限制在MIN/MAX范围内）
     */
    public void setBgStarCount(int count) {
        // 限制数值范围
        count = Math.max(MIN_BG_STAR_COUNT, Math.min(MAX_BG_STAR_COUNT, count));
        if (count != mBgStarCount) {
            mBgStarCount = count;
            if (mContext != null) {
                Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit()
                        .putInt("galaxy4_bg_star_count", mBgStarCount)
                        .apply();
            }
            // 标记OpenGL需要重新初始化（粒子数量变化）
            mGLInitialized = false;
            Log.d(TAG, "背景星星数量已修改为: " + mBgStarCount);
        }
    }
    
    /**
     * 设置星云数量并保存到配置
     * @param count 目标星云数量（会自动限制在MIN/MAX范围内）
     */
    public void setSpaceCloudCount(int count) {
        // 限制数值范围
        count = Math.max(MIN_SPACE_CLOUD_COUNT, Math.min(MAX_SPACE_CLOUD_COUNT, count));
        if (count != mSpaceCloudCount) {
            mSpaceCloudCount = count;
            if (mContext != null) {
                Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit()
                        .putInt("galaxy4_space_cloud_count", mSpaceCloudCount)
                        .apply();
            }
            // 标记OpenGL需要重新初始化（粒子数量变化）
            mGLInitialized = false;
            Log.d(TAG, "星云数量已修改为: " + mSpaceCloudCount);
        }
    }
    
    /**
     * 初始化OpenGL环境
     * 包括：清屏颜色、混合模式、着色器程序创建、纹理加载、粒子位置初始化
     * 仅在未初始化且资源有效时执行
     */
    private void initGL() {
        if (mGLInitialized || mResources == null) return;
        
        Log.d(TAG, "initGL 开始执行");
        mGLInitialized = true;
        
        // 设置清屏颜色（黑色，半透明）
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.5f);
        // 禁用深度测试（2D壁纸不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合模式（实现透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置混合因子：源Alpha * 源颜色 + 1 * 目标颜色（加法混合，适合粒子发光效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        
        // 创建所有着色器程序
        createPrograms();
        // 加载壁纸所需纹理
        loadTextures();
        // 初始化粒子位置
        positionParticles();
        
        Log.d(TAG, "initGL 执行完成");
    }
    
    /**
     * 创建所有OpenGL着色器程序
     * 包括：背景、星云、背景星星、静态星星的顶点/片段着色器
     */
    private void createPrograms() {
        // 1. 背景着色器程序：绘制全屏纹理四边形
        mBgProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_bg_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_bg_fs)
        );
        
        // 2. 星云着色器程序：绘制带旋转的点精灵粒子
        mCloudProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_cloud_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_cloud_fs)
        );
        
        // 3. 背景星星着色器程序：绘制固定大小的白色点精灵
        mBgStarProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_bg_star_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_bg_star_fs)
        );
        
        // 4. 静态星星着色器程序：双纹理混合实现脉冲效果
        mStaticStarProgram = createShaderProgram(
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_static_star_vs),
            RawResourceLoader.readRawText(mResources, R.raw.galaxy4_static_star_fs)
        );
        
        Log.d(TAG, "着色器程序创建完成");
    }
    
    /**
     * 创建并链接OpenGL着色器程序
     * @param vertexSource 顶点着色器源码
     * @param fragmentSource 片段着色器源码
     * @return 链接成功的着色器程序句柄（失败返回0）
     */
    private int createShaderProgram(String vertexSource, String fragmentSource) {
        // 编译顶点着色器
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        // 编译片段着色器
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        
        // 创建程序对象并附加着色器
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        // 链接程序
        GLES20.glLinkProgram(program);
        
        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "程序链接错误: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        
        // 链接成功后删除着色器（已附加到程序，无需保留）
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        
        return program;
    }
    
    /**
     * 编译单个OpenGL着色器
     * @param type 着色器类型（GL_VERTEX_SHADER/GL_FRAGMENT_SHADER）
     * @param source 着色器源码
     * @return 编译成功的着色器句柄（失败返回0）
     */
    private int compileShader(int type, String source) {
        // 创建着色器对象
        int shader = GLES20.glCreateShader(type);
        // 设置着色器源码
        GLES20.glShaderSource(shader, source);
        // 编译着色器
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
     * 加载壁纸所需的所有纹理资源
     * 包括：背景、星云、静态星星1、静态星星2
     */
    private void loadTextures() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false; // 禁用位图自动缩放（保持原始尺寸）
        
        // 加载各纹理
        mTexBg = loadTexture(R.drawable.galaxy4_bg, options);
        mTexCloud = loadTexture(R.drawable.galaxy4_cloud, options);
        mTexStaticStar = loadTexture(R.drawable.galaxy4_staticstar, options);
        mTexStaticStar2 = loadTexture(R.drawable.galaxy4_staticstar2, options);
        
        Log.d(TAG, "纹理加载完成");
    }
    
    /**
     * 从资源ID加载OpenGL纹理
     * @param resourceId 纹理资源ID（R.drawable.xxx）
     * @param options 位图解码选项
     * @return 加载成功的纹理句柄
     */
    private int loadTexture(int resourceId, BitmapFactory.Options options) {
        // 从资源解码位图
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        
        // 生成纹理句柄
        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        
        // 绑定纹理并设置参数
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        // 缩小过滤：线性过滤
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        // 放大过滤：线性过滤
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // S轴纹理环绕：夹紧到边缘（避免纹理重复）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        // T轴纹理环绕：夹紧到边缘
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        // 将位图数据上传到OpenGL纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        
        // 释放位图内存（已上传到GPU，无需保留）
        bitmap.recycle();
        
        return textureHandle[0];
    }
    
    /**
     * 初始化所有粒子的位置和大小（移植自原版RenderScript的positionParticles方法）
     * 包括：星云、背景星星、静态星星的位置计算和缓冲区初始化
     */
    private void positionParticles() {
        mScreenWidth = mWidth;
        mScreenHeight = mHeight;
        
        float wRatio = 1.0f;  // 宽度比例（适配不同屏幕）
        float hRatio = 1.0f;  // 高度比例（适配不同屏幕）
        
        // 适配屏幕宽高比
        if (mScreenWidth > mScreenHeight) {
            wRatio = mScreenWidth / mScreenHeight;
            mScreenHeight = mScreenWidth;
        } else {
            hRatio = mScreenHeight / mScreenWidth;
            mScreenWidth = mScreenHeight;
        }
        
        // 缩放比例（将银河半径适配到屏幕尺寸）
        float scale = GALAXY_RADIUS / (mScreenWidth * 0.5f);
        
        // ========== 初始化星云粒子 ==========
        // 每个星云粒子存储：角度、距离、z轴值（共3个浮点数）
        mSpaceClouds = new float[mSpaceCloudCount * 3];
        for (int i = 0; i < mSpaceCloudCount; i++) {
            // 高斯随机数生成距离（模拟银河分布）
            float d = Math.abs(randomGauss()) * GALAXY_RADIUS * 0.5f + mRandom.nextFloat() * 64.0f;
            // 映射距离到缩放后的范围
            d = mapf(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, d);
            float id = d / GALAXY_RADIUS;
            // 高斯随机数生成z轴值（模拟深度）
            float z = randomGauss() * 0.4f * (1.0f - id);
            
            // 根据距离调整z轴值（模拟银河中心/边缘的深度差异）
            if (d > GALAXY_RADIUS * 0.15f) {
                z *= 0.6f * (1.0f - id);
            } else {
                z *= 0.72f;
            }
            
            // 存储粒子数据
            int idx = i * 3;
            mSpaceClouds[idx] = mRandom.nextFloat() * (float) (Math.PI * 2);  // 随机角度（0-2π）
            mSpaceClouds[idx + 1] = d;                                       // 距离
            mSpaceClouds[idx + 2] = z / 5.0f;                                // z轴值（缩放后）
        }
        
        // ========== 初始化背景星星粒子 ==========
        // 每个背景星星存储：角度、距离、z轴值（共3个浮点数）
        mBgStars = new float[mBgStarCount * 3];
        for (int i = 0; i < mBgStarCount; i++) {
            float d = Math.abs(randomGauss()) * GALAXY_RADIUS * 0.5f + mRandom.nextFloat() * 64.0f;
            d = mapf(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, d);
            float id = d / GALAXY_RADIUS;
            float z = randomGauss() * 0.4f * (1.0f - id);
            
            if (d > GALAXY_RADIUS * 0.15f) {
                z *= 0.6f * (1.0f - id);
            } else {
                z *= 0.72f;
            }
            
            int idx = i * 3;
            mBgStars[idx] = mRandom.nextFloat() * (float) (Math.PI * 2);  // 随机角度
            mBgStars[idx + 1] = d;                                       // 距离
            mBgStars[idx + 2] = z / 5.0f;                                // z轴值
        }
        
        // ========== 初始化静态星星粒子 ==========
        // 每个静态星星存储：x坐标、y坐标、点大小（共3个浮点数）
        mStaticStars = new float[STATIC_STAR_COUNT * 3];
        for (int i = 0; i < STATIC_STAR_COUNT; i++) {
            int idx = i * 3;
            // 随机x坐标（-wRatio ~ wRatio）
            mStaticStars[idx] = (mRandom.nextFloat() * 2.0f - 1.0f) * wRatio;
            // 随机y坐标（-hRatio ~ hRatio）
            mStaticStars[idx + 1] = (mRandom.nextFloat() * 2.0f - 1.0f) * hRatio;
            // 随机点大小（1.0 ~ 10.0）
            mStaticStars[idx + 2] = mRandom.nextFloat() * 9.0f + 1.0f;
        }
        
        // ========== 创建粒子缓冲区（原生内存） ==========
        // 星云缓冲区
        ByteBuffer bb = ByteBuffer.allocateDirect(mSpaceClouds.length * 4);
        bb.order(ByteOrder.nativeOrder()); // 使用原生字节序（匹配GPU）
        mSpaceCloudBuffer = bb.asFloatBuffer();
        mSpaceCloudBuffer.put(mSpaceClouds);
        mSpaceCloudBuffer.position(0); // 重置缓冲区位置
        
        // 背景星星缓冲区
        bb = ByteBuffer.allocateDirect(mBgStars.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mBgStarBuffer = bb.asFloatBuffer();
        mBgStarBuffer.put(mBgStars);
        mBgStarBuffer.position(0);
        
        // 静态星星缓冲区
        bb = ByteBuffer.allocateDirect(mStaticStars.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mStaticStarBuffer = bb.asFloatBuffer();
        mStaticStarBuffer.put(mStaticStars);
        mStaticStarBuffer.position(0);
        
        // 更新投影矩阵（适配屏幕）
        updateProjectionMatrix();
        
        Log.d(TAG, "粒子位置初始化完成");
    }
    
    /**
     * 更新投影矩阵和MVP矩阵
     * 适配屏幕宽高比，并应用旋转/缩放/平移变换
     */
    private void updateProjectionMatrix() {
        // 计算屏幕宽高比
        float aspect = (float) mWidth / mHeight;
        // 创建透视投影矩阵
        Matrix.frustumM(mProjMatrix, 0, -aspect, aspect, -1, 1, 1, 100);
        
        // 临时矩阵（用于矩阵乘法）
        float[] temp = new float[16];
        float[] rotMatrix = new float[16];
        
        // 绕Y轴旋转180度（适配原版壁纸的方向）
        Matrix.setRotateM(rotMatrix, 0, 180, 0, 1, 0);
        Matrix.multiplyMM(temp, 0, mProjMatrix, 0, rotMatrix, 0);
        
        // 创建缩放矩阵（X轴翻转）
        float[] scaleMatrix = new float[16];
        Matrix.setIdentityM(scaleMatrix, 0);
        Matrix.scaleM(scaleMatrix, 0, -1, 1, 1);
        Matrix.multiplyMM(rotMatrix, 0, temp, 0, scaleMatrix, 0);
        
        // 平移矩阵并赋值给MVP矩阵
        Matrix.translateM(mMVPMatrix, 0, rotMatrix, 0, 0, 0, 1);
    }
    
    /**
     * 生成高斯分布的随机数（Box-Muller算法）
     * 用于模拟粒子的自然分布（更接近真实银河的星星分布）
     * @return 高斯随机数（均值0，方差1）
     */
    private float randomGauss() {
        float x1 = 0.0f, x2, w;
        
        // Box-Muller算法：生成两个独立的高斯随机数
        w = 2.0f;
        while (w >= 1.0f) {
            x1 = mRandom.nextFloat() * 2.0f - 1.0f;
            x2 = mRandom.nextFloat() * 2.0f - 1.0f;
            w = x1 * x1 + x2 * x2;
        }
        
        w = (float) Math.sqrt(-2.0f * Math.log(w) / w);
        return x1 * w;
    }
    
    /**
     * 将数值从一个范围映射到另一个范围
     * @param minStart 原范围最小值
     * @param minStop 原范围最大值
     * @param maxStart 目标范围最小值
     * @param maxStop 目标范围最大值
     * @param value 要映射的数值
     * @return 映射后的数值
     */
    private float mapf(float minStart, float minStop, float maxStart, float maxStop, float value) {
        return maxStart + (maxStart - maxStop) * ((value - minStart) / (minStop - minStart));
    }
    
    /**
     * 绘制每一帧（GL线程回调）
     * 执行顺序：清屏 → 绘制背景 → 更新并绘制星云 → 更新并绘制背景星星 → 绘制静态星星
     * @param timeMs 时间戳（毫秒），用于动画控制
     */
    @Override
    public void drawFrame(long timeMs) {
        // 未初始化则先执行OpenGL初始化
        if (!mGLInitialized) {
            initGL();
            return;
        }
        
        // 清屏（颜色缓冲区）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // 转换时间戳为秒（方便动画计算）
        float time = timeMs / 1000.0f;
        
        // 1. 绘制背景纹理
        drawBackground();
        
        // 2. 更新星云角度（旋转动画）并绘制
        for (int i = 0; i < mSpaceCloudCount; i++) {
            mSpaceClouds[i * 3] -= 0.065f;  // 星云旋转速度（负值为顺时针）
        }
        mSpaceCloudBuffer.position(0);
        mSpaceCloudBuffer.put(mSpaceClouds);
        mSpaceCloudBuffer.position(0);
        drawSpaceClouds();
        
        // 3. 更新背景星星角度（慢速旋转）并绘制
        for (int i = 0; i < mBgStarCount; i++) {
            mBgStars[i * 3] -= 0.007f;  // 背景星星旋转速度（比星云慢）
        }
        mBgStarBuffer.position(0);
        mBgStarBuffer.put(mBgStars);
        mBgStarBuffer.position(0);
        drawBgStars();
        
        // 4. 绘制静态星星（带脉冲动画）
        drawStaticStars(time);
    }
    
    /**
     * 绘制全屏背景纹理
     */
    private void drawBackground() {
        // 使用背景着色器程序
        GLES20.glUseProgram(mBgProgram);
        
        // 全屏四边形顶点数据：(x,y) 位置 + (u,v) 纹理坐标
        float[] vertices = {
            -1, -1, 0, 1,  // 左下
             1, -1, 1, 1,  // 右下
            -1,  1, 0, 0,  // 左上
             1,  1, 1, 0   // 右上
        };
        
        // 创建顶点缓冲区
        FloatBuffer vertexBuffer = createFloatBuffer(vertices);
        
        // 获取着色器属性/统一变量句柄
        int posHandle = GLES20.glGetAttribLocation(mBgProgram, "aPosition");    // 位置属性
        int texHandle = GLES20.glGetAttribLocation(mBgProgram, "aTexCoord");    // 纹理坐标属性
        int samplerHandle = GLES20.glGetUniformLocation(mBgProgram, "uTexture");// 纹理采样器
        
        // 启用属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);
        
        // 设置位置属性指针（每4个浮点数为一组，步长16字节）
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        // 设置纹理坐标属性指针
        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        
        // 绑定背景纹理到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexBg);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制四边形（三角带方式，4个顶点）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // 禁用属性数组（绘制完成后释放）
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }
    
    /**
     * 绘制星云粒子（点精灵方式）
     */
    private void drawSpaceClouds() {
        // 使用星云着色器程序
        GLES20.glUseProgram(mCloudProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mCloudProgram, "aPosition");  // 粒子位置属性
        int mvpHandle = GLES20.glGetUniformLocation(mCloudProgram, "uMVPMatrix");// MVP矩阵统一变量
        int samplerHandle = GLES20.glGetUniformLocation(mCloudProgram, "uTexture");// 纹理采样器
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mMVPMatrix, 0);
        
        // 启用位置属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        
        // 设置粒子位置属性指针
        mSpaceCloudBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mSpaceCloudBuffer);
        
        // 绑定星云纹理到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexCloud);
        GLES20.glUniform1i(samplerHandle, 0);
        
        // 绘制所有星云粒子（点精灵方式）
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mSpaceCloudCount);
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
    }
    
    /**
     * 绘制背景星星粒子（点精灵方式）
     */
    private void drawBgStars() {
        // 使用背景星星着色器程序
        GLES20.glUseProgram(mBgStarProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mBgStarProgram, "aPosition");// 粒子位置属性
        int mvpHandle = GLES20.glGetUniformLocation(mBgStarProgram, "uMVPMatrix");// MVP矩阵统一变量
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mMVPMatrix, 0);
        
        // 启用位置属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        
        // 设置粒子位置属性指针
        mBgStarBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mBgStarBuffer);
        
        // 绘制所有背景星星粒子（点精灵方式）
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, mBgStarCount);
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
    }
    
    /**
     * 绘制静态星星（带双纹理混合的脉冲动画）
     * @param time 时间（秒），用于控制混合动画
     */
    private void drawStaticStars(float time) {
        // 使用静态星星着色器程序
        GLES20.glUseProgram(mStaticStarProgram);
        
        // 获取着色器句柄
        int posHandle = GLES20.glGetAttribLocation(mStaticStarProgram, "aPosition");  // 位置属性
        int sizeHandle = GLES20.glGetAttribLocation(mStaticStarProgram, "aPointSize");// 点大小属性
        int mvpHandle = GLES20.glGetUniformLocation(mStaticStarProgram, "uMVPMatrix");// MVP矩阵
        int tex1Handle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTexture1");// 纹理1采样器
        int tex2Handle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTexture2");// 纹理2采样器
        int timeHandle = GLES20.glGetUniformLocation(mStaticStarProgram, "uTime");    // 时间统一变量
        
        // 静态星星使用单位矩阵（屏幕空间绘制）
        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, identity, 0);
        // 设置时间变量（控制脉冲动画）
        GLES20.glUniform1f(timeHandle, time);
        
        // 启用属性数组
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(sizeHandle);
        
        // 设置位置属性指针（每3个浮点数为一组，步长12字节）
        mStaticStarBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 12, mStaticStarBuffer);
        // 设置点大小属性指针
        mStaticStarBuffer.position(2);
        GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 12, mStaticStarBuffer);
        
        // 绑定纹理1到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexStaticStar);
        GLES20.glUniform1i(tex1Handle, 0);
        
        // 绑定纹理2到纹理单元1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexStaticStar2);
        GLES20.glUniform1i(tex2Handle, 1);
        
        // 绘制所有静态星星粒子
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STATIC_STAR_COUNT);
        
        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(sizeHandle);
    }
    
    /**
     * 创建FloatBuffer（原生内存）
     * 用于传递顶点数据到OpenGL（避免JVM内存拷贝）
     * @param data 浮点数组数据
     * @return 初始化后的FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        // 分配原生内存（每个浮点数4字节）
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder()); // 匹配CPU字节序
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);                      // 写入数据
        fb.position(0);                    // 重置位置到起始
        return fb;
    }
}