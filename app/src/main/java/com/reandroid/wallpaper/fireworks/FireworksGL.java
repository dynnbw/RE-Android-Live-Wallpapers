package com.reandroid.wallpaper.fireworks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.MathUtils;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static com.reandroid.wallpaper.fireworks.FireworksScene.*;

/**
 * 烟花壁纸的OpenGL渲染核心类
 * 负责烟花粒子系统的创建、更新、绘制，触摸交互处理，背景纹理加载等
 */
public class FireworksGL extends GLESScene {

    // ---- 场景逻辑层（非 GL） ----
    private final FireworksScene mScene;
    private final Context mContext;

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
    /**
     * drawRect 的顶点模板：z 与 uv 固定，每帧只有 4 个角点的 x/y 会变。
     * drawRect 每帧调用次数与烟花/图层数量同阶，模板复用避免每次重新分配。
     */
    private static final float[] QUAD_TEMPLATE = {
            0f, 0f, 0.0f, 0.0f,
            0f, 0f, 0.0f, 1.0f,
            0f, 0f, 1.0f, 1.0f,
            0f, 0f, 1.0f, 0.0f
    };
    private final float[] mQuadVerts = QUAD_TEMPLATE.clone();

    // X轴偏移量（适配壁纸滚动）
    private float mXOffset = 0.0f;

    /**
     * 构造方法
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    private android.content.SharedPreferences mPluginPrefs;
    /** 跨插件读取能力，由宿主注入（草地夜景背景要读 grass 的设置）。 */
    private com.reandroid.plugin.PluginPrefsProvider mPluginPrefsProvider;

    // 草地夜景变种：开启后背景变为恒夜草地（夜空+星星+草遮挡烟花）
    private static final String KEY_GRASS_NIGHT = "pref_fireworks_grass_night";
    private boolean mGrassNightEnabled = false;

    // 烟花数量设置项
    private static final String KEY_COUNT = "pref_fireworks_count";
    /** 主线程写、GL 线程读,故为 volatile。 */
    private volatile int mPrefCount = FireworksScene.DEFAULT_COUNT;
    // 爆开粒子拖尾开关。默认开:原版 MAX_RATIO 恒为 0,爆开粒子是没有尾迹的,
    // 打开是本次的明确诉求(仅组首那发上升时的白色尾迹是原版固有行为,不受此开关影响)。
    private static final String KEY_TAILS = "pref_fireworks_tails";
    /** 主线程写、GL 线程读,故为 volatile。 */
    private volatile boolean mPrefTails = true;
    // 增强模式主开关(默认关 = 完全原版行为),以及鲜艳配色
    private static final String KEY_ENHANCED = "pref_fireworks_enhanced";
    private static final String KEY_PALETTE = "pref_fireworks_palette";
    private static final String PALETTE_VIVID = "vivid";
    /** 主线程写、GL 线程读,故为 volatile。 */
    private volatile boolean mPrefEnhanced = false;
    private volatile boolean mPrefVivid = false;
    // 已应用的设置(只在 GL 线程读写)。初值 -1 保证首帧一定应用一次:
    // 以数量 10 启动的壁纸必须立刻用 10,不能等到设置变更才生效。
    private int mAppliedCount = -1;
    private boolean mAppliedTails = false;
    private boolean mAppliedEnhanced = false;
    private boolean mAppliedVivid = false;
    private FireworksGrassBackdrop mBackdrop;
    // 草地/星星配置跟随 grass 壁纸设置（每秒轮询 plugin_grass，契约外不注册监听器）
    private long mLastGrassConfigPollMs = 0L;

    // ---- 粒子批量化 ----
    // 每顶点 8 个 float:x, y, u, v, r, g, b, a
    private static final int PARTICLE_FLOATS = 8;
    /** 增强模式光尾的等效曝光时长(秒):拉长量 = 速度 × 它。嫌尾短/尾长改这里。 */
    private static final float TRAIL_SECONDS = 0.12f;
    private static final int VERTS_PER_PARTICLE = 4;
    private static final int INDICES_PER_PARTICLE = 6;
    // 上限 = 场景最大槽位 + 尾迹池 = (30+45)×75 + 500 = 6125
    private static final int MAX_PARTICLES = 6125;

