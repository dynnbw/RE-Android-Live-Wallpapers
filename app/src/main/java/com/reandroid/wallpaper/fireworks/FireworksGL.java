package com.reandroid.wallpaper.fireworks;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * 烟花壁纸的OpenGL渲染核心类
 * 负责烟花粒子系统的创建、更新、绘制，触摸交互处理，背景纹理加载等
 */
public class FireworksGL extends GLESScene {
    // 数学常量：圆周率（简化值）
    private static final float PI = 3.14159265358f;
    // 粒子基础尺寸
    private static final float PARTICLE_SIZE = 50.0f;

    // 烟花爆炸时的粒子数量
    private static final int EXPLODE_FIREWORKS = 74;
    // 每组烟花的粒子步长（每组总粒子数）
    private static final int STRIDE = 75;
    // 常规烟花的最大组数
    private static final int MAX_NORMAL = 2;
    // 额外烟花（点击触发）的最大组数
    private static final int MAX_EXTRAS = 3;
    // 常规烟花粒子数组长度
    private static final int NORMAL_FIREWORKS = 150;
    // 额外烟花粒子数组长度
    private static final int EXTRAS_FIREWORKS = 225;

    // 粒子基础速度
    private static final float SPEED = 0.4f;
    // 速度放大系数
    private static final float SPEED_MAGNIFY = 2.0f;
    // 速度变化范围
    private static final float SPEED_VARIANCE = 0.2f;
    // 空气阻力系数
    private static final float RESISTANCE = 0.00098f;
    // 重力加速度
    private static final float GRAVITY = 0.0004f;
    // 常规粒子类型
    private static final int PARTICLE_NORMAL = 0;
    // 额外粒子类型
    private static final int PARTICLE_EXTRAS = 1;
    // 粒子透明度衰减基础值
    private static final float FADE = 0.000333f;
    // 衰减值变化范围
    private static final float FADE_VARIANCE = 0.2f;
    // 衰减放大系数
    private static final float FADE_MAGNIFY = 1.2f;
    // 烟花发射最大延迟时间（毫秒）
    private static final int MAX_DELAY = 10000;
    // 拖尾粒子最大数量
    private static final int MAX_TAILS = 500;
    // 拖尾生成比例阈值
    private static final float MAX_RATIO = 0.0f;
    // 闪光效果持续时间
    private static final int FLARE_DURATION = 200;

    /**
     * 烟花粒子实体类
     * 存储单个烟花粒子的状态和物理属性
     */
    private static class FireworkParticle {
        int time;           // 粒子最近一次更新时间
        int ds;             // 粒子移动距离累计
        int type;           // 粒子类型（常规/额外）
        boolean active;     // 粒子是否激活
        boolean hasTails;   // 是否生成拖尾
        int r;              // 颜色R通道值
        int g;              // 颜色G通道值
        int b;              // 颜色B通道值
        float life;         // 粒子生命值（0~1，0为消亡）
        float fade;         // 粒子衰减速度
        float posX;         // X坐标
        float posY;         // Y坐标
        float dx;           // X方向速度
        float dy;           // Y方向速度
    }

    /**
     * 拖尾粒子实体类
     * 存储烟花粒子的拖尾效果数据
     */
    private static class TailParticle {
        FireworkParticle root;  // 关联的主烟花粒子
        int time;               // 拖尾生成时间
        int type;               // 拖尾类型（普通拖尾/闪光）
        float life;             // 拖尾生命值
        float posX;             // X坐标
        float posY;             // Y坐标
    }

    // 随机数生成器（基于当前时间初始化）
    private final Random mRandom = new Random(System.currentTimeMillis());

    // 常规烟花粒子数组
    private final FireworkParticle[] gNormal = new FireworkParticle[NORMAL_FIREWORKS];
    // 额外烟花粒子数组（点击触发）
    private final FireworkParticle[] gExtras = new FireworkParticle[EXTRAS_FIREWORKS];
    // 拖尾粒子数组
    private final TailParticle[] gTails = new TailParticle[MAX_TAILS];

    // 当前系统时间（毫秒）
    private int gNow;

    // 基础初始化标记
    private boolean mInitialized = false;
    // OpenGL相关初始化标记
    private boolean mGLInitialized = false;

