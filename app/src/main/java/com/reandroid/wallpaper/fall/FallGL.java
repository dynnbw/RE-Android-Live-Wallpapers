package com.reandroid.wallpaper.fall;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * 落叶壁纸 - OpenGL ES 2.0 核心实现类
 * 负责渲染落叶动画、水面涟漪效果，处理触摸交互
 * 继承自GLESScene（OpenGL ES场景基类）
 */
public class FallGL extends GLESScene {
    // 默认叶子数量
    private static final int DEFAULT_LEAVES_COUNT = 14;
    // 默认水滴数量（背景波纹）
    private static final int DEFAULT_RANDOM_DROPS = 10;
    // 叶子渲染大小
    private static final float LEAF_SIZE = 0.55f;
    // 日志标签
    private static final String TAG = "FallGL";

    // 随机数生成器，用于叶子/水滴的随机属性
    private Random mRandom = new Random();
    // 上一帧的时间戳（毫秒），用于计算帧间隔
    private long mLastTimeMs;

    // OpenGL程序句柄（顶点+片段着色器链接后的程序）
    private int mProgram;
    // 着色器变量句柄：顶点位置、纹理坐标、MVP矩阵、透明度、纹理采样器、颜色
    private int mPositionHandle, mTexCoordHandle, mMatrixHandle, mAlphaHandle, mSamplerHandle, mColorHandle;
    // 枫叶纹理数组（存储多个枫叶纹理ID）
    private int[] mLeafTextures;
    // 河床/水面背景纹理ID
    private int mRiverbedTexture;
    // 叶子对象数组
    private Leaf[] mLeaves;
    // 水滴对象数组（用于涟漪效果）
    private Drop[] mDrops;
    // 当前叶子数量（可通过设置调整）
    private int mLeafCount = DEFAULT_LEAVES_COUNT;
    // 上一次的叶子数量（用于检测数量变化）
    private int mLastLeafCount = DEFAULT_LEAVES_COUNT;

    // 矩阵数组：投影矩阵、视图矩阵、模型矩阵、MVP组合矩阵
    private float[] mProjectionMatrix = new float[16];
    private float[] mViewMatrix = new float[16];
    private float[] mModelMatrix = new float[16];
    private float[] mMVPMatrix = new float[16];
    // 帧间隔时间（秒）
    private float mDeltaTime;
    // 非GL资源初始化完成标志
    private boolean mInitialized = false;
    // GL资源初始化完成标志
    private boolean mGLInitialized = false;
    // 帧计数器（用于调试首帧渲染）
    private int mFrameCount = 0;
    // 壁纸水平偏移量（适配多屏壁纸滑动）
    private float mXOffset = 0.5f;
    // 屏幕旋转标志：0=竖屏，1=横屏
    private int mRotate = 0;
    // 水面网格的宽度/高度（用于涟漪效果的网格分辨率）
    private int mMeshWidth;
    private int mMeshHeight;
    // GL坐标系宽度/高度
    private float mGlWidth = 2.0f;
    private float mGlHeight = 3.333f;
    // 背景纹理缩放比例
    private float mBackgroundScale = 0.75f;

    // 水面涟漪动画参数
    // 涟漪时间（用于控制涟漪动画进度）
    private float mRippleTime = 0.0f;
    // 涟漪动画速度
    private static final float RIPPLE_SPEED = 0.3f;

    // 水面网格相关（涟漪效果的核心）
    // 网格分辨率（越高涟漪越精细）
    private static final int MESH_RESOLUTION = 48;
    // 水面网格顶点缓冲区
    private FloatBuffer mWaterMeshVertexBuffer;
    // 水面网格纹理坐标缓冲区
    private FloatBuffer mWaterMeshTexCoordBuffer;
    // 水面网格索引数组（三角面绘制）
    private short[] mWaterMeshIndices;
    // 水面网格顶点数量
    private int mWaterMeshVertexCount;
    // 水面网格索引数量
    private int mWaterMeshIndexCount;
    // 水面网格原始顶点数据（用于涟漪变形）
    private float[] mWaterMeshVertices;
    // 水面网格原始纹理坐标
    private float[] mWaterMeshTexCoordsBase;
    // 水面网格专用水滴数组（存储涟漪信息）
    private Drop[] mWaterDrops;
    // 最大涟漪水滴数量（触摸/落叶产生的涟漪）
    private static final int DEFAULT_WATER_MESH_DROPS = 10;
    // 当前涟漪水滴数量（来自设置）
    private int mWaterDropCount = DEFAULT_WATER_MESH_DROPS;
    // 上一次的涟漪水滴数量（用于检测变化）
    private int mLastWaterDropCount = DEFAULT_WATER_MESH_DROPS;

    /**
     * 叶子数据模型
     * 存储单片叶子的位置、大小、旋转、速度等属性
     */
    private static class Leaf {
        // 坐标（GL坐标系）
        float x, y;
        // 缩放比例
        float scale;
        // 旋转角度
        float angle;
        // 旋转速度
        float spin;
        // 高度（空中/水面）
        float altitude;
        // 水平/垂直移动速度
        float deltaX, deltaY;
        // 叶子纹理索引（对应mLeafTextures数组）
        int leafTextureIndex;
        // 是否已产生涟漪（接触水面时标记）
        boolean rippled;

