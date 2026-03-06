package com.reandroid.wallpaper.wildworld;

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
 * 野生世界动态壁纸的OpenGL ES渲染核心类
 * 负责处理壁纸的图层动画、角色（翼龙/恐龙）动画、火球特效、昼夜切换、触摸交互等渲染逻辑
 */
public class WildWorldGL extends GLESScene {
    // 最小时间步长（控制动画帧率稳定性）
    private static final float MIN_DT = 0.2f;
    // 恐龙动画时间间隔（毫秒）
    private static final long DINO_DT = 1000;
    // 翼龙动画时间间隔（毫秒）
    private static final long PTERO_DT = 2400;
    // 角色生成间隔时间（毫秒）
    private static final long GEN_TIME = 4000;
    // 角色生成随机系数
    private static final float GEN_RANDOM = 0.4f;

    // 方向常量：上
    private static final int UP = 0;
    // 方向常量：下
    private static final int DOWN = 1;

    // 火球渲染距离系数
    private static final float FIREBALL_DISTANCE = 0.96f;
    // VCN图层（火山/城堡层）渲染距离系数
    private static final float VCN_DISTANCE = 0.95f;
    // 第4层背景渲染距离系数
    private static final float LAYER4_DISTANCE = 0.9f;
    // 第3层背景渲染距离系数
    private static final float LAYER3_DISTANCE = 0.8f;
    // 第2层背景渲染距离系数
    private static final float LAYER2_DISTANCE = 0.7f;
    // 第1层背景渲染距离系数
    private static final float LAYER1_DISTANCE = 0.6f;
    // 翼龙渲染距离系数
    private static final float PTEROSAUR_DISTANCE = 0.85f;
    // 恐龙1渲染距离系数
    private static final float DINOSAUR1_DISTANCE = 0.85f;
    // 恐龙2渲染距离系数
    private static final float DINOSAUR2_DISTANCE = 0.75f;
    // 火球数量
    private static final int FIREBALL_COUNT = 6;

    // 屏幕密度类型：240x320
    private static final int DENSITY_240X320 = 1;
    // 屏幕密度类型：240x400
    private static final int DENSITY_240X400 = 2;
    // 屏幕密度类型：320x480
    private static final int DENSITY_320X480 = 3;
    // 屏幕密度类型：480x800
    private static final int DENSITY_480X800 = 4;

    /**
     * 图层数据结构
     * 存储单个图层的位置、宽高信息
     */
    private static class Layer {
        float x; // 图层X坐标
        float y; // 图层Y坐标
        float w; // 图层宽度
        float h; // 图层高度
    }

    /**
     * 翼龙数据结构
     * 存储翼龙的位置、尺寸、动画状态等信息
     */
    private static class Pterosaur {
        float x; // X坐标
        float y; // Y坐标
        float w; // 宽度
        float h; // 高度
        float scale; // 缩放比例
        int duration; // 动画持续阶段
        int alive; // 存活状态（0=死亡，1=存活）
        float time; // 动画时间戳
    }

    /**
     * 恐龙数据结构
     * 存储恐龙的位置、尺寸、移动状态等信息
     */
    private static class Dinosaur {
        float x; // X坐标
        float y; // Y坐标
        float w; // 宽度
        float h; // 高度
        float stepY; // Y轴移动步长
        float distance; // 渲染距离系数
        int alive; // 存活状态（0=死亡，1=存活）
        float time; // 动画时间戳
    }

    /**
     * 火球数据结构
     * 存储火球的位置、运动状态、特效参数等信息
     */
    private static class Fireball {
        float x; // X坐标
        float y; // Y坐标
        float w; // 宽度
        float h; // 高度
        float dir; // 移动方向（左右）
        float speed; // 移动速度
        float angle; // 旋转角度
        float startTime; // 开始显示时间
        int steps; // 动画步数（生命周期）
    }

    // 随机数生成器（用于动画随机效果）
    private final Random mRandom = new Random(System.currentTimeMillis());

    // 白天背景图层（上下两层）
    private final Layer[] gDay = new Layer[]{new Layer(), new Layer()};
    // 夜晚背景图层（上下两层）
    private final Layer[] gNight = new Layer[]{new Layer(), new Layer()};
    // VCN图层（火山/城堡层，上下两层）
    private final Layer[] gVcnLayer = new Layer[]{new Layer(), new Layer()};
    // 第4层背景（上下两层）
    private final Layer[] gLayer4 = new Layer[]{new Layer(), new Layer()};
    // 第3层背景（上下两层）
    private final Layer[] gLayer3 = new Layer[]{new Layer(), new Layer()};
    // 第2层背景（上下两层）
    private final Layer[] gLayer2 = new Layer[]{new Layer(), new Layer()};
    // 第1层背景（上下两层）
    private final Layer[] gLayer1 = new Layer[]{new Layer(), new Layer()};
    // 翼龙实例
    private final Pterosaur gPterosaur = new Pterosaur();
    // 恐龙实例（两个）
    private final Dinosaur[] gDinosaur = new Dinosaur[]{new Dinosaur(), new Dinosaur()};
    // 火球数组
    private final Fireball[] fbs = new Fireball[FIREBALL_COUNT];