    // OpenGL着色器程序句柄
    private int mProgram;
    // 顶点位置属性句柄
    private int mPositionHandle;
    // 纹理坐标属性句柄
    private int mTexHandle;
    // 投影矩阵统一变量句柄
    private int mMatrixHandle;
    // 纹理采样器句柄
    private int mSamplerHandle;
    // 透明度统一变量句柄
    private int mAlphaHandle;
    // 颜色统一变量句柄
    private int mColorHandle;

    // 背景纹理ID
    private int mTexBackground;
    // 星星（粒子）纹理ID
    private int mTexStar;
    // 背景纹理宽高比
    private float mBackgroundAspect = 1.0f;
    // 缓存上次加载的背景URI（避免重复加载）
    private String mLastBackgroundUri = null;

    // 四边形顶点缓冲（用于绘制纹理矩形）
    private FloatBuffer mQuadBuffer;
    // 投影矩阵（正交投影）
    private final float[] mProjectionMatrix = new float[16];

    // X轴偏移量（适配壁纸滚动）
    private float mXOffset = 0.0f;

    // 触摸事件待处理标记
    private boolean mTapPending = false;
    // 触摸X坐标
    private int mTapX;
    // 触摸Y坐标
    private int mTapY;

    /**
     * 构造方法
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    public FireworksGL(int width, int height) {
        super(width, height);
    }

    /**
     * 生命周期方法：创建时初始化
     * 仅执行一次基础初始化
     */
    @Override
    protected void onCreate() {
        if (mInitialized) return;
        mInitialized = true;
        initialize();
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] { mTexBackground, mTexStar };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBackground = 0;
        mTexStar = 0;

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    /**
     * 尺寸调整回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (mGLInitialized) {
            // 更新OpenGL视口
            GLES20.glViewport(0, 0, mWidth, mHeight);
            // 更新正交投影矩阵
            Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        }
    }

    /**
     * 设置偏移量（适配壁纸滚动）
     * @param xOffset X轴偏移比例
     * @param yOffset Y轴偏移比例（未使用）
     * @param xPixels X轴偏移像素数（未使用）
     * @param yPixels Y轴偏移像素数（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    /**
     * 命令处理回调（处理触摸事件）
     * @param action 命令动作
     * @param x 坐标X
     * @param y 坐标Y
     * @param z 额外参数（未使用）
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        // 处理壁纸触摸事件
        if ("android.wallpaper.tap".equals(action)) {
            mTapX = x;
            mTapY = y;
            mTapPending = true;
        }
    }

    /**
     * 帧绘制方法（核心渲染逻辑）
     * @param timeMs 帧时间戳（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) return;
        // 延迟初始化OpenGL资源（避免提前初始化导致异常）
        if (!mGLInitialized) {
            Resources res = getResources();
            if (res == null) return;
            initGL(res);
        }

        // 检查并重新加载背景（如果URI变化）
        checkAndReloadBackground();

        // 更新当前时间
        gNow = (int) SystemClock.uptimeMillis();

        // 处理待执行的触摸事件
        if (mTapPending) {
            mTapPending = false;
            // 预览模式下无偏移，实际壁纸需叠加滚动偏移
            float tapOffset = isPreview() ? 0.0f : mXOffset;
            int x = (int) (mTapX + tapOffset * mWidth);
            addTap(x, mTapY);
        }

        // 计算最终的X轴偏移（适配壁纸滚动）
        float offset = isPreview() ? 0.0f : mXOffset;
        float offsetX = offset * mWidth;

        // 清空颜色缓冲区（准备绘制新帧）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用着色器程序
        GLES20.glUseProgram(mProgram);
        // 设置投影矩阵
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0);

        // 设置混合模式（背景：常规Alpha混合）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // 绘制背景
        drawBackground(mWidth, mHeight, offsetX);

        // 更新所有粒子状态（物理计算）
        update();

        // 设置混合模式（粒子：加法混合，实现发光效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // 绘制所有粒子和拖尾
        draw(offsetX);
    }

    /**
     * 初始化OpenGL相关资源
     * @param res 资源管理器
     */
    private void initGL(Resources res) {
        mGLInitialized = true;

        // 禁用深度测试（2D渲染不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合（实现透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置视口大小
        GLES20.glViewport(0, 0, mWidth, mHeight);
        // 创建正交投影矩阵（适配屏幕坐标）
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        // 顶点着色器代码
        String vs = RawResourceLoader.readRawText(res, R.raw.fireworks_vs);

        // 片段着色器代码
        String fs = RawResourceLoader.readRawText(res, R.raw.fireworks_fs);

        // 创建并链接着色器程序
        mProgram = createProgram(vs, fs);
        // 获取着色器属性/统一变量句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");

        // 创建四边形顶点缓冲（用于绘制纹理矩形）
        mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // 尝试加载自定义背景纹理
        android.content.Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
        if (ctx != null) {
            mTexBackground = loadCustomBackgroundTexture(ctx, res);
        }
        
        // 自定义背景加载失败时使用默认背景
        if (mTexBackground == 0) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeResource(res, R.drawable.background, bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                mBackgroundAspect = bounds.outWidth / (float) bounds.outHeight;
            }
            mTexBackground = loadTexture(res, R.drawable.background, false);
        }
        
