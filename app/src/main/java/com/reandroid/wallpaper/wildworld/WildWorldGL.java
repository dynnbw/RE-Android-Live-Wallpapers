package com.reandroid.wallpaper.wildworld;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 野生世界动态壁纸的OpenGL ES渲染核心类
 * 负责着色器编译、纹理加载、绘制调用 —— 不包含业务逻辑。
 * 所有动画状态和物理逻辑委托给 {@link WildWorldScene}。
 */
public class WildWorldGL extends GLESScene {

    // ---- 场景逻辑层 ----
    private final Context mContext;
    private final WildWorldScene mScene;

    // ---- OpenGL 状态 ----
    private boolean mGLInitialized = false;

    // 着色器程序句柄
    private int mProgram;
    private int mPositionHandle;
    private int mTexHandle;
    private int mMatrixHandle;
    private int mSamplerHandle;
    private int mAlphaHandle;

    // 纹理 ID
    private int mTexBgDay;
    private int mTexBgDay1;
    private int mTexBgNight;
    private int mTexBgNight1;
    private int mTexLayer5;
    private int mTexLayer51;
    private int mTexLayer4;
    private int mTexLayer41;
    private int mTexLayer3;
    private int mTexLayer31;
    private int mTexLayer2;
    private int mTexLayer21;
    private int mTexLayer1;
    private int mTexLayer11;
    private int mTexPterosaur;
    private int mTexDinosaur;
    private int mTexFireball;

    // 顶点缓冲 / 投影矩阵
    private FloatBuffer mQuadBuffer;
    private final float[] mProjectionMatrix = new float[16];
    /**
     * drawRect 的顶点模板：z 与 uv 固定，每帧只有 4 个角点的 x/y 会变。
     * drawRect 每帧要调几十次，模板复用避免每次重新分配整个数组。
     */
    private static final float[] QUAD_TEMPLATE = {
            0f, 0f, 0.0f, 0.0f,
            0f, 0f, 0.0f, 1.0f,
            0f, 0f, 1.0f, 1.0f,
            0f, 0f, 1.0f, 0.0f
    };
    private final float[] mQuadVerts = QUAD_TEMPLATE.clone();

    // ---- 构造方法 ----