        /**
         * 初始化叶子属性
         * 
         * @param random          随机数生成器
         * @param leafTexCount    纹理总数
         * @param startAboveWater 是否从空中开始（true=空中，false=水面）
         */
        void init(Random random, int leafTexCount, boolean startAboveWater) {
            // 随机选择纹理
            leafTextureIndex = random.nextInt(leafTexCount);
            // 随机初始X坐标
            x = (random.nextFloat() - 0.5f) * 4.0f;
            // 随机初始Y坐标
            y = (random.nextFloat() - 0.5f) * 3.333f;
            // 随机缩放（0.4~0.5）
            scale = 0.4f + random.nextFloat() * 0.1f;
            // 随机初始旋转角度
            angle = random.nextFloat() * 360.0f;
            // 随机旋转速度
            spin = (random.nextFloat() - 0.5f) * 0.016f;
            // 初始高度（空中=0.7，水面=-1.0）
            altitude = startAboveWater ? 0.7f : -1.0f;
            // 随机水平速度
            deltaX = (random.nextFloat() - 0.5f) * 0.02f;
            // 垂直下落速度（0.036~0.044）
            deltaY = -(0.036f + random.nextFloat() * 0.008f);
            // 涟漪标记（空中=false，水面=true）
            rippled = startAboveWater ? false : true;
        }
    }

    /**
     * 水滴数据模型（用于涟漪效果）
     * 存储涟漪的振幅、扩散范围、坐标
     */
    private static class Drop {
        // 起始振幅
        float ampS;
        // 当前有效振幅
        float ampE;
        // 扩散范围
        float spread;
        // 坐标（网格坐标系）
        float x, y;

        /**
         * 初始化水滴属性
         */
        void init() {
            ampS = 0;
            ampE = 0;
            spread = 1;
        }

        /**
         * 更新水滴（涟漪）状态
         * 
         * @param dt 帧间隔时间
         */
        void update(float dt) {
            // 如果有初始振幅，扩散范围增加
            if (ampS > 0) {
                spread += 30.0f * dt;
                // 振幅随扩散范围衰减（原版公式）
                // ampE = ampS / spread;
                // 直接替换原有 ampE 计算逻辑
                ampE = ampS * (float) Math.exp(-0.02f * spread) / (1 + 0.01f * spread);
            }
        }
    }

    /**
     * 构造方法
     * 
     * @param width  显示宽度
     * @param height 显示高度
     */
    public FallGL(int width, int height) {
        super(width, height);
        // 初始化上一帧时间戳
        mLastTimeMs = System.currentTimeMillis();
        Log.d(TAG, "FallGL创建: " + width + "x" + height);
    }

    /**
     * 场景创建时调用（非GL线程）
     * 初始化非GL资源（叶子、水滴、矩阵等）
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate()调用, mResources=" + mResources + ", 宽度=" + mWidth + ", 高度=" + mHeight);
        // 准备非GL资源（GL资源在start()中初始化）
        prepareNonGLResources();
        if (mResources == null) {
            Log.w(TAG, "onCreate: 资源为空，GL初始化将在GL线程上下文就绪时执行");
        }
    }

    @Override
    public void release() {
        // 释放枫叶纹理
        if (mLeafTextures != null && mLeafTextures.length > 0) {
            GLES20.glDeleteTextures(mLeafTextures.length, mLeafTextures, 0);
            mLeafTextures = null;
        }

        // 释放背景纹理
        if (mRiverbedTexture != 0) {
            int[] tex = new int[] { mRiverbedTexture };
            GLES20.glDeleteTextures(1, tex, 0);
            mRiverbedTexture = 0;
        }

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mWaterMeshVertexBuffer = null;
        mWaterMeshTexCoordBuffer = null;
        mGLInitialized = false;
    }

    /**
     * 准备非GL资源（叶子数组、水滴数组、矩阵等）
     */
    private void prepareNonGLResources() {
        // 避免重复初始化
        if (mInitialized)
            return;
        mInitialized = true;

        // 从设置中获取叶子数量（默认14）
        mLeafCount = com.reandroid.wallpaper.settings.WallpaperSettings
                .getFallLeafCount(DEFAULT_LEAVES_COUNT);
        mLastLeafCount = mLeafCount;

        // 初始化叶子数组
        mLeaves = new Leaf[mLeafCount];
        for (int i = 0; i < mLeafCount; i++) {
            mLeaves[i] = new Leaf();
            mLeaves[i].init(mRandom, getLeafTextureCount(), false);
        }
        Log.d(TAG, "初始化" + mLeafCount + "片叶子");

        // 初始化水滴数组
        mDrops = new Drop[DEFAULT_RANDOM_DROPS];
        for (int i = 0; i < DEFAULT_RANDOM_DROPS; i++) {
            mDrops[i] = new Drop();
            mDrops[i].init();
        }
        Log.d(TAG, "初始化" + DEFAULT_RANDOM_DROPS + "个水滴");

        // 判断屏幕旋转状态
        mRotate = mWidth > mHeight ? 1 : 0;
        float width = mWidth > mHeight ? mHeight : mWidth;
        float height = mWidth > mHeight ? mWidth : mHeight;
        // 计算GL坐标系高度
        mGlHeight = 2.0f * height / width;

        // 初始化水面网格水滴数组（可配置最大数量）
        mWaterDropCount = Math.max(1,
                com.reandroid.wallpaper.settings.WallpaperSettings
                        .getFallMaxDrops(DEFAULT_WATER_MESH_DROPS));
        mLastWaterDropCount = mWaterDropCount;
        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            mWaterDrops[i] = new Drop();
            mWaterDrops[i].init();
        }
        // 创建水面网格（用于涟漪渲染）
        createWaterMesh();
        Log.d(TAG, "水面网格创建完成");