        // 加载星星（粒子）纹理
        mTexStar = loadTexture(res, R.drawable.star, true);
    }
    
    /**
     * 检查并重新加载背景纹理（当URI变化时）
     */
    private void checkAndReloadBackground() {
        try {
            android.content.Context ctx = com.reandroid.wallpaper.gles.GLESWallpaper.getAppContext();
            if (ctx == null) return;
            
            // 获取当前保存的自定义背景URI
            String currentUri = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("fireworks_custom_background_uri", null);
            
            // 检查URI是否发生变化
            if ((mLastBackgroundUri == null && currentUri != null) ||
                (mLastBackgroundUri != null && !mLastBackgroundUri.equals(currentUri))) {
                
                android.util.Log.d("FireworksGL", "背景URI变化: " + mLastBackgroundUri + " -> " + currentUri);
                
                // 删除旧纹理（释放资源）
                if (mTexBackground != 0) {
                    int[] tex = new int[]{mTexBackground};
                    GLES20.glDeleteTextures(1, tex, 0);
                    mTexBackground = 0;
                }
                
                // 重新加载背景纹理
                Resources res = getResources();
                if (res != null) {
                    mTexBackground = loadCustomBackgroundTexture(ctx, res);
                    if (mTexBackground == 0) {
                        android.util.Log.d("FireworksGL", "自定义背景加载失败，使用默认背景");
                        // 加载默认背景
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        bounds.inScaled = false;
                        BitmapFactory.decodeResource(res, R.drawable.background, bounds);
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            mBackgroundAspect = bounds.outWidth / (float) bounds.outHeight;
                        }
                        mTexBackground = loadTexture(res, R.drawable.background, false);
                    } else {
                        android.util.Log.d("FireworksGL", "自定义背景加载成功");
                    }
                }
                
                // 更新缓存的URI
                mLastBackgroundUri = currentUri;
            }
        } catch (Exception e) {
            android.util.Log.e("FireworksGL", "背景检查/重新加载异常", e);
            e.printStackTrace();
        }
    }
    
    /**
     * 加载自定义背景纹理
     * @param ctx 上下文
     * @param res 资源管理器
     * @return 纹理ID（0表示加载失败）
     */
    private int loadCustomBackgroundTexture(android.content.Context ctx, Resources res) {
        try {
            if (ctx == null) {
                android.util.Log.e("FireworksGL", "上下文为空");
                return 0;
            }
            
            // 获取自定义背景URI
            String uriString = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("fireworks_custom_background_uri", null);
            mLastBackgroundUri = uriString; // 更新缓存
            
            if (uriString == null) {
                android.util.Log.d("FireworksGL", "未找到自定义背景URI");
                return 0;
            }
            
            android.util.Log.d("FireworksGL", "加载自定义背景: " + uriString);
            
            // 解析URI并打开输入流
            android.net.Uri uri = android.net.Uri.parse(uriString);
            java.io.InputStream stream = ctx.getContentResolver().openInputStream(uri);
            if (stream == null) {
                android.util.Log.e("FireworksGL", "无法打开URI输入流: " + uriString);
                return 0;
            }
            
            // 获取图片尺寸（不加载像素数据）
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeStream(stream, null, bounds);
            stream.close();
            
            // 计算宽高比
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                mBackgroundAspect = bounds.outWidth / (float) bounds.outHeight;
            }
            
            // 加载图片像素数据
            stream = ctx.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(stream);
            stream.close();
            
            if (bmp == null) return 0;
            
            // 创建OpenGL纹理
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            // 设置纹理过滤模式
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // 设置纹理环绕模式（边缘夹紧）
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // 将位图数据上传到纹理
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            // 释放位图资源
            bmp.recycle();
            
            return tex[0];
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * 初始化粒子系统
     * 创建粒子实例并初始化默认状态
     */
    private void initialize() {
        gNow = (int) SystemClock.uptimeMillis();
        
        // 初始化常规烟花粒子
        for (int i = 0; i < NORMAL_FIREWORKS; i++) {
            gNormal[i] = new FireworkParticle();
        }
        // 初始化额外烟花粒子
        for (int i = 0; i < EXTRAS_FIREWORKS; i++) {
            gExtras[i] = new FireworkParticle();
        }
        // 初始化拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            gTails[i] = new TailParticle();
            initTails(gTails[i]);
        }

        // 初始化默认的常规烟花组
        for (int i = 0; i < MAX_NORMAL; i++) {
            int index = i * STRIDE;
            initFireworks(gNormal, index, PARTICLE_NORMAL);
        }
        // 初始化默认的额外烟花组
        for (int i = 0; i < MAX_EXTRAS; i++) {
            int index = i * STRIDE;
            initFireworks(gExtras, index, PARTICLE_EXTRAS);
        }
    }

    /**
     * 检测数值符号是否变化
     * @param last 上一次的值
     * @param cur 当前值
     * @return 1=符号变化，0=未变化
     */
    private int signChanged(float last, float cur) {
        if (last >= 0.0f) {
            return cur >= 0.0f ? 0 : 1;
        } else {
            return cur >= 0.0f ? 1 : 0;
        }
    }

    /**
     * 归一化向量（转换为单位向量）
     * @param vec 二维向量[x, y]
     */
    private void normalize(float[] vec) {
        float eps = 0.000001f;
        float x = vec[0];
        float y = vec[1];
        float v = (float) Math.sqrt(x * x + y * y);
        if (v < eps) {
            vec[0] = 0.0f;
            vec[1] = 0.0f;
        } else {
            vec[0] = x / v;
            vec[1] = y / v;
        }
    }

    /**
     * 计算空气阻力对速度的影响
     * @param s 速度向量[x, y]
     */
    private void resistance(float[] s) {
        normalize(s);
        s[0] = -RESISTANCE * s[0] * (float) Math.sqrt(s[0] * s[0] + s[1] * s[1]);
        s[1] = -RESISTANCE * s[1] * (float) Math.sqrt(s[0] * s[0] + s[1] * s[1]);
    }

    /**
     * 设置粒子颜色（兼容旧方法）
     * @param fireworks 粒子实例
     * @param life 粒子生命值
     */
    private void setColor(FireworkParticle fireworks, float life) {
        float r = fireworks.r / 255.0f;
        float g = fireworks.g / 255.0f;
        float b = fireworks.b / 255.0f;
        float a = (float) Math.sqrt(Math.abs(life));
        GLES20.glUniform1f(mAlphaHandle, a);
        GLES20.glUniform3f(mColorHandle, r, g, b);
    }

    /**
     * 根据生命值计算粒子尺寸
     * @param life 粒子生命值
     * @return 粒子绘制尺寸
     */
    private float getSize(float life) {
        return PARTICLE_SIZE * (float) Math.sqrt(Math.abs(life));
    }

    /**
     * 初始化拖尾粒子状态
     * @param p 拖尾粒子实例
     */
    private void initTails(TailParticle p) {
        p.root = null;
        p.time = gNow;
        p.type = 0;
        p.life = -1.0f;
        p.posX = 0.0f;
        p.posY = 0.0f;
    }

    /**
     * 生成烟花拖尾
     * @param root 主烟花粒子
     * @return 生成的拖尾粒子索引（-1=失败，MAX_TAILS=满）
     */
    private int genTails(FireworkParticle root) {
        if (root == null) return -1;
        float[] vec = new float[]{root.dx, root.dy};
        normalize(vec);
        float s = (float) Math.sqrt(vec[0] * vec[0] + vec[1] * vec[1]);
        float life = root.life * 0.8f * (float) Math.sqrt(s);
        int indexInNormal = indexOf(gNormal, root);
        int indexInExtras = indexOf(gExtras, root);
        // 组首粒子的拖尾生命值降低
        if ((indexInNormal != -1 && indexInNormal % STRIDE == 0)
                || (indexInExtras != -1 && indexInExtras % STRIDE == 0)) {
            life *= 0.3f;
        }
        float size = 0.08f * getSize(life);
        boolean draw = size < 3 || root.ds > size;
        // 不满足条件则不生成拖尾
        if (!root.hasTails || !draw) return -1;

        // 寻找空闲的拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            TailParticle p = gTails[i];
            if (p.life < 0.0f) {
                p.root = root;
                p.time = gNow;
                root.ds = 0;
                p.life = life;
                p.posX = root.posX;
                p.posY = root.posY;
                return i;
            }
        }
        return MAX_TAILS;
    }

    /**
     * 更新拖尾粒子状态
     * @param p 拖尾粒子实例
     */
    private void updateTails(TailParticle p) {
        if (p == null) return;
        if (p.root == null || p.life < 0.0f) return;
        if (p.type == 0) {
            // 计算时间差
            int delta = gNow - p.time;
            p.time = gNow;
            FireworkParticle root = p.root;
            // 衰减生命值
            p.life -= FADE_MAGNIFY * root.fade * delta;
            // 生命值耗尽则重置
            if (p.life < 0.0f) {
                initTails(p);
            }
        }
    }

    /**
     * 绘制拖尾粒子
     * @param tail 拖尾粒子实例
     * @param offsetX X轴偏移量
     */
    private void drawTails(TailParticle tail, float offsetX) {
        if (tail == null) return;
        FireworkParticle root = tail.root;
        if (tail.life < 0.0f) return;
        
        if (tail.type == 0) {
            // 绘制普通拖尾
            float size = getSize(tail.life);
            float x = tail.posX - offsetX;
            float y = tail.posY;
            setParticleColor(root, tail.life);
            drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
        } else if (tail.type == 1) {
            // 绘制闪光效果
            float size = 350.0f;
            float x = tail.posX - offsetX;
            float y = tail.posY;
            setParticleColor(null, 1.0f);
            drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
            // 闪光绘制后重置拖尾
            initTails(tail);
        }
    }

    /**
     * 生成闪光效果
     * @param first 主烟花粒子
     * @return 生成的拖尾粒子索引（-1=失败，MAX_TAILS=满）
     */
    private int genFlares(FireworkParticle first) {
        if (first == null) return -1;
        // 随机生成闪光位置（主粒子周围120像素内）
        float x = randf2(first.posX - 120.0f, first.posX + 120.0f);
        float y = randf2(first.posY - 120.0f, first.posY + 120.0f);

        // 寻找空闲的拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            TailParticle p = gTails[i];
            if (p.life < 0.0f) {
                p.root = first;
                p.time = gNow;
                p.type = 1; // 标记为闪光类型
                first.ds = 0;
                p.life = 1.0f;
                p.posX = x;
                p.posY = y;
                return i;
            }
        }
        return MAX_TAILS;
    }

    /**
     * 初始化烟花粒子组
     * @param arr 粒子数组
     * @param index 组起始索引
     * @param type 粒子类型（常规/额外）
     */
    private void initFireworks(FireworkParticle[] arr, int index, int type) {
        FireworkParticle p = arr[index];
        // 随机生成粒子颜色（0.5~1.0的亮度）
        int r = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        int g = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        int b = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        // 随机生成衰减速度
        float fade = randf2(1.0f - FADE_VARIANCE, 1.0f + FADE_VARIANCE) * FADE;
        // 计算生成拖尾的粒子数量
        int tailsCount = (int) (STRIDE * randf(MAX_RATIO));
        int c = 0;

        // 初始化组内所有粒子
        for (int i = 0; i < STRIDE; i++) {
            p.time = gNow;
            p.active = false;
            // 设置是否生成拖尾
            if (c < tailsCount) {
                p.hasTails = true;
                c++;
            } else {
                p.hasTails = false;
            }
            p.ds = (int) PARTICLE_SIZE;
            p.life = -1.0f;
            p.fade = fade;
            p.type = type;
            p.r = r;
            p.g = g;
            p.b = b;
            // 随机初始位置（屏幕宽度2倍范围内）
            p.posX = (int) randf(mWidth * 2.0f);
            p.posY = (int) mHeight; // 初始在屏幕底部
            p.dx = 0.0f;
            p.dy = 0.0f;
            // 创建下一个粒子实例（最后一个粒子不需要）
            if (i < STRIDE - 1) {
                arr[index + i + 1] = new FireworkParticle();
                p = arr[index + i + 1];
            }
        }

        // 初始化组首粒子（发射的主粒子）
        FireworkParticle first = arr[index];
        first.dy = -randf2(1.0f, 1.0f + SPEED_VARIANCE) * SPEED_MAGNIFY; // 向上的初始速度
        first.r = 255; // 白色
        first.g = 255;
        first.b = 255;
        first.hasTails = true; // 主粒子强制生成拖尾

        // 常规粒子默认激活，额外粒子默认未激活
        if (type == PARTICLE_NORMAL) {
            first.active = true;
            first.life = 1.0f;
            first.time = gNow + (int) randf(MAX_DELAY); // 随机延迟发射
        } else {
            first.active = false;
            first.life = -1.0f;
        }
    }

    /**
     * 烟花爆炸逻辑
     * @param arr 粒子数组
     * @param index 组起始索引
     */
    private void explode(FireworkParticle[] arr, int index) {
        FireworkParticle p = arr[index];
        p.active = false; // 主粒子停止移动
        p.time = gNow;
        p.life = 1.0f;
        p.ds = FLARE_DURATION + 1; // 触发闪光效果

        float theta;
        float threshold;
        // 初始化爆炸后的子粒子
        for (int i = 0; i < EXPLODE_FIREWORKS; i++) {
            FireworkParticle e = arr[index + i + 1];
            e.active = true;
            e.life = 1.0f;
            e.time = gNow;
            e.posX = p.posX; // 与主粒子同位置
            e.posY = p.posY;
            // 随机爆炸方向（0~2π）
            theta = randf(2.0f * PI);
            // 随机爆炸速度（0~1）
            threshold = randf(1.0f);
            e.dx = (float) (Math.cos(theta) * threshold);
            e.dy = (float) (Math.sin(theta) * threshold);
        }
    }

    /**
     * 更新烟花粒子组状态（物理计算）
     * @param arr 粒子数组
     * @param index 组起始索引
     */
    private void updateFireworks(FireworkParticle[] arr, int index) {
        FireworkParticle p = arr[index];
        if (p == null) return;

        // 计算时间差
        int delta = gNow - p.time;
        float[] vec = new float[]{p.dx, p.dy};
        // 计算空气阻力
        resistance(vec);

        // 粒子未激活或生命值耗尽则跳过
        if (delta < 0 || p.life < 0.0f) {
            return;
        } else if (p.active) {
            // 主粒子上升阶段
            p.time = gNow;
            p.life = Math.abs(p.dy); // 生命值关联垂直速度

            // 更新位置
            float lastPos = p.posY;
            p.posY = p.posY + (p.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
            float curPos = p.posY;
            p.ds += (int) Math.abs(curPos - lastPos); // 累计移动距离
            genTails(p); // 生成拖尾

            // 更新垂直速度（重力+阻力）
            float lastSpeed = p.dy;
            p.dy = p.dy + (GRAVITY + vec[1]) * delta;
            float curSpeed = p.dy;
            // 速度符号变化（到达最高点）则触发爆炸
            int changed = signChanged(lastSpeed, curSpeed);
            if (changed != 0) {
                explode(arr, index);
            }
        } else {
            // 爆炸后阶段
            p.ds += delta;
            // 满足条件则生成闪光效果
            if (p.ds > FLARE_DURATION && p.life > 0.2f) {
                genFlares(p);
            }
            // 更新主粒子位置
            p.posY = p.posY + (p.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
            // 更新主粒子速度
            p.dy = p.dy + (GRAVITY + vec[1]) * delta;
            p.time = gNow;
            // 衰减生命值
            p.life -= p.fade * delta;
            // 生命值耗尽则重置粒子组
            if (p.life < 0.0f) {
                initFireworks(arr, index, p.type);
                return;
            }

            // 更新爆炸后的子粒子
            for (int i = 0; i < EXPLODE_FIREWORKS; i++) {
                FireworkParticle e = arr[index + i + 1];
                delta = gNow - e.time;
                e.time = gNow;
                vec[0] = e.dx;
                vec[1] = e.dy;
                resistance(vec); // 计算阻力

                // 更新位置
                float lastPos = (float) Math.sqrt(e.posX * e.posX + e.posY * e.posY);
                e.posX = e.posX + (e.dx + vec[0] * delta * SPEED * 0.5f) * delta * SPEED;
                e.posY = e.posY + (e.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
                float curPos = (float) Math.sqrt(e.posX * e.posX + e.posY * e.posY);
                e.ds += (int) Math.abs(curPos - lastPos);
                genTails(e); // 生成拖尾

                // 更新X方向速度（阻力）
                float lastSpeed = e.dx;
                e.dx = e.dx + vec[0] * delta;
                float curSpeed = e.dx;
                if (signChanged(lastSpeed, curSpeed) == 1) {
                    e.dx = lastSpeed; // 符号变化则回弹
                }
                // 更新Y方向速度（重力+阻力）
                lastSpeed = e.dy;
                curSpeed = e.dy + vec[1] * delta;
                e.dy = e.dy + (GRAVITY + vec[1]) * delta;
                if (signChanged(lastSpeed, curSpeed) == 1) {
                    e.dy = e.dy + GRAVITY * delta; // 符号变化则增加重力
                }
                // 衰减子粒子生命值
                e.life -= e.fade * delta;
            }
        }
    }

    /**
     * 更新所有粒子状态
     * 遍历所有粒子组和拖尾粒子，执行物理计算
     */
    private void update() {
        // 更新常规烟花
        for (int i = 0; i < MAX_NORMAL; i++) {
            updateFireworks(gNormal, i * STRIDE);
        }
        // 更新额外烟花
        for (int i = 0; i < MAX_EXTRAS; i++) {
            updateFireworks(gExtras, i * STRIDE);
        }
        // 更新拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            updateTails(gTails[i]);
        }
    }

    /**
     * 绘制背景
     * 绘制双背景以实现滚动无缝衔接
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param offsetX X轴偏移量
     */
    private void drawBackground(int width, int height, float offsetX) {
        // 计算背景宽度（保持宽高比）
        float bgWidth = height * mBackgroundAspect;
        float startX = -offsetX;
        // 绘制第一个背景
        drawRect(mTexBackground, startX, 0.0f, startX + bgWidth, height);
        // 绘制第二个背景（衔接第一个）
        drawRect(mTexBackground, startX + bgWidth, 0.0f, startX + bgWidth * 2.0f, height);
    }

    /**
     * 绘制烟花粒子组
     * @param arr 粒子数组
     * @param index 组起始索引
     * @param offsetX X轴偏移量
     */
    private void drawFireworks(FireworkParticle[] arr, int index, float offsetX) {
        for (int i = 0; i < STRIDE; i++) {
            FireworkParticle p = arr[index + i];
            int delta = gNow - p.time;
            // 仅绘制激活且时间差为正的粒子
            if (p.active && delta >= 0) {
                setParticleColor(p, p.life);
                float size = getSize(p.life);
                float x = p.posX - offsetX;
                float y = p.posY;
                // 绘制粒子（中心对齐）
                drawRect(mTexStar, x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
            }
        }
    }

    /**
     * 绘制所有可见元素
     * @param offsetX X轴偏移量
     */
    private void draw(float offsetX) {
        // 绘制常规烟花
        for (int i = 0; i < MAX_NORMAL; i++) {
            drawFireworks(gNormal, i * STRIDE, offsetX);
        }
        // 绘制额外烟花
        for (int i = 0; i < MAX_EXTRAS; i++) {
            drawFireworks(gExtras, i * STRIDE, offsetX);
        }
        // 绘制拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            drawTails(gTails[i], offsetX);
        }
    }

    /**
     * 处理触摸事件，生成点击烟花
     * @param x 触摸X坐标
     * @param y 触摸Y坐标
     */
    private void addTap(int x, int y) {
        // 寻找空闲的额外烟花组
        for (int i = 0; i < MAX_EXTRAS; i++) {
            int index = i * STRIDE;
            FireworkParticle p = gExtras[index];
            if (p.life < 0.0f) {
                // 初始化烟花组
                initFireworks(gExtras, index, PARTICLE_EXTRAS);
                p = gExtras[index];
                // 设置触摸位置为发射位置
                p.posX = x;
                p.posY = y;
                p.dx = 0.0f;
                p.dy = 0.0f;
                p.life = 1.0f;
                p.active = true;
                // 立即触发爆炸
                explode(gExtras, index);
                break;
            }
        }
    }

    /**
     * 生成0~range的随机浮点数
     * @param range 最大值
     * @return 随机数
     */
    private float randf(float range) {
        return mRandom.nextFloat() * range;
    }

    /**
     * 生成min~max的随机浮点数
     * @param min 最小值
     * @param max 最大值
     * @return 随机数
     */
    private float randf2(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    /**
     * 设置粒子绘制颜色和透明度
     * @param fireworks 粒子实例（null则使用白色）
     * @param life 粒子生命值
     */
    private void setParticleColor(FireworkParticle fireworks, float life) {
        float r = 1.0f;
        float g = 1.0f;
        float b = 1.0f;
        // 使用粒子自身颜色（如果不为空）
        if (fireworks != null) {
            r = fireworks.r / 255.0f;
            g = fireworks.g / 255.0f;
            b = fireworks.b / 255.0f;
        }
        // 透明度关联生命值（平方根映射，让衰减更自然）
        float a = (float) Math.sqrt(Math.abs(life));
        // 设置着色器统一变量
        GLES20.glUniform1f(mAlphaHandle, a);
        GLES20.glUniform3f(mColorHandle, r, g, b);
    }

    /**
     * 查找粒子在数组中的索引
     * @param arr 粒子数组
     * @param target 目标粒子
     * @return 索引（-1=未找到）
     */
    private int indexOf(FireworkParticle[] arr, FireworkParticle target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }

    /**
     * 绘制纹理矩形
     * @param texture 纹理ID
     * @param x0 左X坐标
     * @param y0 下Y坐标
     * @param x1 右X坐标
     * @param y1 上Y坐标
     */
    private void drawRect(int texture, float x0, float y0, float x1, float y1) {
        // 构建四边形顶点数据（XY坐标 + 纹理坐标）
        float[] verts = new float[] {
                x0, y0, 0.0f, 0.0f,
                x0, y1, 0.0f, 1.0f,
                x1, y1, 1.0f, 1.0f,
                x1, y0, 1.0f, 0.0f
        };

        // 填充顶点缓冲
        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        // 启用顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 启用纹理坐标属性
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexHandle);
        GLES20.glVertexAttribPointer(mTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 绑定纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);
        
        // 背景纹理强制使用不透明白色
        if (texture == mTexBackground) {
            GLES20.glUniform1f(mAlphaHandle, 1.0f);
            GLES20.glUniform3f(mColorHandle, 1.0f, 1.0f, 1.0f);
        }

        // 绘制四边形（三角扇模式）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        // 禁用属性（性能优化）
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexHandle);
    }

    /**
     * 加载纹理资源
     * @param res 资源管理器
     * @param resId 资源ID
     * @param repeat 是否重复纹理
     * @return 纹理ID（0=失败）
     */
    private int loadTexture(Resources res, int resId, boolean repeat) {
        // 加载位图（不缩放）
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        Bitmap bmp = BitmapFactory.decodeResource(res, resId, opts);
        if (bmp == null) return 0;

        // 创建OpenGL纹理
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        
        // 设置纹理过滤模式（最近邻过滤）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        
        // 设置纹理环绕模式
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        
        // 上传位图数据到纹理
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        // 释放位图资源
        bmp.recycle();
        
        return tex[0];
    }

    /**
     * 创建着色器程序
     * @param vs 顶点着色器代码
     * @param fs 片段着色器代码
     * @return 程序句柄
     */
    private int createProgram(String vs, String fs) {
        // 加载并编译着色器
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        
        // 创建程序并附加着色器
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        // 链接程序
        GLES20.glLinkProgram(program);
        
        return program;
    }

    /**
     * 加载并编译着色器
     * @param type 着色器类型（顶点/片段）
     * @param source 着色器代码
     * @return 着色器句柄
     */
    private int loadShader(int type, String source) {
        // 创建着色器
        int shader = GLES20.glCreateShader(type);
        // 设置着色器代码
        GLES20.glShaderSource(shader, source);
        // 编译着色器
        GLES20.glCompileShader(shader);
        
        return shader;
    }
}