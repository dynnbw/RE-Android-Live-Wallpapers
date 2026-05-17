package com.reandroid.wallpaper.wildworld;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * WildWorld OpenGL 渲染 — 100% 对齐 RenderScript wildworld.rs 的视觉输出。
 * 正交投影 (0, w, h, 0)，单纹理着色器，按 Z 序绘制。
 */
public class WildWorldGL extends GLESScene {

    // ---- Scene ----
    private final WildWorldScene mScene;

    // ---- GL state ----
    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mSamplerHandle;
    private final float[] mProjMatrix = new float[16];
    private FloatBuffer mQuadBuffer;
    private boolean mGlReady;

    // ---- Textures ----
    private int mTexBgDay, mTexBgDay1;
    private int mTexBgNight, mTexBgNight1;
    private int mTexLayer5, mTexLayer51;
    private int mTexLayer4, mTexLayer41;
    private int mTexLayer3, mTexLayer31;
    private int mTexLayer2, mTexLayer21;
    private int mTexLayer1, mTexLayer11;
    private int mTexPterosaur;
    private int mTexDinosaur;
    private int mTexFireball;

    // ---- Touch state ----
    private boolean mTouchPending;
    private float mTouchX, mTouchY;

    // ---- Scroll offset ----
    private float mScrollPixels;

    public WildWorldGL(int width, int height) {
        super(width, height);
        mScene = new WildWorldScene();
        mScene.storeScreenSize(width, height);
    }

    @Override
    protected void onCreate() {
        mScene.init();
        mScene.setDensity(0);
        if (isPreview()) mScene.mXOffset = mScene.mScreenWidth / 2;
    }

    @Override
    public void release() {
        int[] tex = {mTexBgDay, mTexBgDay1, mTexBgNight, mTexBgNight1,
                mTexLayer5, mTexLayer51, mTexLayer4, mTexLayer41,
                mTexLayer3, mTexLayer31, mTexLayer2, mTexLayer21,
                mTexLayer1, mTexLayer11, mTexPterosaur, mTexDinosaur, mTexFireball};
        GLES20.glDeleteTextures(tex.length, tex, 0);
        if (mProgram != 0) { GLES20.glDeleteProgram(mProgram); mProgram = 0; }
        mGlReady = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.storeScreenSize(width, height);
        mScene.setDensity(mScene.mDensity == 0 ? 0 : mScene.mDensity);
        if (mGlReady) {
            GLES20.glViewport(0, 0, mWidth, mHeight);
            Matrix.orthoM(mProjMatrix, 0, 0, width, height, 0, -1f, 1f);
        }
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScrollPixels = xPixels;
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if ("android.wallpaper.tap".equals(action)) {
            mTouchX = x;
            mTouchY = y;
            mTouchPending = true;
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mGlReady) {
            if (getResources() == null) return;
            initGL(getResources());
        }

        // RS main(): gXOffset = State->xOffset - screenWidth / 2
        mScene.mXOffset = (int)(mScrollPixels - mScene.mScreenWidth / 2f);

        if (mTouchPending) {
            mTouchPending = false;
            mScene.onTouch(mTouchX, mTouchY);
        }

        // RS timing: gCurTime = uptimeMillis(); gDT = (gCurTime - gOldTime) / 1000; minf(gDT, MIN_DT)
        long now = SystemClock.uptimeMillis();
        mScene.mCurTime = now;
        if (mScene.mOldTime == 0) mScene.mOldTime = mScene.mCurTime;
        float dt = (mScene.mCurTime - mScene.mOldTime) / 1000f;
        if (dt > WildWorldScene.MIN_DT) dt = WildWorldScene.MIN_DT;
        mScene.mOldTime = mScene.mCurTime;
        mScene.mDT = dt;

        float frameScale = dt * 25f; // RS targets 25fps (main returns 25)
        mScene.update(frameScale);

        // RS draw()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawDayAndNightLayer();
        drawLayers();
    }

