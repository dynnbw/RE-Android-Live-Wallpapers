/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reandroid.wallpaper.noisefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.MotionEvent;
import android.app.WallpaperManager;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * NoiseField (泡泡) - RenderScript 完整移植到 OpenGL ES 2.0
 */
public class NoiseFieldGL extends GLESScene {
    private static final String TAG = "NoiseFieldGL";

    private final Context mContext;
    private final NoiseFieldScene mScene;

    private int mBgProgram;
    private int mDotProgram;

    private int mBgPositionLoc;
    private int mBgColorLoc;

    private int mDotPositionLoc;
    private int mDotSpeedLoc;
    private int mDotAlphaLoc;
    private int mDotMvpLoc;
    private int mDotScaleLoc;
    private int mDotTexLoc;

    private int mDotTexture;

    private float mScaleSize = 1.0f;
    private float mSizeMultiplier = 1.0f;
    private final float[] mMvp = new float[16];
    // updateMvp 每帧调一次，投影与临时矩阵都用字段复用
    private final float[] mProjMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];
    private boolean mInitialized;

    private float[] mBgPositions;
    private float[] mBgColors;
    private int mBgVertexCount;
    private FloatBuffer mBgPosBuffer;
    private FloatBuffer mBgColorBuffer;

    private FloatBuffer mDotPosBuffer;
    private FloatBuffer mDotSpeedBuffer;
    private FloatBuffer mDotAlphaBuffer;

    public NoiseFieldGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new NoiseFieldScene(width, height);
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
        mSizeMultiplier = prefs.getInt("noisefield_size", 100) / 100.0f;
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        mScaleSize = mResources.getDisplayMetrics().densityDpi / 240.0f;
    }

    @Override
    public void start() {
        mScene.start();
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
        mScene.onTouchEvent(ev);
    }

    @Override
    public void release() {
        int[] tex = new int[] { mDotTexture };
        GLES30.glDeleteTextures(tex.length, tex, 0);
        mDotTexture = 0;

        if (mBgProgram != 0) {
            GLES30.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mDotProgram != 0) {
            GLES30.glDeleteProgram(mDotProgram);
            mDotProgram = 0;
        }
        mInitialized = false;
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if (WallpaperManager.COMMAND_TAP.equals(action)
                || WallpaperManager.COMMAND_SECONDARY_TAP.equals(action)) {
            mScene.onCommandTouch(x, y);
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
        updateMvp();
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        if (mScene.consumeParamsDirty()) {
            mScene.allocateArrays();
            mScene.positionParticles();
        }

        mScene.updateFrameScale(timeMs);

        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        drawDots();
        drawBackground();

        mScene.updateParticles();
        updateParticleBuffers();
    }

    /** 编译失败过就不再重试：否则每帧都要重读 assets、重编 4 个着色器。 */
    private boolean mGLFailed;

    private void initGLIfNeeded() {
        if (mInitialized || mGLFailed || mResources == null) return;

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);

        String bgVs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_bg_fs.glsl");
        String dotVs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_dot_vs.glsl");
        String dotFs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_dot_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        mDotProgram = createProgram(dotVs, dotFs);
        if (mBgProgram == 0 || mDotProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            mGLFailed = true;   // 记下来，别每帧重试
            return;
        }

        mBgPositionLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_position");
        mBgColorLoc = GLES30.glGetAttribLocation(mBgProgram, "ATTRIB_color");

        mDotPositionLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_position");
        mDotSpeedLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_speed");
        mDotAlphaLoc = GLES30.glGetAttribLocation(mDotProgram, "ATTRIB_alpha");
        mDotMvpLoc = GLES30.glGetUniformLocation(mDotProgram, "UNI_MVP");
        mDotScaleLoc = GLES30.glGetUniformLocation(mDotProgram, "UNI_scaleSize");
        mDotTexLoc = GLES30.glGetUniformLocation(mDotProgram, "UNI_Tex0");

        createBackgroundMesh();
        if (mScene.consumeParamsDirty()) {
            mScene.allocateArrays();
        }
        mScene.positionParticles();
        updateParticleBuffers();
        updateMvp();
        mDotTexture = loadTexture("noisefield/drawable/noisefield_dot.png");

        mInitialized = true;
    }

    private void createBackgroundMesh() {
        float[] mesh = AssetLoader.readFloatArray(mContext, "noisefield/data/noisefield_bg_mesh.csv");
        int count = mesh.length / 5;
        mBgVertexCount = count;
        mBgPositions = new float[count * 3];
        mBgColors = new float[count * 4];
        for (int i = 0; i < count; i++) {
            int src = i * 5;
            int v = i * 3;
            int c = i * 4;
            mBgPositions[v] = mesh[src];
            mBgPositions[v + 1] = mesh[src + 1];
            mBgPositions[v + 2] = 0.0f;
            mBgColors[c] = mesh[src + 2];
            mBgColors[c + 1] = mesh[src + 3];
            mBgColors[c + 2] = mesh[src + 4];
            mBgColors[c + 3] = 1.0f;
        }
        mBgPosBuffer = createFloatBuffer(mBgPositions);
        mBgColorBuffer = createFloatBuffer(mBgColors);
    }

    private void drawBackground() {
        GLES30.glUseProgram(mBgProgram);

        GLES30.glEnableVertexAttribArray(mBgPositionLoc);
        GLES30.glEnableVertexAttribArray(mBgColorLoc);

        GLES30.glVertexAttribPointer(mBgPositionLoc, 3, GLES30.GL_FLOAT, false, 0, mBgPosBuffer);
        GLES30.glVertexAttribPointer(mBgColorLoc, 4, GLES30.GL_FLOAT, false, 0, mBgColorBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mBgVertexCount);

        GLES30.glDisableVertexAttribArray(mBgPositionLoc);
        GLES30.glDisableVertexAttribArray(mBgColorLoc);
    }

    private void drawDots() {
        if (mDotTexture == 0) return;   // 纹理没就绪时绑 0 会采样到未定义内容
        GLES30.glUseProgram(mDotProgram);
        GLES30.glUniformMatrix4fv(mDotMvpLoc, 1, false, mMvp, 0);
        GLES30.glUniform1f(mDotScaleLoc, mScaleSize * mSizeMultiplier);
        GLES30.glUniform1i(mDotTexLoc, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mDotTexture);

        GLES30.glEnableVertexAttribArray(mDotPositionLoc);
        GLES30.glEnableVertexAttribArray(mDotSpeedLoc);
        GLES30.glEnableVertexAttribArray(mDotAlphaLoc);

        GLES30.glVertexAttribPointer(mDotPositionLoc, 3, GLES30.GL_FLOAT, false, 0, mDotPosBuffer);
        GLES30.glVertexAttribPointer(mDotSpeedLoc, 1, GLES30.GL_FLOAT, false, 0, mDotSpeedBuffer);
        GLES30.glVertexAttribPointer(mDotAlphaLoc, 1, GLES30.GL_FLOAT, false, 0, mDotAlphaBuffer);

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, mScene.getDotCount());

        GLES30.glDisableVertexAttribArray(mDotPositionLoc);
        GLES30.glDisableVertexAttribArray(mDotSpeedLoc);
        GLES30.glDisableVertexAttribArray(mDotAlphaLoc);
    }

    private void updateMvp() {
        float[] proj = mProjMatrix;
        float[] tmp = mTempMatrix;

        if (mWidth > mHeight) {
            float aspect = (float) mWidth / (float) mHeight;
            Matrix.frustumM(proj, 0, -aspect, aspect, -1f, 1f, 1f, 100f);
        } else {
            float aspect = (float) mHeight / (float) mWidth;
            Matrix.frustumM(proj, 0, -0.5f, 1f, -aspect, aspect, 1f, 100f);
        }

        Matrix.setRotateM(tmp, 0, 180f, 0f, 1f, 0f);
        Matrix.multiplyMM(mMvp, 0, proj, 0, tmp, 0);

        Matrix.setIdentityM(tmp, 0);
        Matrix.scaleM(tmp, 0, -1f, 1f, 1f);
        Matrix.multiplyMM(proj, 0, mMvp, 0, tmp, 0);

        Matrix.setIdentityM(tmp, 0);
        Matrix.translateM(tmp, 0, 0f, 0f, 1f);
        Matrix.multiplyMM(mMvp, 0, proj, 0, tmp, 0);
    }

    private void updateParticleBuffers() {
        mDotPosBuffer = updateFloatBuffer(mDotPosBuffer, mScene.getDotPositions());
        mDotSpeedBuffer = updateFloatBuffer(mDotSpeedBuffer, mScene.getDotSpeeds());
        mDotAlphaBuffer = updateFloatBuffer(mDotAlphaBuffer, mScene.getDotAlpha());
    }

    private FloatBuffer updateFloatBuffer(FloatBuffer buf, float[] data) {
        if (buf == null || buf.capacity() < data.length) {
            return createFloatBuffer(data);
        }
        buf.position(0);
        buf.put(data);
        buf.position(0);
        return buf;
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return 0;
        }
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        int textureId = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }



}