    /**
     * @param width  初始宽度
     * @param height 初始高度
     */
    public WildWorldGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new WildWorldScene();
    }

    // ---- GLESScene 生命周期 ----

    @Override
    protected void onCreate() {
        mScene.initState(mWidth, mHeight);
        if (isPreview()) {
            mScene.mXOffsetPixels = 0.5f;
        }
    }

    @Override
    public void release() {
        int[] tex = new int[] {
                mTexBgDay, mTexBgDay1, mTexBgNight, mTexBgNight1,
                mTexLayer5, mTexLayer51, mTexLayer4, mTexLayer41,
                mTexLayer3, mTexLayer31, mTexLayer2, mTexLayer21,
                mTexLayer1, mTexLayer11, mTexPterosaur, mTexDinosaur,
                mTexFireball
        };
        GLES30.glDeleteTextures(tex.length, tex, 0);
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

        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.initState(width, height);
        if (mGLInitialized) {
            GLES30.glViewport(0, 0, mWidth, mHeight);
            Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.mXOffsetPixels = xPixels;
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            mScene.mTouchX = x;
            mScene.mTouchY = y;
            mScene.mTouchPending = true;
        }
    }

    // ---- 每帧绘制 ----

    @Override
    public void drawFrame(long timeMs) {
        if (!mGLInitialized) {
            initGL();
        }

        // 更新场景逻辑（时间步长、触摸处理、动画状态）
        mScene.updateFrame(timeMs);

        // 执行 OpenGL 绘制
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(mProgram);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0);
        draw();
    }

    // ---- 绘制方法 ----

    /**
     * 绘制所有元素
     */
    private void draw() {
        drawDayAndNightLayer();
        drawLayers();
    }

    /**
     * 绘制昼夜背景图层
     */
    private void drawDayAndNightLayer() {
        if (mScene.mDayNight > 0) {
            drawLayer(mTexBgDay1, mScene.mDay[WildWorldScene.UP]);
            drawLayer(mTexBgDay, mScene.mDay[WildWorldScene.DOWN]);

            if (mScene.mAnimation == 0) {
                drawLayer(mTexBgNight1, mScene.mNight[WildWorldScene.UP]);
                drawLayer(mTexBgNight, mScene.mNight[WildWorldScene.DOWN]);
            }
        } else {
            if (mScene.mAnimation != 0) {
                drawLayer(mTexBgDay1, mScene.mDay[WildWorldScene.UP]);
                drawLayer(mTexBgDay, mScene.mDay[WildWorldScene.DOWN]);
            }
            drawLayer(mTexBgNight1, mScene.mNight[WildWorldScene.UP]);
            drawLayer(mTexBgNight, mScene.mNight[WildWorldScene.DOWN]);
        }
    }

    /**
     * 绘制所有前景图层（VCN / 角色 / 火球 / 背景层）
     */
    private void drawLayers() {
        // 火球
        if (mScene.mFireballsShow != 0) {
            for (int i = 0; i < WildWorldScene.FIREBALL_COUNT; i++) {
                WildWorldScene.Fireball f = mScene.mFireballs[i];
                if (mScene.mCurTime > f.startTime && f.steps > 0) {
                    float offX = f.x + mScene.mXOffset;
                    drawRect(mTexFireball, offX, f.y, offX + f.w, f.y + f.h);
                }
            }
        }

        // VCN 图层
        float offX = drawLayerSeamless(mTexLayer5, mTexLayer51,
                mScene.mVcnLayer, mScene.mXOffset);

        // 翼龙
        drawPterosaur();

        // 第 4 ～ 1 层背景
        offX = drawLayerSeamless(mTexLayer4, mTexLayer41, mScene.mLayer4, mScene.mXOffset);
        drawDinosaur(WildWorldScene.UP);
        offX = drawLayerSeamless(mTexLayer3, mTexLayer31, mScene.mLayer3, mScene.mXOffset);
        drawDinosaur(WildWorldScene.DOWN);
        offX = drawLayerSeamless(mTexLayer2, mTexLayer21, mScene.mLayer2, mScene.mXOffset);
        offX = drawLayerSeamless(mTexLayer1, mTexLayer11, mScene.mLayer1, mScene.mXOffset);
    }

    /**
     * 绘制翼龙
     */
    private void drawPterosaur() {
        if (mScene.mPterosaur.alive != 0) {
            float offX = mScene.mPterosaur.x + mScene.mXOffset;
            if (offX + mScene.mPterosaur.w > 0 && offX < mScene.mScreenWidth) {
                drawRect(mTexPterosaur, offX, mScene.mPterosaur.y,
                        offX + mScene.mPterosaur.w, mScene.mPterosaur.y + mScene.mPterosaur.h);
            }
        }
    }

    /**
     * 绘制恐龙
     * @param ud 方向（UP/DOWN）
     */
    private void drawDinosaur(int ud) {
        WildWorldScene.Dinosaur d = mScene.mDinosaur[ud];
        if (d.alive != 0) {
            float offX = d.x + mScene.mXOffset;
            if (offX + d.w > 0 && offX < mScene.mScreenWidth) {
                drawRect(mTexDinosaur, offX, d.y - d.stepY,
                        offX + d.w, d.y + d.h - d.stepY);
            }
        }
    }

    private void drawLayer(int tex, WildWorldScene.Layer layer) {
        drawRect(tex, layer.x, layer.y, layer.x + layer.w, layer.y + layer.h);
    }

    /**
     * 绘制图层的上下两层，均循环滚动
     * @return 对齐后的 X 偏移量
     */
    private float drawLayerSeamless(int texUp, int texDown, WildWorldScene.Layer[] layers, float gXOffset) {
        float offX = layers[WildWorldScene.UP].x + gXOffset;
        while (offX < 0) offX += layers[WildWorldScene.UP].w;

        WildWorldScene.Layer up = layers[WildWorldScene.UP];
        WildWorldScene.Layer down = layers[WildWorldScene.DOWN];

        // 上层：当前 + 左偏移（循环填充）
        drawRect(texUp, offX, up.y, offX + up.w, up.y + up.h);
        drawRect(texUp, offX - up.w, up.y, offX, up.y + up.h);

        // 下层：同样循环
        drawRect(texDown, offX, down.y, offX + down.w, down.y + down.h);
        drawRect(texDown, offX - down.w, down.y, offX, down.y + down.h);

        return offX;
    }

    /**
     * 绘制矩形（核心绘制方法）
     */
    private void drawRect(int texture, float x0, float y0, float x1, float y1) {
        float[] verts = mQuadVerts;
        verts[0] = x0; verts[1] = y0;
        verts[4] = x0; verts[5] = y1;
        verts[8] = x1; verts[9] = y1;
        verts[12] = x1; verts[13] = y0;

        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        GLES30.glEnableVertexAttribArray(mPositionHandle);
        GLES30.glVertexAttribPointer(mPositionHandle, 2, GLES30.GL_FLOAT, false, 16, mQuadBuffer);

        mQuadBuffer.position(2);
        GLES30.glEnableVertexAttribArray(mTexHandle);
        GLES30.glVertexAttribPointer(mTexHandle, 2, GLES30.GL_FLOAT, false, 16, mQuadBuffer);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(mSamplerHandle, 0);
        GLES30.glUniform1f(mAlphaHandle, 1.0f);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);

        GLES30.glDisableVertexAttribArray(mPositionHandle);
        GLES30.glDisableVertexAttribArray(mTexHandle);
    }

    // ---- OpenGL 初始化 ----

    /**
     * 初始化 OpenGL 环境（着色器 / 纹理 / 投影矩阵）
     */
    private void initGL() {
        mGLInitialized = true;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glViewport(0, 0, mWidth, mHeight);

        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        String vs = AssetLoader.readText(mContext, "wildworld/shaders/GLES/wildworld_vs.glsl");
        String fs = AssetLoader.readText(mContext, "wildworld/shaders/GLES/wildworld_fs.glsl");

        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES30.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle = GLES30.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES30.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES30.glGetUniformLocation(mProgram, "uSampler");
        mAlphaHandle = GLES30.glGetUniformLocation(mProgram, "uAlpha");

        mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        // 加载所有纹理
        mTexBgDay = loadTexture("wildworld/drawable/ww_bgday.png");
        mTexBgDay1 = loadTexture("wildworld/drawable/ww_bgday1.jpg");
        mTexBgNight = loadTexture("wildworld/drawable/ww_bgnight.png");
        mTexBgNight1 = loadTexture("wildworld/drawable/ww_bgnight1.jpg");
        mTexLayer5 = loadTexture("wildworld/drawable/ww_layer5.png");
        mTexLayer51 = loadTexture("wildworld/drawable/ww_layer51.png");
        mTexLayer4 = loadTexture("wildworld/drawable/ww_layer4.png");
        mTexLayer41 = loadTexture("wildworld/drawable/ww_layer41.png");
        mTexLayer3 = loadTexture("wildworld/drawable/ww_layer3.png");
        mTexLayer31 = loadTexture("wildworld/drawable/ww_layer31.png");
        mTexLayer2 = loadTexture("wildworld/drawable/ww_layer2.png");
        mTexLayer21 = loadTexture("wildworld/drawable/ww_layer21.png");
        mTexLayer1 = loadTexture("wildworld/drawable/ww_layer1.png");
        mTexLayer11 = loadTexture("wildworld/drawable/ww_layer11.png");
        mTexPterosaur = loadTexture("wildworld/drawable/ww_pterosaur.png");
        mTexDinosaur = loadTexture("wildworld/drawable/ww_dinosaur.png");
        mTexFireball = loadTexture("wildworld/drawable/ww_fireball.png");
    }

    /**
     * 加载纹理资源
     */
    private int loadTexture(String assetPath) {
        Bitmap bmp = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bmp == null) return 0;

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return tex[0];
    }

}