    // 批量粒子的着色器程序(与背景程序分开,背景那条路径与其 uniform 特例完全不动)
    private int mParticleProgram;
    private int mParticlePositionHandle;
    private int mParticleTexHandle;
    private int mParticleColorHandle;
    private int mParticleMatrixHandle;
    private int mParticleSamplerHandle;

    // 交错顶点缓冲与预生成索引缓冲
    private FloatBuffer mParticleBuffer;
    private ShortBuffer mParticleIndexBuffer;
    // 本帧已写入的粒子数
    private int mParticleCount;

    public FireworksGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new FireworksScene(width, height);
    }

    /**
     * Called by BasePluginEngine via reflection to inject plugin-isolated prefs.
     *
     * <p>本方法在主线程被偏好监听器调用,因此<b>只写 volatile 字段</b>,绝不在这里调用
     * applySettings —— 那会重建渲染线程正在读的粒子数组(见 applyPendingSettings)。
     */
    /** 跨插件读取能力（草地夜景背景要读 grass 的设置），由宿主注入。 */
    public void setPluginPrefsProvider(com.reandroid.plugin.PluginPrefsProvider provider) {
        mPluginPrefsProvider = provider;
    }

    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mPluginPrefs = prefs;
        if (prefs != null) {
            mGrassNightEnabled = prefs.getBoolean(KEY_GRASS_NIGHT, false);
            mPrefCount = prefs.getInt(KEY_COUNT, FireworksScene.DEFAULT_COUNT);
            mPrefTails = prefs.getBoolean(KEY_TAILS, true);
            mPrefEnhanced = prefs.getBoolean(KEY_ENHANCED, false);
            mPrefVivid = PALETTE_VIVID.equals(prefs.getString(KEY_PALETTE, "original"));
        }
    }

    /**
     * 把主线程写入的偏好应用到场景。<b>只在 GL 线程调用</b> —— applySettings 会重建粒子数组。
     */
    private void applyPendingSettings() {
        int count = mPrefCount;
        boolean tails = mPrefTails;
        boolean enhanced = mPrefEnhanced;
        boolean vivid = mPrefVivid;
        if (count == mAppliedCount && tails == mAppliedTails
                && enhanced == mAppliedEnhanced && vivid == mAppliedVivid) {
            return;
        }
        mAppliedCount = count;
        mAppliedTails = tails;
        mAppliedEnhanced = enhanced;
        mAppliedVivid = vivid;
        mScene.applySettings(count, tails, enhanced, vivid);
    }

    /** Returns the custom background URI, checking plugin prefs first. */
    private String getCustomBackgroundUri() {
        String key = "fireworks_custom_background_uri";
        if (mPluginPrefs != null) {
            String uri = mPluginPrefs.getString("pref_custom_background_uri", null);
            if (uri != null && !uri.isEmpty()) return uri;
        }
        return mContext.getSharedPreferences("wallpaper_prefs", 0).getString(key, null);
    }

    /**
     * 生命周期方法：创建时初始化
     * 仅执行一次基础初始化
     */
    @Override
    protected void onCreate() {
        if (mScene.mInitialized) return;
        mScene.mInitialized = true;
        mScene.initialize();
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

        if (mParticleProgram != 0) {
            GLES20.glDeleteProgram(mParticleProgram);
            mParticleProgram = 0;
        }

        if (mBackdrop != null) {
            mBackdrop.release();
            mBackdrop = null;
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
        if (mBackdrop != null) {
            mBackdrop.resize(width, height);
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
        // 处理壁纸触摸事件（桌面路径：系统发送 tap 命令）
        if ("android.wallpaper.tap".equals(action)) {
            mScene.mTapX = x;
            mScene.mTapY = y;
            mScene.mTapPending = true;
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        // 应用内预览路径：预览只转发 onTouchEvent，不派发 tap 命令。
        // 仅在预览模式接管，桌面继续走 onCommand，避免双重触发。
        if (isPreview() && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mScene.mTapX = (int) event.getX();
            mScene.mTapY = (int) event.getY();
            mScene.mTapPending = true;
        }
    }

    /**
     * 帧绘制方法（核心渲染逻辑）
     * @param timeMs 帧时间戳（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        if (!mScene.mInitialized) return;
        // 延迟初始化OpenGL资源（避免提前初始化导致异常）
        if (!mGLInitialized) {
            if (mContext == null) return;
            initGL();
        }

        // 应用主线程写入的数量/尾迹设置(有变化时会重建粒子数组,必须在 GL 线程做)
        applyPendingSettings();

        // 更新当前时间
        mScene.mNow = (int) SystemClock.uptimeMillis();

        // 处理待执行的触摸事件
        if (mScene.mTapPending) {
            mScene.mTapPending = false;
            // 预览模式下无偏移，实际壁纸需叠加滚动偏移
            float tapOffset = isPreview() ? 0.0f : mXOffset;
            int x = (int) (mScene.mTapX + tapOffset * mWidth);
            mScene.addTap(x, mScene.mTapY);
        }

        // 计算最终的X轴偏移（适配壁纸滚动）
        float offset = isPreview() ? 0.0f : mXOffset;
        float offsetX = offset * mWidth;

        // 草地夜景变种：夜空 → 星星 → 烟花(加法) → 草叶遮挡
        if (mGrassNightEnabled) {
            drawGrassNightFrame(offsetX, offset);
            return;
        }

        // 检查并重新加载背景（如果URI变化）
        checkAndReloadBackground();

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
        mScene.update();

        // 设置混合模式（粒子：加法混合，实现发光效果）
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        // 绘制所有粒子和拖尾
        draw(offsetX);
    }

    /**
     * 草地夜景变种帧：夜空 → 星星 → 烟花(加法混合) → 草叶（草遮挡烟花）。
     * 恒夜，无太阳/月亮/蒲公英/萤火虫；自定义背景不参与此模式。
     */
    private void drawGrassNightFrame(float offsetX, float offset) {
        if (mBackdrop == null) {
            mBackdrop = new FireworksGrassBackdrop();
        }
        if (!mBackdrop.isReady()) {
            mBackdrop.initGL(mContext, mWidth, mHeight);
        }

        // 滚动语义与 grass 壁纸一致：预览固定 0.5，桌面跟随滚动偏移
        mBackdrop.update(mScene.mNow, isPreview() ? 0.5f : offset);

        // 草地/星星配置跟随 grass 壁纸（每秒轮询）
        long now = SystemClock.uptimeMillis();
        if (now - mLastGrassConfigPollMs >= 1000L) {
            mLastGrassConfigPollMs = now;
            pollGrassConfig();
        }

        // 清空颜色缓冲区（准备绘制新帧）
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 夜空渐变 + 星星（标准 Alpha 混合）
        mBackdrop.drawSky(mProjectionMatrix);
        mBackdrop.drawStars(mProjectionMatrix);

        // 更新所有粒子状态（物理计算）
        mScene.update();

        // 烟花：加法混合，发光效果，绘制于星星之上、草叶之下
        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        draw(offsetX);

        // 草叶最后绘制，遮挡烟花
        mBackdrop.drawGrass(mProjectionMatrix);
    }

    /**
     * 读取 **grass 壁纸**的草/星星配置，用于把草地夜景背景画得和它一致。
     * 映射语义与 GrassScene 一致：高/宽/硬度为百分比/100，范围分别夹在 0.1-10、0.1-10、0.3-10。
     *
     * <p>设置必须来自 grass 自己那一份，而注入的 {@code setPluginPrefs} 只给得到 fireworks
     * 这一份，所以跨插件读取由宿主经 {@link com.reandroid.plugin.PluginPrefsProvider} 注入，
     * 这里不自己去碰存储层（同类：ManyScene 读 vis2 / vis3）。
     */
    private void pollGrassConfig() {
        if (mBackdrop == null || mPluginPrefsProvider == null) return;
        try {
            android.content.SharedPreferences p = mPluginPrefsProvider.forPlugin("grass");
            if (p == null) return;
            int bladeCount = p.getInt("pref_grass_count", 200);
            float heightScale = MathUtils.clamp(p.getInt("pref_grass_height", 100) / 100.0f, 0.1f, 10.0f);
            float widthScale = MathUtils.clamp(p.getInt("pref_grass_width", 100) / 100.0f, 0.1f, 10.0f);
            float hardnessScale = MathUtils.clamp(p.getInt("pref_grass_hardness", 100) / 100.0f, 0.3f, 10.0f);
            int starCount = p.getInt("pref_grass_star_count", 2048);
            mBackdrop.setConfig(bladeCount, heightScale, widthScale, hardnessScale, starCount);
        } catch (Exception e) {
            // 轮询失败不影响本帧渲染
            android.util.Log.e("FireworksGL", "grass config poll failed", e);
        }
    }

    /**
     * 初始化OpenGL相关资源
     */
    private void initGL() {
        mGLInitialized = true;

        // 禁用深度测试（2D渲染不需要）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        // 启用混合（实现透明效果）
        GLES20.glEnable(GLES20.GL_BLEND);
        // 设置视口大小
        GLES20.glViewport(0, 0, mWidth, mHeight);
        // 创建正交投影矩阵（适配屏幕坐标）
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        // 从assets加载着色器
        String vs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_vs.glsl");
        String fs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_fs.glsl");

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
        if (mContext != null) {
            mTexBackground = loadCustomBackgroundTexture(mContext);
        }

        // 自定义背景加载失败时使用默认背景
        if (mTexBackground == 0) {
            Bitmap bmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/background.jpg");
            if (bmp != null) {
                mBackgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
                mTexBackground = loadTexture(bmp, false);
            }
        }

        // 加载星星（粒子）纹理
        Bitmap starBmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/star.png");
        if (starBmp != null) {
            mTexStar = loadTexture(starBmp, true);
        }

        // 批量粒子的着色器程序
        String pvs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_particle_vs.glsl");
        String pfs = AssetLoader.readText(mContext, "fireworks/shaders/GLES/fireworks_particle_fs.glsl");
        mParticleProgram = createProgram(pvs, pfs);
        mParticlePositionHandle = GLES20.glGetAttribLocation(mParticleProgram, "aPosition");
        mParticleTexHandle = GLES20.glGetAttribLocation(mParticleProgram, "aTexCoord");
        mParticleColorHandle = GLES20.glGetAttribLocation(mParticleProgram, "aColor");
        mParticleMatrixHandle = GLES20.glGetUniformLocation(mParticleProgram, "uMVPMatrix");
        mParticleSamplerHandle = GLES20.glGetUniformLocation(mParticleProgram, "uSampler");

        initParticleBatch();
    }

    /**
     * 一次性分配交错顶点缓冲,并按最大粒子数预生成索引缓冲。
     * 索引按 0,1,2, 0,2,3 顺序展开 —— 与 drawRect 的 TRIANGLE_FAN 覆盖同样两个三角形,
     * 且顺序展开意味着"画前 N 颗粒子"只需改 drawElements 的 count。
     */
    private void initParticleBatch() {
        int maxVerts = MAX_PARTICLES * VERTS_PER_PARTICLE;
        mParticleBuffer = ByteBuffer.allocateDirect(maxVerts * PARTICLE_FLOATS * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        ShortBuffer indices = ByteBuffer
                .allocateDirect(MAX_PARTICLES * INDICES_PER_PARTICLE * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int p = 0; p < MAX_PARTICLES; p++) {
            short base = (short) (p * VERTS_PER_PARTICLE);
            indices.put(base).put((short) (base + 1)).put((short) (base + 2));
            indices.put(base).put((short) (base + 2)).put((short) (base + 3));
        }
        indices.position(0);
        mParticleIndexBuffer = indices;
    }

    /**
     * 检查并重新加载背景纹理（当URI变化时）
     */
    private void checkAndReloadBackground() {
        try {
            if (mContext == null) return;

            // 获取当前保存的自定义背景URI
            String currentUri = getCustomBackgroundUri();

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
                mTexBackground = loadCustomBackgroundTexture(mContext);
                if (mTexBackground == 0) {
                    android.util.Log.d("FireworksGL", "自定义背景加载失败，使用默认背景");
                    // 加载默认背景
                    Bitmap bmp = AssetLoader.decodeBitmap(mContext, "fireworks/drawable/background.jpg");
                    if (bmp != null) {
                        mBackgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
                        mTexBackground = loadTexture(bmp, false);
                    }
                } else {
                    android.util.Log.d("FireworksGL", "自定义背景加载成功");
                }

                // 更新缓存的URI
                mLastBackgroundUri = currentUri;
            }
        } catch (Exception e) {
            android.util.Log.e("FireworksGL", "背景检查/重新加载异常", e);
        }
    }

    /**
     * 加载自定义背景纹理
     * @param ctx 上下文
     * @return 纹理ID（0表示加载失败）
     */
    private int loadCustomBackgroundTexture(Context ctx) {
        try {
            if (ctx == null) {
                android.util.Log.e("FireworksGL", "上下文为空");
                return 0;
            }

            // 获取自定义背景URI
            String uriString = getCustomBackgroundUri();
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
            return 0;
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
        if (mScene.mEnhanced) {
            drawFireworksEnhanced(arr, index, offsetX);
            return;
        }
        for (int i = 0; i < STRIDE; i++) {
            FireworkParticle p = arr[index + i];
            int delta = mScene.mNow - p.time;
            // 仅绘制激活且时间差为正的粒子
            if (p.active && delta >= 0) {
                float size = mScene.getSize(p.life);
                putFireworkParticle(p, p.life, p.posX - offsetX, p.posY, size);
            }
        }
    }

    /**
     * 增强模式的粒子绘制：组首是"橙色外辉 + 白色核心"的火箭，
     * 其余是实时单位的小火花（alpha 走 life/maxLife 曲线，星尘带闪烁）。
     */
    private void drawFireworksEnhanced(FireworkParticle[] arr, int index, float offsetX) {
        FireworkParticle leader = arr[index];
        if (leader != null && leader.active) {
            float x = leader.posX - offsetX;
            float y = leader.posY;
            float glow = mScene.getRocketGlowSize();
            putParticle(x - glow * 0.5f, y - glow * 0.5f, x + glow * 0.5f, y + glow * 0.5f,
                    1.0f, 0.686f, 0.275f, 0.32f);
            // 核心同样沿速度拉长 → 上升的火箭是一道彗尾
            putStreak(leader, x, y, mScene.getRocketCoreSize(), 1.0f);
        }
        for (int i = 1; i < STRIDE; i++) {
            FireworkParticle e = arr[index + i];
            if (e == null || !e.active || e.life <= 0.0f) continue;
            float t = e.maxLife > 0.0f ? e.life / e.maxLife : 0.0f;
            float a = t > 0.78f ? 1.0f : t / 0.78f;
            // 参考实现在这里还乘了 0.88,但它画的是不透明硬边圆点;
            // 软贴图本身已经衰减过一次,再压就只有"灰"没有"亮"
            a = (float) Math.pow(a, 0.6);
            if (e.twinkle) {
                a *= 0.55f + 0.45f * (float) Math.sin(e.life * 45.0f + e.phase);
                if (a < 0.0f) a = 0.0f;
            }
            if (a <= 0.0f) continue;
            putStreak(e, e.posX - offsetX, e.posY, e.size, a);
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
            // 普通拖尾
            float size = mScene.getSize(tail.life);
            putFireworkParticle(root, tail.life, tail.posX - offsetX, tail.posY, size);
        } else if (tail.type == 1) {
            // 闪光:纯白、全不透明,直径随屏高等比缩放
            float size = mScene.getFlareSize();
            putParticle(tail.posX - offsetX - size * 0.5f, tail.posY - size * 0.5f,
                    tail.posX - offsetX + size * 0.5f, tail.posY + size * 0.5f,
                    1.0f, 1.0f, 1.0f, 1.0f);
            // 闪光绘制后重置拖尾
            mScene.initTails(tail);
        }
    }

    /** 开始一帧的粒子批次。 */
    private void beginParticles() {
        mParticleBuffer.clear();
        mParticleCount = 0;
    }

    /**
     * 往批次里追加一颗粒子(4 顶点 6 索引)。顶点与 UV 顺序和 drawRect 完全一致。
     */
    private void putParticle(float x0, float y0, float x1, float y1,
                             float r, float g, float b, float a) {
        // 轴对齐四边形就是"沿 y"的特例,和拉伸四边形共用同一条写入路径
        putQuadCorners(x0, y0, x0, y1, x1, y1, x1, y0, r, g, b, a);
    }

    /**
     * 写一个任意四边形的粒子（顶点按周长顺序，UV 的 u 轴沿"尾→头"方向）。
     * 供轴对齐的普通粒子和沿速度拉伸的光尾共用。
     */
    private void putQuadCorners(float x0, float y0, float x1, float y1,
                                float x2, float y2, float x3, float y3,
                                float r, float g, float b, float a) {
        if (mParticleCount >= MAX_PARTICLES) {
            // 上限已按场景最大配置算出,正常不会触发;留作防御
            return;
        }
        mParticleBuffer.put(x0).put(y0).put(0.0f).put(0.0f).put(r).put(g).put(b).put(a);
        mParticleBuffer.put(x1).put(y1).put(0.0f).put(1.0f).put(r).put(g).put(b).put(a);
        mParticleBuffer.put(x2).put(y2).put(1.0f).put(1.0f).put(r).put(g).put(b).put(a);
        mParticleBuffer.put(x3).put(y3).put(1.0f).put(0.0f).put(r).put(g).put(b).put(a);
        mParticleCount++;
    }

    /**
     * 增强模式的光尾：把粒子沿<b>速度方向</b>拉长。
     *
     * <p>拉长量正比于速度，所以刚炸开时光尾最长、减速后自然收成一个点 —— 不需要额外的
     * 尾迹粒子池（那套的时间单位是为原版调的，且 500 个槽位分给上千颗粒子只会得到稀疏亮点）。
     * 代价是零：仍是一颗一个四边形、一次 draw call。
     */
    private void putStreak(FireworkParticle p, float x, float y, float size, float a) {
        float vx = p.dx;
        float vy = p.dy;
        float speed = (float) Math.hypot(vx, vy);
        if (!mScene.mTailsEnabled || speed < 1.0f) {
            putParticle(x - size * 0.5f, y - size * 0.5f,
                    x + size * 0.5f, y + size * 0.5f,
                    p.r / 255.0f, p.g / 255.0f, p.b / 255.0f, a);
            return;
        }
        float ux = vx / speed;
        float uy = vy / speed;
        // 拉长量 = 速度 × 拖尾时长;上限防止极快的粒子拖成一条线。
        // 6 倍这个帽子对爆开粒子从不触发(它们最快也才 ~2 倍),只是兜住火箭那种长距离高速的情况。
        float stretch = Math.min(speed * TRAIL_SECONDS, size * 6.0f);
        float halfLen = (size + stretch) * 0.5f;
        float halfWid = size * 0.5f;
        float nx = -uy;
        float ny = ux;
        float r = p.r / 255.0f;
        float g = p.g / 255.0f;
        float b = p.b / 255.0f;
        putQuadCorners(
                x - ux * halfLen - nx * halfWid, y - uy * halfLen - ny * halfWid,
                x - ux * halfLen + nx * halfWid, y - uy * halfLen + ny * halfWid,
                x + ux * halfLen + nx * halfWid, y + uy * halfLen + ny * halfWid,
                x + ux * halfLen - nx * halfWid, y + uy * halfLen - ny * halfWid,
                r, g, b, a);
    }

    /**
     * 追加一颗"烟花粒子"(带自身颜色,透明度走 sqrt(|life|),与旧的 setParticleColor 一致)。
     */
    private void putFireworkParticle(FireworkParticle p, float life,
                                     float x, float y, float size) {
        float a = (float) Math.sqrt(Math.abs(life));
        float r = p != null ? p.r / 255.0f : 1.0f;
        float g = p != null ? p.g / 255.0f : 1.0f;
        float b = p != null ? p.b / 255.0f : 1.0f;
        putParticle(x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f,
                r, g, b, a);
    }

    /** 一次 draw call 提交本帧所有粒子。 */
    private void flushParticles() {
        if (mParticleCount == 0) return;

        GLES20.glUseProgram(mParticleProgram);
        GLES20.glUniformMatrix4fv(mParticleMatrixHandle, 1, false, mProjectionMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexStar);
        GLES20.glUniform1i(mParticleSamplerHandle, 0);

        int stride = PARTICLE_FLOATS * 4;
        mParticleBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mParticlePositionHandle);
        GLES20.glVertexAttribPointer(mParticlePositionHandle, 2, GLES20.GL_FLOAT,
                false, stride, mParticleBuffer);
        mParticleBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mParticleTexHandle);
        GLES20.glVertexAttribPointer(mParticleTexHandle, 2, GLES20.GL_FLOAT,
                false, stride, mParticleBuffer);
        mParticleBuffer.position(4);
        GLES20.glEnableVertexAttribArray(mParticleColorHandle);
        GLES20.glVertexAttribPointer(mParticleColorHandle, 4, GLES20.GL_FLOAT,
                false, stride, mParticleBuffer);

        mParticleIndexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mParticleCount * INDICES_PER_PARTICLE,
                GLES20.GL_UNSIGNED_SHORT, mParticleIndexBuffer);

        GLES20.glDisableVertexAttribArray(mParticlePositionHandle);
        GLES20.glDisableVertexAttribArray(mParticleTexHandle);
        GLES20.glDisableVertexAttribArray(mParticleColorHandle);
    }

    /**
     * 绘制所有可见粒子:按原绘制顺序写入同一批次,最后一次性提交。
     * 顺序不变 = 同一批次内仍按提交顺序光栅化,叠加结果与逐次绘制一致。
     * @param offsetX X轴偏移量
     */
    private void draw(float offsetX) {
        beginParticles();
        // 绘制常规烟花
        for (int i = 0; i < mScene.mNormalGroups; i++) {
            drawFireworks(mScene.mNormal, i * STRIDE, offsetX);
        }
        // 绘制额外烟花
        for (int i = 0; i < mScene.mExtraGroups; i++) {
            drawFireworks(mScene.mExtras, i * STRIDE, offsetX);
        }
        // 绘制拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            drawTails(mScene.mTails[i], offsetX);
        }
        flushParticles();
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
        float[] verts = mQuadVerts;
        verts[0] = x0; verts[1] = y0;
        verts[4] = x0; verts[5] = y1;
        verts[8] = x1; verts[9] = y1;
        verts[12] = x1; verts[13] = y0;

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
     * 从Bitmap创建GL纹理
     * @param bmp 位图数据
     * @param repeat 是否重复纹理
     * @return 纹理ID（0=失败）
     */
    private int loadTexture(Bitmap bmp, boolean repeat) {
        if (bmp == null) return 0;

        // 创建OpenGL纹理
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);

        // 设置纹理过滤模式（最近邻过滤）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

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

    /**
     * 加载并编译着色器
     * @param type 着色器类型（顶点/片段）
     * @param source 着色器代码
     * @return 着色器句柄
     */
}
