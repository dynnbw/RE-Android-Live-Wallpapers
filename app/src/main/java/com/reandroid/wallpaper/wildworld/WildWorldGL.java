package com.reandroid.wallpaper.wildworld;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

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

    // ---- 构造方法 ----

    /**
     * @param width  初始宽度
     * @param height 初始高度
     */
    public WildWorldGL(int width, int height) {
        super(width, height);
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

        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.initState(width, height);
        if (mGLInitialized) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
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
            Resources res = getResources();
            if (res == null) return;
            initGL(res);
        }

        // 更新场景逻辑（时间步长、触摸处理、动画状态）
        mScene.updateFrame(timeMs);

        // 执行 OpenGL 绘制
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjectionMatrix, 0);
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
        float[] verts = new float[] {
                x0, y0, 0.0f, 0.0f,
                x0, y1, 0.0f, 1.0f,
                x1, y1, 1.0f, 1.0f,
                x1, y0, 1.0f, 0.0f
        };

        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexHandle);
        GLES20.glVertexAttribPointer(mTexHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);
        GLES20.glUniform1f(mAlphaHandle, 1.0f);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexHandle);
    }

    // ---- OpenGL 初始化 ----

    /**
     * 初始化 OpenGL 环境（着色器 / 纹理 / 投影矩阵）
     */
    private void initGL(Resources res) {
        mGLInitialized = true;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, mWidth, mHeight);

        Matrix.orthoM(mProjectionMatrix, 0, 0, mWidth, mHeight, 0, -1.0f, 1.0f);

        String vs = RawResourceLoader.readRawText(res, R.raw.wildworld_vs);
        String fs = RawResourceLoader.readRawText(res, R.raw.wildworld_fs);

        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");

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
     * 加载纹理资源
     */
    private int loadTexture(Resources res, int resId) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        Bitmap bmp = BitmapFactory.decodeResource(res, resId, opts);
        if (bmp == null) return 0;

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
    }

    /**
     * 创建着色器程序
     */
    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        return program;
    }

    /**
     * 加载单个着色器
     */
    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