    // 屏幕宽度
    private int screenWidth;
    // 屏幕高度
    private int screenHeight;
    // 背景滚动速度
    private int gBgSpeed;
    // 屏幕密度类型（对应DENSITY_xxx常量）
    private int gDensity;
    // 动画状态（0/1，控制昼夜切换动画）
    private int gAnimation;
    // 昼夜状态（1=白天，0=夜晚）
    private int gDayNight;
    // 昼夜切换速度
    private int gDayAndNightSpeed;
    // VCN图层鼠标偏移X
    private int gVcnMouseOffx;
    // VCN图层鼠标可交互宽度
    private int gVcnMouseW;
    // 翼龙宽度
    private int gPterosaurW;
    // 翼龙高度
    private int gPterosaurH;
    // 翼龙移动速度
    private int gPterosaurSpeed;
    // 恐龙X轴移动速度
    private int gDinosaurSpeedX;
    // 恐龙Y轴移动速度
    private int gDinosaurSpeedY;
    // 火球基础速度
    private int gFireballBaseSpeed;
    // 火球宽度
    private int gFireballW;
    // 火球高度
    private int gFireballH;
    // 火球显示状态（0=隐藏，1=显示）
    private int gFireballsShow;
    // 太阳可交互区域左边界
    private int gSunLeft;
    // 太阳可交互区域右边界
    private int gSunRight;
    // 太阳可交互区域上边界
    private int gSunTop;
    // 太阳可交互区域下边界
    private int gSunBottom;
    // 月亮可交互区域左边界
    private int gMoonLeft;
    // 月亮可交互区域右边界
    private int gMoonRight;
    // 月亮可交互区域上边界
    private int gMoonTop;
    // 月亮可交互区域下边界
    private int gMoonBottom;

    // X轴缩放比例（适配不同屏幕）
    private float mScaleX = 1.0f;
    // Y轴缩放比例（适配不同屏幕）
    private float mScaleY = 1.0f;

    // 上一帧时间戳
    private float gOldTime;
    // 当前帧时间戳
    private float gCurTime;
    // 角色生成时间戳
    private float gGenTime;
    // 帧时间间隔（秒）
    private float gDT;
    // X轴偏移量（用于背景滚动）
    private float gXOffset;

    // 初始化状态标记（全局参数）
    private boolean mInitialized = false;
    // OpenGL初始化状态标记
    private boolean mGLInitialized = false;

    // OpenGL着色器程序ID
    private int mProgram;
    // 顶点位置句柄
    private int mPositionHandle;
    // 纹理坐标句柄
    private int mTexHandle;
    // 投影矩阵句柄
    private int mMatrixHandle;
    // 纹理采样器句柄
    private int mSamplerHandle;
    // 透明度句柄
    private int mAlphaHandle;

    // 白天背景纹理1
    private int mTexBgDay;
    // 白天背景纹理2
    private int mTexBgDay1;
    // 夜晚背景纹理1
    private int mTexBgNight;
    // 夜晚背景纹理2
    private int mTexBgNight1;
    // VCN图层纹理1
    private int mTexLayer5;
    // VCN图层纹理2
    private int mTexLayer51;
    // 第4层背景纹理1
    private int mTexLayer4;
    // 第4层背景纹理2
    private int mTexLayer41;
    // 第3层背景纹理1
    private int mTexLayer3;
    // 第3层背景纹理2
    private int mTexLayer31;
    // 第2层背景纹理1
    private int mTexLayer2;
    // 第2层背景纹理2
    private int mTexLayer21;
    // 第1层背景纹理1
    private int mTexLayer1;
    // 第1层背景纹理2
    private int mTexLayer11;
    // 翼龙纹理
    private int mTexPterosaur;
    // 恐龙纹理
    private int mTexDinosaur;
    // 火球纹理
    private int mTexFireball;

    // 四边形顶点缓冲（用于绘制矩形）
    private FloatBuffer mQuadBuffer;
    // 投影矩阵（正交投影）
    private final float[] mProjectionMatrix = new float[16];

    // X轴偏移像素（用于壁纸滑动适配）
    private float mXOffsetPixels = 0.0f;

    // 触摸事件待处理标记
    private boolean mTouchPending = false;
    // 触摸X坐标
    private float mTouchX = -1.0f;
    // 触摸Y坐标
    private float mTouchY = -1.0f;

    /**
     * 构造方法
     * @param width 初始化宽度
     * @param height 初始化高度
     */
    public WildWorldGL(int width, int height) {
        super(width, height);
    }

    /**
     * 场景创建回调（初始化全局状态）
     */
    @Override
    protected void onCreate() {
        if (!mInitialized) {
            mInitialized = true;
            initState(); // 初始化动画状态参数
        }
    }

    @Override
    public void release() {
        // 释放纹理资源
        int[] tex = new int[] {
                mTexBgDay, mTexBgDay1, mTexBgNight, mTexBgNight1,
                mTexLayer5, mTexLayer51, mTexLayer4, mTexLayer41,
                mTexLayer3, mTexLayer31, mTexLayer2, mTexLayer21,
                mTexLayer1, mTexLayer11, mTexPterosaur, mTexDinosaur,
                mTexFireball
        };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexBgDay = 0;
        mTexBgDay1 = 0;
        mTexBgNight = 0;
        mTexBgNight1 = 0;
        mTexLayer5 = 0;
        mTexLayer51 = 0;
        mTexLayer4 = 0;
        mTexLayer41 = 0;
        mTexLayer3 = 0;
        mTexLayer31 = 0;
        mTexLayer2 = 0;
        mTexLayer21 = 0;
        mTexLayer1 = 0;
        mTexLayer11 = 0;
        mTexPterosaur = 0;
        mTexDinosaur = 0;
        mTexFireball = 0;

        // 释放着色器程序
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    /**
     * 屏幕尺寸变化回调
     * @param width 新宽度
     * @param height 新高度
     */
    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        initState(); // 重新初始化状态（适配新尺寸）
        if (mGLInitialized) {
            // 更新OpenGL视口
            GLES20.glViewport(0, 0, mWidth, mHeight);
            // 更新正交投影矩阵
            Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        }
    }