    // ---- Init GL ----

    private void initGL(Resources res) {
        String vs = RawResourceLoader.readRawText(res, R.raw.wildworld_vs);
        String fs = RawResourceLoader.readRawText(res, R.raw.wildworld_fs);
        mProgram = createProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");

        mQuadBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        Matrix.orthoM(mProjMatrix, 0, 0, mWidth, mHeight, 0, -1f, 1f);

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

        mGlReady = true;
    }

    // ---- draw (exact RS draw functions, same order) ----

    private void drawDayAndNightLayer() {
        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjMatrix, 0);

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

    private void drawLayers() {
        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mProjMatrix, 0);

        // Fireballs
        if (mScene.mFireballsShow != 0) {
            for (int i = 0; i < WildWorldScene.FIREBALL_COUNT; i++) {
                WildWorldScene.Fireball f = mScene.mFireballs[i];
                if (mScene.mCurTime > f.startTime && f.steps > 0) {
                    float offX = f.x + mScene.mXOffset;
                    drawRect(mTexFireball, offX, f.y, offX + f.w, f.y + f.h);
                }
            }
        }

        // VCN Layer
        float offX = mScene.mVcnLayer[WildWorldScene.UP].x + mScene.mXOffset;
        while (offX < 0) offX += mScene.mVcnLayer[WildWorldScene.UP].w;
        drawRect(mTexLayer5, offX, mScene.mVcnLayer[WildWorldScene.UP].y,
                offX + mScene.mVcnLayer[WildWorldScene.UP].w, mScene.mVcnLayer[WildWorldScene.UP].y + mScene.mVcnLayer[WildWorldScene.UP].h);
        drawRect(mTexLayer5, offX - mScene.mVcnLayer[WildWorldScene.UP].w, mScene.mVcnLayer[WildWorldScene.UP].y,
                offX, mScene.mVcnLayer[WildWorldScene.UP].y + mScene.mVcnLayer[WildWorldScene.UP].h);
        drawLayer(mTexLayer51, mScene.mVcnLayer[WildWorldScene.DOWN]);

        // Pterosaur
        drawPterosaur();

        // Layer 4
        offX = mScene.mLayer4[WildWorldScene.UP].x + mScene.mXOffset;
        while (offX < 0) offX += mScene.mLayer4[WildWorldScene.UP].w;
        drawRect(mTexLayer4, offX, mScene.mLayer4[WildWorldScene.UP].y,
                offX + mScene.mLayer4[WildWorldScene.UP].w, mScene.mLayer4[WildWorldScene.UP].y + mScene.mLayer4[WildWorldScene.UP].h);
        drawRect(mTexLayer4, offX - mScene.mLayer4[WildWorldScene.UP].w, mScene.mLayer4[WildWorldScene.UP].y,
                offX, mScene.mLayer4[WildWorldScene.UP].y + mScene.mLayer4[WildWorldScene.UP].h);
        drawLayer(mTexLayer41, mScene.mLayer4[WildWorldScene.DOWN]);

        // Dinosaur 1 (UP)
        drawDinosaur(WildWorldScene.UP);

        // Layer 3
        offX = mScene.mLayer3[WildWorldScene.UP].x + mScene.mXOffset;
        while (offX < 0) offX += mScene.mLayer3[WildWorldScene.UP].w;
        drawRect(mTexLayer3, offX, mScene.mLayer3[WildWorldScene.UP].y,
                offX + mScene.mLayer3[WildWorldScene.UP].w, mScene.mLayer3[WildWorldScene.UP].y + mScene.mLayer3[WildWorldScene.UP].h);
        drawRect(mTexLayer3, offX - mScene.mLayer3[WildWorldScene.UP].w, mScene.mLayer3[WildWorldScene.UP].y,
                offX, mScene.mLayer3[WildWorldScene.UP].y + mScene.mLayer3[WildWorldScene.UP].h);
        drawLayer(mTexLayer31, mScene.mLayer3[WildWorldScene.DOWN]);