        // 初始化视图矩阵（相机位置）
        Matrix.setLookAtM(mViewMatrix, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        // 初始化投影矩阵（正交投影），Y轴缩放0.5   避免边缘异常
        float yScale = 0.9f;
        Matrix.orthoM(mProjectionMatrix, 0, -1, 1, -mGlHeight / 2.0f * yScale, mGlHeight / 2.0f * yScale, 0.1f, 10.0f);
        Log.d(TAG, "非GL矩阵初始化完成，Y轴缩放: " + yScale);
    }

    /**
     * 屏幕尺寸变化时调用
     * 
     * @param width  新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        // 更新旋转状态
        mRotate = width > height ? 1 : 0;
        mGlWidth = 2.0f;
        mGlHeight = 2.0f * (float)height / (float)width;
        // 统一Y轴缩放，和初始化一致
        float yScale = 0.9f;
        Matrix.orthoM(mProjectionMatrix, 0, -1, 1, -mGlHeight / 2.0f * yScale, mGlHeight / 2.0f * yScale, 0.1f, 10.0f);
        // 重新创建水面网格（适配新尺寸）
        createWaterMesh();
    }

    /**
     * 创建水面网格（用于涟漪效果的三角网格）
     * 生成顶点、纹理坐标、索引数据，并初始化缓冲区
     */
    private void createWaterMesh() {
        // 获取GL坐标系高度
        float height = mGlHeight;
        // 计算网格分辨率（宽/高方向）
        int wResolution = MESH_RESOLUTION;
        int hResolution = (int) (MESH_RESOLUTION * height / 2.0f);

        // 分辨率补偿（避免边缘问题）
        wResolution += 2;
        hResolution += 2;

        // 存储顶点、纹理坐标、索引的临时列表
        java.util.List<Float> vertices = new java.util.ArrayList<>();
        java.util.List<Float> texCoords = new java.util.ArrayList<>();

        // 生成网格顶点
        for (int y = 0; y <= hResolution; y++) {
            // 计算Y轴偏移（GL坐标系）
            float yOffset = (((float) y / hResolution) * 2.0f - 1.0f) * height / 2.0f;
            for (int x = 0; x <= wResolution; x++) {
                // 计算X轴坐标（GL坐标系）
                float xPos = ((float) x / wResolution) * 2.0f - 1.0f;
                // 添加顶点（x,y,z）
                vertices.add(xPos);
                vertices.add(yOffset);
                vertices.add(0.0f);
                // 添加纹理坐标（u,v）
                texCoords.add((float) x / wResolution);
                texCoords.add((float) y / hResolution);
            }
        }

        // 生成网格索引（三角面列表）
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int y = 0; y < hResolution; y++) {
            int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                int index = yOffset + x;
                int iWR1 = index + wResolution + 1;
                // 每个网格单元生成两个三角面
                indices.add(index);
                indices.add(index + 1);
                indices.add(iWR1);

                indices.add(index + 1);
                indices.add(iWR1 + 1);
                indices.add(iWR1);
            }
        }

        // 记录网格数据数量
        mWaterMeshVertexCount = vertices.size() / 3;
        mWaterMeshIndexCount = indices.size();
        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;

        // 将顶点列表转为数组并初始化缓冲区
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        // 保存原始顶点数据（用于涟漪变形）
        mWaterMeshVertices = vertexArray.clone();
        // 创建顶点缓冲区（DirectBuffer，GL高效访问）
        mWaterMeshVertexBuffer = ByteBuffer.allocateDirect(vertexArray.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mWaterMeshVertexBuffer.put(vertexArray).position(0);

        // 将纹理坐标列表转为数组并初始化缓冲区
        float[] texCoordArray = new float[texCoords.size()];
        for (int i = 0; i < texCoords.size(); i++) {
            texCoordArray[i] = texCoords.get(i);
        }
        // 保存原始纹理坐标（用于涟漪变形）
        mWaterMeshTexCoordsBase = texCoordArray.clone();
        // 创建纹理坐标缓冲区
        mWaterMeshTexCoordBuffer = ByteBuffer.allocateDirect(texCoordArray.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mWaterMeshTexCoordBuffer.put(texCoordArray).position(0);

        // 将索引列表转为short数组（GL ES 2.0仅支持short索引）
        mWaterMeshIndices = new short[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            mWaterMeshIndices[i] = (short) (int) indices.get(i);
        }

        Log.d(TAG, "水面网格: " + mWaterMeshVertexCount + " 个顶点, " + mWaterMeshIndexCount + " 个索引");
    }

    /**
     * GL线程启动时调用（EGL上下文就绪）
     * 初始化GL资源：着色器程序、纹理等
     */
    @Override
    public void start() {
        // 避免重复初始化
        if (!mGLInitialized) {
            mGLInitialized = true;

            // 设置GL清屏颜色为黑色（匹配原版RenderScript实现）
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            Log.d(TAG, "GL: 设置清屏颜色为黑色 (0, 0, 0)");
            // 启用混合（透明效果）
            GLES20.glEnable(GLES20.GL_BLEND);
            // 设置混合模式（源Alpha，目标1-源Alpha）
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            // 设置视口大小
            GLES20.glViewport(0, 0, mWidth, mHeight);
            Log.d(TAG, "GL: 设置视口为 " + mWidth + "x" + mHeight);

            Log.d(TAG, "GL线程: 创建着色器程序...");
            // 创建并链接着色器程序
            createProgram();

            Log.d(TAG, "GL线程: 加载纹理...");
            try {
                // 加载枫叶纹理
                mLeafTextures = loadLeafTextures();
                Log.d(TAG, "加载" + mLeafTextures.length + "个枫叶纹理");
            } catch (Exception e) {
                Log.e(TAG, "GL线程加载枫叶纹理失败", e);
                // 加载失败时创建占位纹理
                mLeafTextures = createPlaceholderLeafTextures();
                Log.d(TAG, "创建" + mLeafTextures.length + "个枫叶占位纹理");
            }

            try {
                // 加载河床/水面背景纹理
                mRiverbedTexture = loadTexture(R.drawable.pond);
                Log.d(TAG, "河床纹理加载完成: " + mRiverbedTexture);
            } catch (Exception e) {
                Log.e(TAG, "GL线程加载河床纹理失败", e);
                // 加载失败时创建占位纹理（蓝色）
                mRiverbedTexture = createPlaceholderTexture(256, 256, Color.parseColor("#4A6FA5"));
                Log.d(TAG, "创建河床占位纹理: " + mRiverbedTexture);
            }

            Log.d(TAG, "GL初始化完成");
        }
    }

    /**
     * 处理壁纸引擎命令（如触摸/点击）
     * 
     * @param action 命令动作（如"tap"）
     * @param x      坐标X
     * @param y      坐标Y
     * @param z      预留参数
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        // 打印命令日志
        Log.i(TAG, "onCommand: 动作=" + action + " x=" + x + " y=" + y + " z=" + z);
        // 处理点击命令
        if (action != null && action.toLowerCase().contains("tap")) {
            // 将点击转为水滴/涟漪
            addDrop(x, y);
        }
    }

    /**
     * 设置壁纸偏移（多屏滑动时调用）
     * 
     * @param xOffset 水平偏移量
     * @param yOffset 垂直偏移量（未使用）
     * @param xPixels 像素偏移X（未使用）
     * @param yPixels 像素偏移Y（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    /**
     * 创建并链接OpenGL着色器程序
     * 包含顶点着色器和片段着色器的编译、链接
     */
    private void createProgram() {
        // 顶点着色器源码：处理MVP矩阵，传递纹理坐标
        String vertexShader = RawResourceLoader.readRawText(mResources, R.raw.fall_vs);
        // 片段着色器源码：处理纹理采样、透明度、颜色混合
        String fragmentShader = RawResourceLoader.readRawText(mResources, R.raw.fall_fs);

        // 编译顶点/片段着色器
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);

        // 编译失败则返回
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "着色器编译失败!");
            return;
        }

        // 创建程序并附加着色器
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vs);
        GLES20.glAttachShader(mProgram, fs);
        // 链接程序
        GLES20.glLinkProgram(mProgram);

        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "程序链接失败: " + GLES20.glGetProgramInfoLog(mProgram));
        }

        // 获取着色器变量句柄（后续渲染时使用）
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");

        Log.d(TAG, "程序创建完成。句柄: 位置=" + mPositionHandle + ", 纹理坐标=" + mTexCoordHandle +
                ", 矩阵=" + mMatrixHandle + ", 透明度=" + mAlphaHandle + ", 采样器=" + mSamplerHandle + ", 颜色=" + mColorHandle);

        // 编译完成后删除着色器（释放资源）
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
    }

    /**
     * 编译着色器
     * 
     * @param shaderType 着色器类型（顶点/片段）
     * @param source     着色器源码
     * @return int 编译后的着色器ID（0=失败）
     */
    private int compileShader(int shaderType, String source) {
        // 创建着色器
        int shader = GLES20.glCreateShader(shaderType);
        // 设置源码并编译
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        // 检查编译状态
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES20.GL_TRUE) {
            String type = (shaderType == GLES20.GL_VERTEX_SHADER) ? "顶点" : "片段";
            Log.e(TAG, type + "着色器编译失败: " + GLES20.glGetShaderInfoLog(shader));
            // 编译失败则删除着色器
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /**
     * 加载纹理资源
     * 
     * @param resourceId 资源ID
     * @return int 纹理ID（0=失败）
     */
    private int loadTexture(int resourceId) {
        // 纹理加载选项（不缩放，ARGB8888格式）
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        // 解码资源为Bitmap
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        if (bitmap == null) {
            Log.e(TAG, "解码资源失败: " + resourceId);
            return 0;
        }

        Log.d(TAG, "加载纹理: " + resourceId + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");

        // 生成纹理ID
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        // 绑定纹理（后续操作针对该纹理）
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        // 设置纹理过滤模式（最近邻）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        // 设置纹理环绕模式（夹紧到边缘）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // 将Bitmap数据上传到GL纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

        // 检查GL错误
        int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "加载纹理GL错误: " + glError);
        }

        // 释放Bitmap资源
        bitmap.recycle();
        return texture[0];
    }

    /**
     * 创建占位纹理（纹理加载失败时使用）
     * 
     * @param width  纹理宽度
     * @param height 纹理高度
     * @param color  纹理颜色
     * @return int 纹理ID
     */
    private int createPlaceholderTexture(int width, int height, int color) {
        Log.d(TAG, "创建占位纹理: " + width + "x" + height);
        // 创建指定大小的Bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // 填充指定颜色
        canvas.drawColor(color);

        // 生成并绑定纹理
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        // 设置过滤模式
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        // 上传Bitmap数据
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        // 释放Bitmap
        bitmap.recycle();
        return texture[0];
    }

    /**
     * 获取枫叶纹理数量
     * 
     * @return int 纹理数量
     */
    private int getLeafTextureCount() {
        // 如果纹理已加载，返回实际数量
        if (mLeafTextures != null) {
            return mLeafTextures.length;
        }
        // 未加载时，根据设置判断（绿色叶子=20个，默认=14个）
        boolean greenLeavesEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isGreenLeavesEnabled(false);
        return greenLeavesEnabled ? 20 : 14;
    }

    /**
     * 加载枫叶纹理数组
     * 根据设置判断是否加载绿色叶子纹理
     * 
     * @return int[] 纹理ID数组
     */
    private int[] loadLeafTextures() {
        // 检查是否启用绿色树叶
        boolean greenLeavesEnabled = com.reandroid.wallpaper.settings.WallpaperSettings
                .isGreenLeavesEnabled(false);

        int[] leafResourceIds;
        if (greenLeavesEnabled) {
            // 加载20个纹理（leaves_0 ~ leaves_19）
            leafResourceIds = new int[] {
                    R.drawable.leaves_0, R.drawable.leaves_1, R.drawable.leaves_2, R.drawable.leaves_3,
                    R.drawable.leaves_4, R.drawable.leaves_5, R.drawable.leaves_6, R.drawable.leaves_7,
                    R.drawable.leaves_8, R.drawable.leaves_9, R.drawable.leaves_10, R.drawable.leaves_11,
                    R.drawable.leaves_12, R.drawable.leaves_13, R.drawable.leaves_14, R.drawable.leaves_15,
                    R.drawable.leaves_16, R.drawable.leaves_17, R.drawable.leaves_18, R.drawable.leaves_19
            };
        } else {
            // 加载14个纹理（leaves_0 ~ leaves_13）
            leafResourceIds = new int[] {
                    R.drawable.leaves_0, R.drawable.leaves_1, R.drawable.leaves_2, R.drawable.leaves_3,
                    R.drawable.leaves_4, R.drawable.leaves_5, R.drawable.leaves_6, R.drawable.leaves_7,
                    R.drawable.leaves_8, R.drawable.leaves_9, R.drawable.leaves_10, R.drawable.leaves_11,
                    R.drawable.leaves_12, R.drawable.leaves_13
            };
        }

        // 加载每个纹理
        int[] textures = new int[leafResourceIds.length];
        for (int i = 0; i < leafResourceIds.length; i++) {
            textures[i] = loadLeafTexture(leafResourceIds[i]);
        }
        Log.d(TAG, "加载" + textures.length + "个枫叶纹理 (绿色叶子: " + greenLeavesEnabled + ")");
        return textures;
    }

    /**
     * 加载单个枫叶纹理（使用LINEAR过滤和Mipmap，提升高清纹理效果）
     * 
     * @param resourceId 资源ID
     * @return int 纹理ID
     */
    private int loadLeafTexture(int resourceId) {
        // 纹理加载选项
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeResource(mResources, resourceId, options);
        if (bitmap == null) {
            Log.e(TAG, "解码枫叶纹理资源失败: " + resourceId);
            return 0;
        }

        Log.d(TAG, "加载枫叶纹理: " + resourceId + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");

        // 生成并绑定纹理
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);

        // 设置高清纹理过滤模式（线性+Mipmap）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 上传纹理数据并生成Mipmap
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);

        // 检查GL错误
        int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "加载枫叶纹理GL错误: " + glError);
        }

        // 释放Bitmap
        bitmap.recycle();
        return texture[0];
    }

    /**
     * 创建枫叶占位纹理（14个不同颜色的64x64纹理）
     * 
     * @return int[] 纹理ID数组
     */
    private int[] createPlaceholderLeafTextures() {
        int[] textures = new int[14];
        // 14种暖色调（模拟枫叶颜色）
        int[] colors = {
                Color.parseColor("#8B4513"), Color.parseColor("#A0522D"),
                Color.parseColor("#8B6914"), Color.parseColor("#B8860B"),
                Color.parseColor("#D2691E"), Color.parseColor("#CD853F"),
                Color.parseColor("#DEB887"), Color.parseColor("#F4A460"),
                Color.parseColor("#FF8C00"), Color.parseColor("#FFA500"),
                Color.parseColor("#FFD700"), Color.parseColor("#FFFF00"),
                Color.parseColor("#FF6347"), Color.parseColor("#FF4500")
        };
        // 创建每个占位纹理
        for (int i = 0; i < 14; i++) {
            textures[i] = createPlaceholderTexture(64, 64, colors[i]);
        }
        return textures;
    }

    /**
     * 绘制帧（GL线程主渲染方法）
     * 
     * @param timeMs 当前时间戳（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        // 计算帧间隔时间（秒）
        long now = System.currentTimeMillis();
        mDeltaTime = Math.min((now - mLastTimeMs) * 0.001f, 0.2f);
        mLastTimeMs = now;
        // 更新涟漪时间
        mRippleTime += mDeltaTime * RIPPLE_SPEED;

        // 首帧日志（调试用）
        if (mFrameCount == 0) {
            Log.d(TAG, "首次drawFrame()开始");
        }
        mFrameCount++;

        // 清屏（颜色+深度缓冲区）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        // 如果资源未初始化，但现在资源可用，则执行GL初始化
        if (!mInitialized) {
            if (mResources != null) {
                Log.d(TAG, "drawFrame中资源可用，通过start()初始化GL资源");
                start();
            } else {
                // 无资源则无法渲染，返回
                return;
            }
        }

        // 程序未初始化则返回
        if (mProgram == 0) {
            Log.w(TAG, "程序未初始化");
            return;
        }

        // 使用当前程序
        GLES20.glUseProgram(mProgram);

        // 检查配置变化（最大涟漪数量）
        ensureWaterDropCount();

        // 更新水滴/涟漪状态
        updateDrops();
        // 更新叶子状态（位置、旋转、高度等）
        updateLeaves();

        // 绘制水面（带涟漪动画）
        drawWaterQuad();

        // 绘制所有叶子
        for (Leaf leaf : mLeaves) {
            drawLeaf(leaf);
        }

        // 检查GL错误
        int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "drawFrame中GL错误: " + glError);
        }
    }

    /**
     * 绘制水面（带涟漪变形的网格）
     */
    private void drawWaterQuad() {
        // 更新水面网格（应用涟漪变形）
        updateWaterMesh();

        // 初始化模型矩阵（单位矩阵）
        Matrix.setIdentityM(mModelMatrix, 0);
        // 计算MV矩阵（视图*模型）
        float[] mv = new float[16];
        Matrix.multiplyMM(mv, 0, mViewMatrix, 0, mModelMatrix, 0);
        // 计算MVP矩阵（投影*MV）
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mv, 0);
        // 上传MVP矩阵到着色器
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        // 设置透明度为1（不透明）
        GLES20.glUniform1f(mAlphaHandle, 1.0f);
        // 设置颜色为白色（不修改纹理颜色）
        GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        mWaterMeshVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 12, mWaterMeshVertexBuffer);

        // 启用纹理坐标属性
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        mWaterMeshTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mWaterMeshTexCoordBuffer);

        // 绑定河床纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mRiverbedTexture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        // 创建索引缓冲区并绘制三角面
        java.nio.ShortBuffer indexBuffer = java.nio.ByteBuffer.allocateDirect(mWaterMeshIndices.length * 2)
                .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
        indexBuffer.put(mWaterMeshIndices).position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mWaterMeshIndexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer);

        // 禁用属性（优化性能）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /**
     * 更新水面网格（应用涟漪变形到纹理坐标）
     */
    private void updateWaterMesh() {
        // 无顶点数据则返回
        if (mWaterMeshVertices == null)
            return;
        if (mWaterDrops == null || mWaterDropCount <= 0)
            return;

        // 更新水滴/涟漪的振幅
        for (Drop drop : mWaterDrops) {
            drop.spread += 30.0f * mDeltaTime;
            // 原版衰减公式
            drop.ampE = drop.ampS / drop.spread;
        }

        // 计算网格分辨率
        float height = mGlHeight;
        int wResolution = MESH_RESOLUTION + 2;
        int hResolution = (int) (MESH_RESOLUTION * height / 2.0f) + 2;

        // 存储变形后的纹理坐标
        float[] deformedTex = new float[mWaterMeshTexCoordsBase.length];

        // 遍历所有网格顶点，计算变形后的纹理坐标
        for (int y = 0; y < hResolution; y++) {
            for (int x = 0; x < wResolution; x++) {
                // 获取顶点坐标
                int vertexIdx = (y * wResolution + x) * 3;
                float xPos = mWaterMeshVertices[vertexIdx];
                float yPos = mWaterMeshVertices[vertexIdx + 1];

                float posX = xPos;
                float posY = yPos;

                // 计算纹理坐标基础值
                float varU = posX + 1.0f;
                float varV = posY + (mGlHeight * 0.5f);
                float dxMul = 1.0f;

                // 适配屏幕旋转
                if (mRotate < 1) { // 竖屏
                    varU *= 0.25f;
                    float vScale = 0.33f * (3.333f / mGlHeight);
                    varV *= vScale;
                    varU += mXOffset * 0.5f;
                    posX += mXOffset * 2.0f;
                } else { // 横屏
                    varU *= 0.5f;
                    float vScale = 0.3125f * (3.333f / mGlHeight);
                    varV *= vScale;
                    dxMul = 2.5f;
                }

                // 缩放纹理坐标（放大背景，避免边缘露黑）
                varU = 0.5f + (varU - 0.5f) * mBackgroundScale;
                varV = 0.5f + (varV - 0.5f) * mBackgroundScale;

                // 转换为网格坐标系
                float scaleX = (mMeshWidth - 1) * 0.5f;
                float scaleY = (mMeshHeight - 1) * 0.5f;
                float posScaledX = (posX + 1.0f) * scaleX;
                float posScaledY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

                // 应用所有水滴/涟漪的变形
                for (Drop drop : mWaterDrops) {
                    float dx = drop.x - posScaledX;
                    float dy = drop.y - posScaledY;
                    dx *= dxMul;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    // 仅处理涟漪范围内的顶点
                    if (dist < drop.spread) {
                        // 计算涟漪振幅
                        float amp = drop.ampE * 0.12f * dist;
                        amp /= (drop.spread * drop.spread);
                        amp *= (float) Math.sin(drop.spread - dist);
                        // 变形纹理坐标（产生涟漪效果）
                        varU += dx * amp;
                        varV += dy * amp;
                    }
                }

                // 保存变形后的纹理坐标
                int texIdx = (y * wResolution + x) * 2;
                deformedTex[texIdx] = varU;
                deformedTex[texIdx + 1] = varV;
            }
        }

        // 更新纹理坐标缓冲区
        mWaterMeshTexCoordBuffer.clear();
        mWaterMeshTexCoordBuffer.put(deformedTex).position(0);
    }

    /**
     * 更新水滴/涟漪状态
     */
    private void updateDrops() {
        // 更新所有水滴
        for (Drop drop : mDrops) {
            drop.update(mDeltaTime);
        }
        // 随机生成水滴（30%概率）
        if (mRandom.nextFloat() < 0.3f) {
            int idx = mRandom.nextInt(DEFAULT_RANDOM_DROPS);
            Drop drop = mDrops[idx];
            drop.ampS = 1.0f;
            drop.spread = 0;
            drop.x = (mRandom.nextFloat() - 0.5f) * 2.0f;
            drop.y = (mRandom.nextFloat() - 0.5f) * 3.0f;
        }
    }

    /**
     * 检查最大涟漪数量是否变化，必要时重建水滴数组
     */
    private void ensureWaterDropCount() {
        int desired = Math.max(1,
                com.reandroid.wallpaper.settings.WallpaperSettings
                        .getFallMaxDrops(DEFAULT_WATER_MESH_DROPS));
        if (desired == mLastWaterDropCount && mWaterDrops != null) {
            return;
        }

        mWaterDropCount = desired;
        mLastWaterDropCount = desired;

        mWaterDrops = new Drop[mWaterDropCount];
        for (int i = 0; i < mWaterDropCount; i++) {
            mWaterDrops[i] = new Drop();
            mWaterDrops[i].init();
        }

        Log.d(TAG, "更新最大涟漪水滴数量: " + mWaterDropCount);
    }

    /**
     * 更新叶子状态（位置、旋转、高度、重生等）
     */
    private void updateLeaves() {
        // 从设置中获取目标叶子数量
        int desiredCount = com.reandroid.wallpaper.settings.WallpaperSettings
                .getFallLeafCount(DEFAULT_LEAVES_COUNT);
        // 如果数量变化，重新初始化叶子数组
        if (desiredCount != mLastLeafCount && desiredCount > 0) {
            mLeafCount = desiredCount;
            mLastLeafCount = desiredCount;
            mLeaves = new Leaf[mLeafCount];
            for (int i = 0; i < mLeafCount; i++) {
                mLeaves[i] = new Leaf();
                mLeaves[i].init(mRandom, getLeafTextureCount(), false);
            }
            Log.d(TAG, "更新叶子数量为 " + mLeafCount);
        }

        // 更新每片叶子的状态
        for (Leaf leaf : mLeaves) {
            if (leaf.altitude <= 0.0f) { // 叶子接触水面
                // 未产生涟漪则生成涟漪
                if (!leaf.rippled) {
                    genLeafDrop(leaf, 1.5f);
                    leaf.rippled = true;
                    leaf.spin *= 0.25f; // 水面处减速旋转
                }

                // 水面移动
                leaf.x += leaf.deltaX * mDeltaTime;
                leaf.y += leaf.deltaY * mDeltaTime;
                leaf.angle += leaf.spin;

                // 边界检测：只允许叶子在屏幕下侧或上侧消失时重生，左右侧不重生
                float margin = LEAF_SIZE * 0.6f; // 叶子边距（考虑最大缩放）
                float screenBottom = -mGlHeight / 2.0f - margin;
                float screenTop = mGlHeight / 2.0f + margin;
                if (leaf.y < screenBottom || leaf.y > screenTop) {
                    leaf.init(mRandom, getLeafTextureCount(), true);
                }
            } else { // 叶子在空中
                // 下降（高度降低）
                leaf.altitude -= 0.15f * mDeltaTime;
                // 空中旋转更快
                leaf.angle += leaf.spin * 2.0f;
            }
        }
    }

    /**
     * 叶子接触水面时生成涟漪
     * 
     * @param leaf      叶子实例
     * @param amplitude 涟漪振幅
     */
    private void genLeafDrop(Leaf leaf, float amplitude) {
        // 计算叶子落点在网格坐标系的位置，完全复用updateWaterMesh的映射逻辑
        float posX = leaf.x;
        float posY = leaf.y;
        float dxMul = 1.0f;
        if (mRotate < 1) { // 竖屏
            posX += mXOffset * 2.0f;
        } else { // 横屏
            dxMul = 2.5f;
        }
        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float meshX = (posX + 1.0f) * scaleX;
        float meshY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;
        // 不做clamp，允许meshX/meshY超出网格，水波纹会在实际落点生成

        // 找到振幅最小的水滴（复用，避免超出最大数量）
        int iMin = 0;
        float minAmp = Float.MAX_VALUE;
        if (mWaterDrops == null || mWaterDropCount <= 0)
            return;
        for (int ct = 0; ct < mWaterDropCount; ct++) {
            if (mWaterDrops[ct].ampE < minAmp) {
                iMin = ct;
                minAmp = mWaterDrops[ct].ampE;
            }
        }

        // 初始化新涟漪
        Drop drop = mWaterDrops[iMin];
        drop.ampS = amplitude;
        drop.spread = 0;
        drop.x = meshX;
        drop.y = meshY;
        Log.i(TAG, "叶子落水，网格坐标: (" + meshX + ", " + meshY + "), 振幅: " + amplitude);
    }

    /**
     * 绘制单片叶子（包含阴影和主叶片）
     * 
     * @param leaf 叶子实例
     */
    private void drawLeaf(Leaf leaf) {
        if (leaf.altitude > 0.0f) { // 空中叶子绘制阴影
            // 计算阴影透明度（高度越高，阴影越淡）
            float shadowAlpha = 1.0f;
            if (leaf.altitude >= 0.4f) {
                shadowAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            shadowAlpha = clamp(shadowAlpha, 0.0f, 1.0f) * 0.15f;

            // 阴影位置偏移（随高度变化）
            float shadowOffset = leaf.altitude * 0.2f;
            int leafTexture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
            // 绘制阴影（黑色剪影）
            drawLeafQuad(leaf.x - shadowOffset, leaf.y - shadowOffset, leaf.scale, leaf.angle, leafTexture, shadowAlpha,
                    true);
        }

        // 绘制主叶片
        float leafAlpha = 1.0f;
        if (leaf.altitude > 0.0f) { // 空中叶子透明度渐变
            leafAlpha = 1.0f;
            if (leaf.altitude >= 0.4f) {
                leafAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            leafAlpha = clamp(leafAlpha, 0.0f, 1.0f);
        }
        int leafTexture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
        // 绘制主叶片（正常纹理）
        drawLeafQuad(leaf.x, leaf.y, leaf.scale, leaf.angle, leafTexture, leafAlpha, false);
    }

    /**
     * 绘制叶子的四边形（带位置、缩放、旋转）
     * 
     * @param x          X坐标
     * @param y          Y坐标
     * @param scale      缩放比例
     * @param rotation   旋转角度
     * @param texture    纹理ID
     * @param alpha      透明度
     * @param silhouette 是否绘制剪影（阴影）
     */
    private void drawLeafQuad(float x, float y, float scale, float rotation, int texture, float alpha,
            boolean silhouette) {
        // 应用壁纸水平偏移
        float drawX = x - mXOffset * 2.0f;
        // 初始化模型矩阵
        Matrix.setIdentityM(mModelMatrix, 0);
        // 平移
        Matrix.translateM(mModelMatrix, 0, drawX, y, 0);
        // 旋转
        Matrix.rotateM(mModelMatrix, 0, rotation, 0, 0, 1);
        // 等比缩放，无需补偿
        Matrix.scaleM(mModelMatrix, 0, scale, scale, 1);

        // 计算MV矩阵和MVP矩阵
        float[] mvMatrix = new float[16];
        Matrix.multiplyMM(mvMatrix, 0, mViewMatrix, 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mvMatrix, 0);

        // 绘制四边形
        drawQuad(-LEAF_SIZE, -LEAF_SIZE, LEAF_SIZE, LEAF_SIZE, texture, alpha, silhouette);
    }

    /**
     * 绘制四边形（通用方法）
     * 
     * @param left       左边界
     * @param top        上边界
     * @param right      右边界
     * @param bottom     下边界
     * @param texture    纹理ID
     * @param alpha      透明度
     * @param silhouette 是否绘制剪影
     */
    private void drawQuad(float left, float top, float right, float bottom, int texture, float alpha,
            boolean silhouette) {
        // 顶点数据（x,y,z, u,v）
        float[] vertices = {
                left, bottom, 0, 0.0f, 0.0f,
                right, bottom, 0, 1.0f, 0.0f,
                right, top, 0, 1.0f, 1.0f,
                left, top, 0, 0.0f, 1.0f
        };

        // 创建顶点缓冲区
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(vertices).position(0);

        // 上传MVP矩阵
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        // 上传透明度
        GLES20.glUniform1f(mAlphaHandle, alpha);
        // 设置颜色（剪影=黑色，正常=白色）
        if (silhouette) {
            GLES20.glUniform4f(mColorHandle, 0.0f, 0.0f, 0.0f, 1.0f);
        } else {
            GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer);

        // 启用纹理坐标属性
        vertexBuffer.position(3);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer);

        // 绑定纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        // 绘制四边形（三角扇）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        // 禁用属性（优化性能）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    /**
     * 添加水滴/涟漪（触摸事件调用）
     * 
     * @param x 触摸X坐标（像素）
     * @param y 触摸Y坐标（像素）
     */
    public void addDrop(int x, int y) {
        // 找到振幅最小的水滴（复用）
        int minIdx = 0;
        float minAmp = Float.MAX_VALUE;
        if (mWaterDrops == null || mWaterDropCount <= 0)
            return;
        for (int i = 0; i < mWaterDropCount; i++) {
            if (mWaterDrops[i].ampE < minAmp) {
                minAmp = mWaterDrops[i].ampE;
                minIdx = i;
            }
        }
        // 转换触摸坐标到GL坐标系
        float posX = ((float) x / (float) mWidth) * 2.0f - 1.0f;
        float posY = (1.0f - (float) y / (float) mHeight) * mGlHeight - (mGlHeight * 0.5f);
        if (mRotate == 0) { // 竖屏适配偏移
            posX += mXOffset * 2.0f;
        }

        // 转换到网格坐标系
        float scaleX = (mMeshWidth - 1) * 0.5f;
        float scaleY = (mMeshHeight - 1) * 0.5f;
        float dropX = (posX + 1.0f) * scaleX;
        float dropY = ((posY / (mGlHeight * 0.5f)) + 1.0f) * scaleY;

        // 初始化新涟漪
        Drop drop = mWaterDrops[minIdx];
        drop.ampS = 1.2f;
        drop.spread = 0;
        drop.x = dropX;
        drop.y = dropY;
        Log.i(TAG, "触摸产生涟漪，网格坐标: (" + dropX + ", " + dropY + ")");
    }

    /**
     * 数值夹紧（限制在min和max之间）
     * 
     * @param value 输入值
     * @param min   最小值
     * @param max   最大值
     * @return float 夹紧后的值
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}