    /**
     * 设置壁纸偏移（适配桌面滑动）
     * @param xOffset 相对偏移（0-1）
     * @param yOffset Y轴相对偏移（未使用）
     * @param xPixels X轴偏移像素
     * @param yPixels Y轴偏移像素（未使用）
     */
    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffsetPixels = xPixels;
    }

    /**
     * 壁纸命令回调（处理触摸等命令）
     * @param action 命令类型（如android.wallpaper.tap）
     * @param x 触摸X坐标
     * @param y 触摸Y坐标
     * @param z 预留参数
     */
    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            mTouchX = x;
            mTouchY = y;
            mTouchPending = true; // 标记触摸事件待处理
        }
    }

    /**
     * 绘制每一帧（核心渲染逻辑）
     * @param timeMs 系统时间（毫秒）
     */
    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) return; // 未初始化则跳过
        if (!mGLInitialized) {
            Resources res = getResources();
            if (res == null) return;
            initGL(res); // 初始化OpenGL环境
        }

        // 计算X轴偏移（适配壁纸滑动）
        gXOffset = mXOffsetPixels - screenWidth / 2.0f;

        // 计算帧时间间隔
        long now = SystemClock.uptimeMillis();
        gCurTime = now;
        if (gOldTime == 0) {
            gOldTime = gCurTime;
        }
        gDT = (gCurTime - gOldTime) / 1000.0f; // 转换为秒
        if (gDT > MIN_DT) gDT = MIN_DT; // 限制最大时间步长（防止动画跳变）
        gOldTime = gCurTime;

        // 处理待执行的触摸事件
        if (mTouchPending) {
            mTouchPending = false;
            onTouchCommand();
        }

        // 更新动画状态（背景、角色、火球）
        update();

        // 开始绘制
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 清空颜色缓冲区
        GLES20.glUseProgram(mProgram); // 使用着色器程序
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); // 设置混合模式（透明度）
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0); // 设置投影矩阵
        draw(); // 绘制所有元素
    }

    /**
     * 初始化动画状态参数
     */
    private void initState() {
        // 确定屏幕宽高（取最小为宽，最大为高，适配横竖屏）
        screenWidth = Math.min(mWidth, mHeight);
        screenHeight = Math.max(mWidth, mHeight);
        gDensity = 0;
        gAnimation = 1; // 默认动画状态
        gDayNight = 1; // 默认白天
        gGenTime = 0; // 初始化角色生成时间
        gOldTime = 0; // 初始化时间戳
        gFireballsShow = 0; // 火球默认隐藏
        // 初始化角色状态（死亡）
        gPterosaur.alive = 0;
        gPterosaur.duration = 0;
        gDinosaur[UP].alive = 0;
        gDinosaur[DOWN].alive = 0;

        // 初始化火球数组
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            fbs[i] = new Fireball();
            fbs[i].steps = 0;
        }

        // 设置屏幕密度（适配不同分辨率）
        setDensity(getDensity());
        // 预览模式下的偏移适配
        if (isPreview()) {
            mXOffsetPixels = 0.5f;
        }
    }

    /**
     * 获取屏幕密度类型
     * @return 密度类型（DENSITY_xxx常量）
     */
    private int getDensity() {
        if (screenWidth <= 240 && screenHeight <= 320) {
            return DENSITY_240X320;
        }
        if (screenWidth <= 240 && screenHeight <= 400) {
            return DENSITY_240X400;
        }
        if (screenWidth > 320 || screenHeight > 480) {
            return (screenWidth > 480 || screenHeight > 800) ? DENSITY_480X800 : DENSITY_480X800;
        }
        return DENSITY_320X480;
    }

    /**
     * 根据密度类型设置动画参数（核心适配逻辑）
     * @param den 密度类型
     */
    private void setDensity(int den) {
        gDensity = den == 0 ? DENSITY_480X800 : den;

        float baseW; // 基准宽度
        float baseH; // 基准高度
        // 根据密度类型设置基准尺寸
        if (gDensity == DENSITY_240X320) {
            baseW = 240.0f;
            baseH = 320.0f;
        } else if (gDensity == DENSITY_240X400) {
            baseW = 240.0f;
            baseH = 400.0f;
        } else if (gDensity == DENSITY_320X480) {
            baseW = 320.0f;
            baseH = 480.0f;
        } else {
            baseW = 480.0f;
            baseH = 800.0f;
        }

        // 计算缩放比例（适配当前屏幕）
        mScaleX = screenWidth / baseW;
        mScaleY = screenHeight / baseH;

        // 根据不同密度设置具体的动画参数
        if (gDensity == DENSITY_240X320) {
            gBgSpeed = 24;
            gDayAndNightSpeed = 16;
            gFireballBaseSpeed = 80;
            gFireballW = Math.round(32 * mScaleX);
            gFireballH = Math.round(40 * mScaleY);

            initLayer(gDay, 0, 0, screenWidth, 222, 222, 14);
            initLayer(gNight, 0, -222 - 15, screenWidth, 222, -15, 15);

            initLayer(gVcnLayer, 0, 121, screenWidth, 79, 121 + 79, 7);
            gVcnMouseOffx = Math.round(48 * mScaleX);
            gVcnMouseW = Math.round(80 * mScaleX);

            initLayer(gLayer4, 0, 197, screenWidth, 12, 197 + 12, 6);
            initLayer(gLayer3, 0, 206, screenWidth, 9, 206 + 9, 19);
            initLayer(gLayer2, 0, 213, screenWidth, 17, 213 + 17, 40);
            initLayer(gLayer1, 0, 227, screenWidth, 38, 227 + 38, 55);

            gPterosaur.x = -100 * mScaleX;
            gPterosaur.y = (72 * mScaleY) * 0.5f;
            gPterosaurW = Math.round(100 * mScaleX);
            gPterosaurH = Math.round(72 * mScaleY);
            gPterosaur.scale = 1.0f;
            gPterosaurSpeed = 48;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = (94 + 178 * (1 - 0.8f)) * mScaleY;
            gDinosaur[UP].w = 178 * 0.8f * mScaleX;
            gDinosaur[UP].h = 149 * 0.8f * mScaleY;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = (102 + 178 * (1 - 0.9f)) * mScaleY;
            gDinosaur[DOWN].w = 178 * 0.9f * mScaleX;
            gDinosaur[DOWN].h = 149 * 0.9f * mScaleY;
            gDinosaurSpeedX = 26;
            gDinosaurSpeedY = 6;

            gSunLeft = Math.round(160 * mScaleX);
            gSunRight = Math.round(240 * mScaleX);
            gSunTop = Math.round(40 * mScaleY);
            gSunBottom = Math.round(160 * mScaleY);

            gMoonLeft = Math.round(20 * mScaleX);
            gMoonRight = Math.round(120 * mScaleX);
            gMoonTop = Math.round(60 * mScaleY);
            gMoonBottom = Math.round(160 * mScaleY);
        } else if (gDensity == DENSITY_240X400) {
            gBgSpeed = 24;
            gDayAndNightSpeed = 16;
            gFireballBaseSpeed = 80;
            gFireballW = Math.round(32 * mScaleX);
            gFireballH = Math.round(40 * mScaleY);

            initLayer(gDay, 0, 0, screenWidth, 288, 288, 18);
            initLayer(gNight, 0, -288 - 18, screenWidth, 288, -18, 18);

            initLayer(gVcnLayer, 0, 177, screenWidth, 81, 177 + 81, 14);
            gVcnMouseOffx = Math.round(48 * mScaleX);
            gVcnMouseW = Math.round(80 * mScaleX);

            initLayer(gLayer4, 0, 260, screenWidth, 13, 260 + 13, 15);
            initLayer(gLayer3, 0, 270, screenWidth, 10, 270 + 10, 26);
            initLayer(gLayer2, 0, 282, screenWidth, 16, 282 + 16, 49);
            initLayer(gLayer1, 0, 296, screenWidth, 39, 296 + 39, 65);

            gPterosaur.x = -134 * mScaleX;
            gPterosaur.y = (96 * mScaleY) * 0.5f;
            gPterosaurW = Math.round(134 * mScaleX);
            gPterosaurH = Math.round(96 * mScaleY);
            gPterosaur.scale = 1.0f;
            gPterosaurSpeed = 48;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = (138 + 213 * (1 - 0.8f)) * mScaleY;
            gDinosaur[UP].w = 213 * 0.8f * mScaleX;
            gDinosaur[UP].h = 178 * 0.8f * mScaleY;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = (145 + 213 * (1 - 0.9f)) * mScaleY;
            gDinosaur[DOWN].w = 213 * 0.9f * mScaleX;
            gDinosaur[DOWN].h = 178 * 0.9f * mScaleY;
            gDinosaurSpeedX = 28;
            gDinosaurSpeedY = 6;

            gSunLeft = Math.round(160 * mScaleX);
            gSunRight = Math.round(240 * mScaleX);
            gSunTop = Math.round(40 * mScaleY);
            gSunBottom = Math.round(160 * mScaleY);

            gMoonLeft = Math.round(20 * mScaleX);
            gMoonRight = Math.round(120 * mScaleX);
            gMoonTop = Math.round(60 * mScaleY);
            gMoonBottom = Math.round(160 * mScaleY);
        } else if (gDensity == DENSITY_320X480) {
            gBgSpeed = 30;
            gDayAndNightSpeed = 20;
            gFireballBaseSpeed = 90;
            gFireballW = Math.round(40 * mScaleX);
            gFireballH = Math.round(60 * mScaleY);

            initLayer(gDay, 0, 0, screenWidth, 339, 339, 21);
            initLayer(gNight, 0, -335 - 25, screenWidth, 335, -25, 25);

            initLayer(gVcnLayer, 0, 206, screenWidth, 104, 206 + 104, 11);
            gVcnMouseOffx = Math.round(78 * mScaleX);
            gVcnMouseW = Math.round(100 * mScaleX);

            initLayer(gLayer4, 0, 304, screenWidth, 15, 304 + 15, 9);
            initLayer(gLayer3, 0, 315, screenWidth, 11, 315 + 11, 27);
            initLayer(gLayer2, 0, 329, screenWidth, 19, 329 + 19, 57);
            initLayer(gLayer1, 0, 355, screenWidth, 48, 355 + 48, 76);

            gPterosaur.x = -179 * mScaleX;
            gPterosaur.y = (128 * mScaleY) * 0.5f;
            gPterosaurW = Math.round(179 * mScaleX);
            gPterosaurH = Math.round(128 * mScaleY);
            gPterosaur.scale = 1.0f;
            gPterosaurSpeed = 64;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = (125 + 284 * (1 - 0.8f)) * mScaleY;
            gDinosaur[UP].w = 284 * 0.8f * mScaleX;
            gDinosaur[UP].h = 238 * 0.8f * mScaleY;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = (145 + 284 * (1 - 0.9f)) * mScaleY;
            gDinosaur[DOWN].w = 284 * 0.9f * mScaleX;
            gDinosaur[DOWN].h = 238 * 0.9f * mScaleY;
            gDinosaurSpeedX = 32;
            gDinosaurSpeedY = 8;

            gSunLeft = Math.round(220 * mScaleX);
            gSunRight = Math.round(320 * mScaleX);
            gSunTop = Math.round(40 * mScaleY);
            gSunBottom = Math.round(160 * mScaleY);

            gMoonLeft = Math.round(30 * mScaleX);
            gMoonRight = Math.round(130 * mScaleX);
            gMoonTop = Math.round(80 * mScaleY);
            gMoonBottom = Math.round(180 * mScaleY);
        } else {
            gBgSpeed = 36;
            gDayAndNightSpeed = 24;
            gFireballBaseSpeed = 100;
            gFireballW = Math.round(60 * mScaleX);
            gFireballH = Math.round(100 * mScaleY);

            initLayer(gDay, 0, 0, screenWidth, 565, 565, 35);
            initLayer(gNight, 0, -560 - 40, screenWidth, 560, -40, 40);

            initLayer(gVcnLayer, 0, 333, screenWidth, 175, 333 + 175, 20);
            gVcnMouseOffx = Math.round(124 * mScaleX);
            gVcnMouseW = Math.round(150 * mScaleX);

            initLayer(gLayer4, 0, 506, screenWidth, 25, 506 + 25, 15);
            initLayer(gLayer3, 0, 525, screenWidth, 18, 525 + 18, 45);
            initLayer(gLayer2, 0, 550, screenWidth, 32, 550 + 32, 95);
            initLayer(gLayer1, 0, 595, screenWidth, 80, 595 + 80, 128);

            gPterosaur.x = -270 * mScaleX;
            gPterosaur.y = (215 * mScaleY) * 0.5f;
            gPterosaurW = Math.round(270 * mScaleX);
            gPterosaurH = Math.round(215 * mScaleY);
            gPterosaur.scale = 1.0f;
            gPterosaurSpeed = 64;

            gDinosaur[UP].distance = DINOSAUR1_DISTANCE;
            gDinosaur[UP].x = screenWidth;
            gDinosaur[UP].y = (225 + 426 * (1 - 0.8f)) * mScaleY;
            gDinosaur[UP].w = 426 * 0.8f * mScaleX;
            gDinosaur[UP].h = 390 * 0.8f * mScaleY;
            gDinosaur[DOWN].distance = DINOSAUR2_DISTANCE;
            gDinosaur[DOWN].x = screenWidth * 1.5f;
            gDinosaur[DOWN].y = (256 + 426 * (1 - 0.9f)) * mScaleY;
            gDinosaur[DOWN].w = 426 * 0.9f * mScaleX;
            gDinosaur[DOWN].h = 390 * 0.9f * mScaleY;
            gDinosaurSpeedX = 32;
            gDinosaurSpeedY = 8;

            gSunLeft = Math.round(320 * mScaleX);
            gSunRight = Math.round(480 * mScaleX);
            gSunTop = Math.round(60 * mScaleY);
            gSunBottom = Math.round(240 * mScaleY);

            gMoonLeft = Math.round(40 * mScaleX);
            gMoonRight = Math.round(200 * mScaleX);
            gMoonTop = Math.round(100 * mScaleY);
            gMoonBottom = Math.round(300 * mScaleY);
        }
    }

    /**
     * 初始化图层参数
     * @param layers 图层数组（上下两层）
     * @param x 初始X坐标
     * @param yUp 上层Y坐标
     * @param w 宽度
     * @param hUp 上层高度
     * @param yDown 下层Y坐标
     * @param hDown 下层高度
     */
    private void initLayer(Layer[] layers, float x, float yUp, float w, float hUp, float yDown, float hDown) {
        layers[UP].x = x * mScaleX;
        layers[UP].y = yUp * mScaleY;
        layers[UP].w = w;
        layers[UP].h = hUp * mScaleY;
        layers[DOWN].x = x * mScaleX;
        layers[DOWN].y = yDown * mScaleY;
        layers[DOWN].w = w;
        layers[DOWN].h = hDown * mScaleY;
    }

    /**
     * 更新所有动画元素状态
     */
    private void update() {
        // 计算昼夜切换步长
        float dayNightStep = gDayAndNightSpeed * (gDT / 0.025f);
        // 白天切换到夜晚
        if (gDayNight > 0) {
            if (gAnimation == 0) {
                gNight[UP].y -= dayNightStep;
                gNight[DOWN].y -= dayNightStep;
                // 夜晚图层完全显示后切换动画状态
                if (gNight[DOWN].y + gNight[DOWN].h <= 0) {
                    gAnimation = 1;
                }
            }
        } else { // 夜晚切换到白天
            if (gAnimation == 1) {
                gNight[UP].y += dayNightStep;
                gNight[DOWN].y += dayNightStep;
                // 白天图层完全显示后切换动画状态
                if (gNight[UP].y >= 0) {
                    gAnimation = 0;
                    gNight[UP].y = 0;
                }
            }
        }

        // 更新背景图层位置
        updateLayers();
        // 更新翼龙动画
        updatePterosaur();
        // 更新恐龙动画（上/下）
        updateDinosaur(UP);
        updateDinosaur(DOWN);
        // 更新火球动画
        updateFireballs();

        // 随机生成角色（翼龙/恐龙）
        if (gCurTime - gGenTime > GEN_TIME) {
            gGenTime = gCurTime;
            float random = randf(GEN_RANDOM);

            // 生成翼龙
            if (gPterosaur.alive == 0 && random > 0.04f && random < 0.14f) {
                gPterosaur.time = uptimeMillis();
                gPterosaur.alive = 1;
                gPterosaur.x = -screenWidth;
                gPterosaur.y = 32 + screenHeight * randf(0.2f);
            }

            // 生成上层恐龙
            if (random < 0.1f) {
                if (gDinosaur[UP].alive == 0) {
                    gDinosaur[UP].time = uptimeMillis();
                    gDinosaur[UP].alive = 1;
                    gDinosaur[UP].x = screenWidth * 3;
                    gDinosaur[UP].stepY = 0;
                }
            } else if (random < 0.18f) { // 生成下层恐龙
                if (gDinosaur[DOWN].alive == 0) {
                    gDinosaur[UP].time = uptimeMillis();
                    gDinosaur[DOWN].alive = 1;
                    gDinosaur[DOWN].x = screenWidth * 3;
                    gDinosaur[DOWN].stepY = 0;
                }
            }
        }
    }

    /**
     * 更新背景图层滚动位置
     */
    private void updateLayers() {
        // VCN图层滚动
        gVcnLayer[UP].x += gBgSpeed * gDT * (1 - VCN_DISTANCE);
        if (gVcnLayer[UP].x + gXOffset >= screenWidth) gVcnLayer[UP].x = -gXOffset;

        // 第4层滚动
        gLayer4[UP].x += gBgSpeed * gDT * (1 - LAYER4_DISTANCE);
        if (gLayer4[UP].x + gXOffset >= screenWidth) gLayer4[UP].x = -gXOffset;

        // 第3层滚动
        gLayer3[UP].x += gBgSpeed * gDT * (1 - LAYER3_DISTANCE);
        if (gLayer3[UP].x + gXOffset >= screenWidth) gLayer3[UP].x = -gXOffset;

        // 第2层滚动
        gLayer2[UP].x += gBgSpeed * gDT * (1 - LAYER2_DISTANCE);
        if (gLayer2[UP].x + gXOffset >= screenWidth) gLayer2[UP].x = -gXOffset;

        // 第1层滚动
        gLayer1[UP].x += gBgSpeed * gDT * (1 - LAYER1_DISTANCE);
        if (gLayer1[UP].x + gXOffset >= screenWidth) gLayer1[UP].x = -gXOffset;
    }

    /**
     * 更新翼龙动画状态（位置/缩放）
     */
    private void updatePterosaur() {
        if (gPterosaur.alive != 0) {
            // 翼龙缩放动画（呼吸/扇动效果）
            if (gCurTime - gPterosaur.time > PTERO_DT) {
                gPterosaur.time = gCurTime;
                if (gPterosaur.duration != 0) {
                    if (gPterosaur.scale < PTEROSAUR_DISTANCE) {
                        gPterosaur.scale = PTEROSAUR_DISTANCE;
                    } else if (gPterosaur.scale == PTEROSAUR_DISTANCE) {
                        gPterosaur.scale = PTEROSAUR_DISTANCE + 0.06f;
                    } else {
                        gPterosaur.scale = PTEROSAUR_DISTANCE;
                        gPterosaur.duration = 0;
                    }
                } else {
                    if (gPterosaur.scale > PTEROSAUR_DISTANCE) {
                        gPterosaur.scale = PTEROSAUR_DISTANCE;
                    } else if (gPterosaur.scale == PTEROSAUR_DISTANCE) {
                        gPterosaur.scale = PTEROSAUR_DISTANCE - 0.06f;
                    } else {
                        gPterosaur.scale = PTEROSAUR_DISTANCE;
                        gPterosaur.duration = 1;
                    }
                }
                // 更新翼龙尺寸
                gPterosaur.w = gPterosaurW * gPterosaur.scale;
                gPterosaur.h = gPterosaurH * gPterosaur.scale;
            }
            // 翼龙移动
            gPterosaur.x += gPterosaurSpeed * gDT * gPterosaur.scale;
        }
    }

    /**
     * 更新恐龙动画状态（位置/Y轴摆动）
     * @param ud 方向（UP/DOWN）
     */
    private void updateDinosaur(int ud) {
        Dinosaur d = gDinosaur[ud];
        if (d.alive != 0) {
            // 恐龙Y轴摆动周期
            if (gCurTime - d.time > DINO_DT) {
                d.time = gCurTime;
                d.stepY = 0;
            } else {
                d.stepY += gDinosaurSpeedY * (1.7f - d.distance) * gDT;
            }
            // 恐龙X轴移动
            d.x -= gDinosaurSpeedX * (1.7f - d.distance) * gDT;
        }
    }

    /**
     * 更新火球动画状态（位置/生命周期）
     */
    private void updateFireballs() {
        if (gFireballsShow != 0) {
            int count = 0;
            for (int i = 0; i < FIREBALL_COUNT; i++) {
                Fireball f = fbs[i];
                if (f.steps > 0) {
                    // 火球开始运动
                    if (gCurTime > f.startTime) {
                        f.x += gBgSpeed * gDT * (1 - FIREBALL_DISTANCE)
                                + f.dir * f.speed * FIREBALL_DISTANCE * gDT;
                        f.y -= f.speed * FIREBALL_DISTANCE * gDT;
                        f.steps -= 1; // 减少生命周期
                        f.angle = 120.0f;
                    } else {
                        // 火球未激活时随背景滚动
                        f.x += gBgSpeed * gDT * (1 - FIREBALL_DISTANCE);
                    }
                    if (f.steps > 0) {
                        count++;
                    }
                }
            }
            // 所有火球生命周期结束后隐藏
            if (count == 0) {
                gFireballsShow = 0;
            }
        }
    }

    /**
     * 绘制所有元素
     */
    private void draw() {
        // 绘制昼夜背景
        drawDayAndNightLayer();
        // 绘制其他图层（VCN/角色/火球）
        drawLayers();
    }

    /**
     * 绘制昼夜背景图层
     */
    private void drawDayAndNightLayer() {
        if (gDayNight > 0) { // 白天状态
            drawLayer(mTexBgDay1, gDay[UP]);
            drawLayer(mTexBgDay, gDay[DOWN]);

            // 白天切夜晚动画中绘制夜晚图层
            if (gAnimation == 0) {
                drawLayer(mTexBgNight1, gNight[UP]);
                drawLayer(mTexBgNight, gNight[DOWN]);
            }
        } else { // 夜晚状态
            // 夜晚切白天动画中绘制白天图层
            if (gAnimation != 0) {
                drawLayer(mTexBgDay1, gDay[UP]);
                drawLayer(mTexBgDay, gDay[DOWN]);
            }
            drawLayer(mTexBgNight1, gNight[UP]);
            drawLayer(mTexBgNight, gNight[DOWN]);
        }
    }

    /**
     * 绘制所有前景图层（VCN/角色/火球/背景层）
     */
    private void drawLayers() {
        // 绘制火球
        if (gFireballsShow != 0) {
            for (int i = 0; i < FIREBALL_COUNT; i++) {
                Fireball f = fbs[i];
                if (gCurTime > f.startTime && f.steps > 0) {
                    float offX = f.x + gXOffset;
                    drawRect(mTexFireball, offX, f.y, offX + f.w, f.y + f.h);
                }
            }
        }

        // 绘制VCN图层（循环滚动）
        float offX = gVcnLayer[UP].x + gXOffset;
        while (offX < 0) {
            offX += gVcnLayer[UP].w;
        }
        drawRect(mTexLayer5, offX, gVcnLayer[UP].y, offX + gVcnLayer[UP].w,
                gVcnLayer[UP].y + gVcnLayer[UP].h);
        drawRect(mTexLayer5, offX - gVcnLayer[UP].w, gVcnLayer[UP].y, offX,
                gVcnLayer[UP].y + gVcnLayer[UP].h);
        drawLayer(mTexLayer51, gVcnLayer[DOWN]);

        // 绘制翼龙
        drawPterosaur();

        // 绘制第4层背景（循环滚动）
        offX = gLayer4[UP].x + gXOffset;
        while (offX < 0) {
            offX += gLayer4[UP].w;
        }
        drawRect(mTexLayer4, offX, gLayer4[UP].y, offX + gLayer4[UP].w,
                gLayer4[UP].y + gLayer4[UP].h);
        drawRect(mTexLayer4, offX - gLayer4[UP].w, gLayer4[UP].y, offX,
                gLayer4[UP].y + gLayer4[UP].h);
        drawLayer(mTexLayer41, gLayer4[DOWN]);

        // 绘制上层恐龙
        drawDinosaur(UP);

        // 绘制第3层背景（循环滚动）
        offX = gLayer3[UP].x + gXOffset;
        while (offX < 0) {
            offX += gLayer3[UP].w;
        }
        drawRect(mTexLayer3, offX, gLayer3[UP].y, offX + gLayer3[UP].w,
                gLayer3[UP].y + gLayer3[UP].h);
        drawRect(mTexLayer3, offX - gLayer3[UP].w, gLayer3[UP].y, offX,
                gLayer3[UP].y + gLayer3[UP].h);
        drawLayer(mTexLayer31, gLayer3[DOWN]);

        // 绘制下层恐龙
        drawDinosaur(DOWN);

        // 绘制第2层背景（循环滚动）
        offX = gLayer2[UP].x + gXOffset;
        while (offX < 0) {
            offX += gLayer2[UP].w;
        }
        drawRect(mTexLayer2, offX, gLayer2[UP].y, offX + gLayer2[UP].w,
                gLayer2[UP].y + gLayer2[UP].h);
        drawRect(mTexLayer2, offX - gLayer2[UP].w, gLayer2[UP].y, offX,
                gLayer2[UP].y + gLayer2[UP].h);
        drawLayer(mTexLayer21, gLayer2[DOWN]);

        // 绘制第1层背景（循环滚动）
        offX = gLayer1[UP].x + gXOffset;
        while (offX < 0) {
            offX += gLayer1[UP].w;
        }
        drawRect(mTexLayer1, offX, gLayer1[UP].y, offX + gLayer1[UP].w,
                gLayer1[UP].y + gLayer1[UP].h);
        drawRect(mTexLayer1, offX - gLayer1[UP].w, gLayer1[UP].y, offX,
                gLayer1[UP].y + gLayer1[UP].h);
        drawLayer(mTexLayer11, gLayer1[DOWN]);
    }

    /**
     * 绘制翼龙
     */
    private void drawPterosaur() {
        if (gPterosaur.alive != 0) {
            float offX = gPterosaur.x + gXOffset;
            // 翼龙在屏幕内才绘制
            if (offX + gPterosaur.w > 0 && offX < screenWidth) {
                drawRect(mTexPterosaur, offX, gPterosaur.y, offX + gPterosaur.w, gPterosaur.y + gPterosaur.h);
            } else if (gPterosaur.x + gXOffset > screenWidth) {
                // 翼龙移出屏幕后标记为死亡
                gPterosaur.alive = 0;
            }
        }
    }

    /**
     * 绘制恐龙
     * @param ud 方向（UP/DOWN）
     */
    private void drawDinosaur(int ud) {
        Dinosaur d = gDinosaur[ud];
        if (d.alive != 0) {
            float offX = d.x + gXOffset;
            // 恐龙在屏幕内才绘制
            if (offX + d.w > 0 && offX < screenWidth) {
                drawRect(mTexDinosaur, offX, d.y - d.stepY, offX + d.w, d.y + d.h - d.stepY);
            } else if (d.x + d.w + gXOffset < 0) {
                // 恐龙移出屏幕后标记为死亡
                d.alive = 0;
            }
        }
    }

    /**
     * 处理触摸命令（昼夜切换/生成火球）
     */
    private void onTouchCommand() {
        float x = mTouchX;
        float y = mTouchY;

        // 点击太阳：白天切夜晚
        if (x > gSunLeft && x < gSunRight && y > gSunTop && y < gSunBottom) {
            if (gDayNight > 0 && gAnimation == 1) {
                gDayNight = 0;
            }
        }
        // 点击月亮：夜晚切白天
        else if (x > gMoonLeft && x < gMoonRight && y > gMoonTop && y < gMoonBottom) {
            if (gDayNight == 0 && gAnimation == 0) {
                gDayNight = 1;
            }
        }
        // 点击VCN图层：生成火球
        else {
            float xPos = gVcnLayer[UP].x + gXOffset;
            while (xPos < 0) {
                xPos += screenWidth;
            }
            // 计算VCN图层可交互区域
            float vcn1MouseLeft = xPos + gVcnMouseOffx - gVcnMouseW / 2.0f;
            float vcn1MouseRight = vcn1MouseLeft + gVcnMouseW;
            float vcn2MouseLeft = vcn1MouseLeft - screenWidth;
            float vcn2MouseRight = vcn2MouseLeft + gVcnMouseW;

            // 点击VCN图层第一块区域
            if (x > vcn1MouseLeft && x < vcn1MouseRight && y > gVcnLayer[UP].y
                    && y < gVcnLayer[UP].y + gVcnLayer[UP].h) {
                createFireballs(vcn1MouseLeft + gVcnMouseW / 2.0f - gXOffset);
            }
            // 点击VCN图层第二块区域（循环滚动）
            else if (x > vcn2MouseLeft && x < vcn2MouseRight && y > gVcnLayer[UP].y
                    && y < gVcnLayer[UP].y + gVcnLayer[UP].h) {
                createFireballs(vcn2MouseLeft + gVcnMouseW / 2.0f - gXOffset);
            }
        }
    }

    /**
     * 生成火球特效
     * @param xpos 火球生成的X坐标
     */
    private void createFireballs(float xpos) {
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            Fireball f = fbs[i];
            if (f.steps <= 0) {
                // 随机参数（大小/速度/方向）
                float random = randf(0.3f);
                float scale = 0.6f + random;
                f.w = gFireballW * scale;
                f.h = gFireballH * scale;
                f.x = xpos - f.w / 2.0f;
                f.y = gVcnLayer[UP].y + 8;
                f.speed = gFireballBaseSpeed + gFireballBaseSpeed * random;
                f.dir = 0.3f - randf(0.6f);
                f.steps = (int) (30 + random * 40);
                f.startTime = uptimeMillis() + randf(0.4f) * 10000.0f;
            }
        }
        gFireballsShow = 1; // 显示火球
    }

    /**
     * 生成指定范围的随机浮点数
     * @param range 随机范围（0 ~ range）
     * @return 随机值
     */
    private float randf(float range) {
        return mRandom.nextFloat() * range;
    }

    /**
     * 获取系统运行时间（毫秒）
     * @return 运行时间
     */
    private float uptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    /**
     * 绘制单个图层
     * @param tex 纹理ID
     * @param layer 图层数据
     */
    private void drawLayer(int tex, Layer layer) {
        drawRect(tex, layer.x, layer.y, layer.x + layer.w, layer.y + layer.h);
    }

    /**
     * 初始化OpenGL环境（着色器/纹理/投影矩阵）
     * @param res 资源管理器
     */
    private void initGL(Resources res) {
        mGLInitialized = true;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); // 禁用深度测试
        GLES20.glEnable(GLES20.GL_BLEND); // 启用混合（透明度）
        GLES20.glViewport(0, 0, mWidth, mHeight); // 设置视口

        // 创建正交投影矩阵
        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        // 顶点着色器源码
        String vs = RawResourceLoader.readRawText(res, R.raw.wildworld_vs);

        // 片段着色器源码
        String fs = RawResourceLoader.readRawText(res, R.raw.wildworld_fs);

        // 创建着色器程序
        mProgram = createProgram(vs, fs);
        // 获取着色器变量句柄
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");

        // 创建四边形顶点缓冲
        mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // 加载所有纹理
        mTexBgDay = loadTexture(res, R.drawable.ww_bgday);
        mTexBgDay1 = loadTexture(res, R.drawable.ww_bgday1);
        mTexBgNight = loadTexture(res, R.drawable.ww_bgnight);
        mTexBgNight1 = loadTexture(res, R.drawable.ww_bgnight1);
        mTexLayer5 = loadTexture(res, R.drawable.ww_layer5);
        mTexLayer51 = loadTexture(res, R.drawable.ww_layer51);
        mTexLayer4 = loadTexture(res, R.drawable.ww_layer4);
        mTexLayer41 = loadTexture(res, R.drawable.ww_layer41);
        mTexLayer3 = loadTexture(res, R.drawable.ww_layer3);
        mTexLayer31 = loadTexture(res, R.drawable.ww_layer31);
        mTexLayer2 = loadTexture(res, R.drawable.ww_layer2);
        mTexLayer21 = loadTexture(res, R.drawable.ww_layer21);
        mTexLayer1 = loadTexture(res, R.drawable.ww_layer1);
        mTexLayer11 = loadTexture(res, R.drawable.ww_layer11);
        mTexPterosaur = loadTexture(res, R.drawable.ww_pterosaur);
        mTexDinosaur = loadTexture(res, R.drawable.ww_dinosaur);
        mTexFireball = loadTexture(res, R.drawable.ww_fireball);
    }

    /**
     * 绘制矩形（核心绘制方法）
     * @param texture 纹理ID
     * @param x0 左上角X
     * @param y0 左上角Y
     * @param x1 右下角X
     * @param y1 右下角Y
     */
    private void drawRect(int texture, float x0, float y0, float x1, float y1) {
        // 构建顶点数据（位置+纹理坐标）
        float[] verts = new float[] {
            x0, y0, 0.0f, 0.0f,
            x0, y1, 0.0f, 1.0f,
            x1, y1, 1.0f, 1.0f,
            x1, y0, 1.0f, 0.0f
        };

        // 加载顶点数据到缓冲
        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        // 设置顶点位置属性
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 设置纹理坐标属性
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexHandle);
        GLES20.glVertexAttribPointer(mTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        // 绑定纹理并设置采样器
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glUniform1f(mAlphaHandle, 1.0f); // 不透明

        // 绘制四边形（三角扇）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        // 禁用属性数组
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexHandle);
    }

    /**
     * 加载纹理资源
     * @param res 资源管理器
     * @param resId 纹理资源ID
     * @return 纹理ID
     */
    private int loadTexture(Resources res, int resId) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false; // 禁用自动缩放
        Bitmap bmp = BitmapFactory.decodeResource(res, resId, opts);
        if (bmp == null) return 0;

        // 生成纹理ID
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);

        // 设置纹理参数
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 加载纹理数据
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle(); // 释放位图内存
        return tex[0];
    }

    /**
     * 创建着色器程序
     * @param vs 顶点着色器源码
     * @param fs 片段着色器源码
     * @return 程序ID
     */
    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs); // 加载顶点着色器
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs); // 加载片段着色器
        int program = GLES20.glCreateProgram(); // 创建程序
        GLES20.glAttachShader(program, v); // 附加顶点着色器
        GLES20.glAttachShader(program, f); // 附加片段着色器
        GLES20.glLinkProgram(program); // 链接程序
        return program;
    }

    /**
     * 加载单个着色器
     * @param type 着色器类型（GL_VERTEX_SHADER/GL_FRAGMENT_SHADER）
     * @param source 着色器源码
     * @return 着色器ID
     */
    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type); // 创建着色器
        GLES20.glShaderSource(shader, source); // 设置源码
        GLES20.glCompileShader(shader); // 编译着色器
        return shader;
    }
}