        // Dinosaur 2 (DOWN)
        drawDinosaur(WildWorldScene.DOWN);

        // Layer 2
        offX = mScene.mLayer2[WildWorldScene.UP].x + mScene.mXOffset;
        while (offX < 0) offX += mScene.mLayer2[WildWorldScene.UP].w;
        drawRect(mTexLayer2, offX, mScene.mLayer2[WildWorldScene.UP].y,
                offX + mScene.mLayer2[WildWorldScene.UP].w, mScene.mLayer2[WildWorldScene.UP].y + mScene.mLayer2[WildWorldScene.UP].h);
        drawRect(mTexLayer2, offX - mScene.mLayer2[WildWorldScene.UP].w, mScene.mLayer2[WildWorldScene.UP].y,
                offX, mScene.mLayer2[WildWorldScene.UP].y + mScene.mLayer2[WildWorldScene.UP].h);
        drawLayer(mTexLayer21, mScene.mLayer2[WildWorldScene.DOWN]);

        // Layer 1
        offX = mScene.mLayer1[WildWorldScene.UP].x + mScene.mXOffset;
        while (offX < 0) offX += mScene.mLayer1[WildWorldScene.UP].w;
        drawRect(mTexLayer1, offX, mScene.mLayer1[WildWorldScene.UP].y,
                offX + mScene.mLayer1[WildWorldScene.UP].w, mScene.mLayer1[WildWorldScene.UP].y + mScene.mLayer1[WildWorldScene.UP].h);
        drawRect(mTexLayer1, offX - mScene.mLayer1[WildWorldScene.UP].w, mScene.mLayer1[WildWorldScene.UP].y,
                offX, mScene.mLayer1[WildWorldScene.UP].y + mScene.mLayer1[WildWorldScene.UP].h);
        drawLayer(mTexLayer11, mScene.mLayer1[WildWorldScene.DOWN]);
    }

    private void drawPterosaur() {
        WildWorldScene.Pterosaur p = mScene.mPterosaur;
        if (p.alive == 0) return;
        float offX = p.x + mScene.mXOffset;
        if (offX + p.w > 0 && offX < mScene.mScreenWidth) {
            drawRect(mTexPterosaur, offX, p.y, offX + p.w, p.y + p.h);
        } else if (p.x + mScene.mXOffset > mScene.mScreenWidth) {
            p.alive = 0;
        }
    }

    private void drawDinosaur(int ud) {
        WildWorldScene.Dinosaur d = mScene.mDinosaur[ud];
        if (d.alive == 0) return;
        float offX = d.x + mScene.mXOffset;
        if (offX + d.w > 0 && offX < mScene.mScreenWidth) {
            drawRect(mTexDinosaur, offX, d.y - d.stepY, offX + d.w, d.y + d.h - d.stepY);
        } else if (d.x + d.w + mScene.mXOffset < 0) {
            d.alive = 0;
        }
    }

    private void drawLayer(int tex, WildWorldScene.Layer l) {
        drawRect(tex, l.x, l.y, l.x + l.w, l.y + l.h);
    }

    // ---- Core drawRect (matches RS drawRect) ----

    private void drawRect(int texture, float x0, float y0, float x1, float y1) {
        float[] verts = {x0, y0, 0f, 0f, x0, y1, 0f, 1f, x1, y1, 1f, 1f, x1, y0, 1f, 0f};
        mQuadBuffer.clear();
        mQuadBuffer.put(verts).position(0);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);
        mQuadBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, mQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    // ---- Utilities ----

    private int loadTexture(Resources res, int resId) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        Bitmap bmp = BitmapFactory.decodeResource(res, resId, opts);
        if (bmp == null) return 0;
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return tex[0];
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int loadShader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        return s;
    }
}
