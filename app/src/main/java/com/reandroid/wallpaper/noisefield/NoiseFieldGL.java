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
import android.opengl.GLES20;
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
    private final float[] mMvp = new float[16];
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
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mDotTexture = 0;

        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mDotProgram != 0) {
            GLES20.glDeleteProgram(mDotProgram);
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

        mScene.updateFrameScale(timeMs);

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawDots();
        drawBackground();

        mScene.updateParticles();
        updateParticleBuffers();
    }

    private void initGLIfNeeded() {
        if (mInitialized || mResources == null) return;

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        String bgVs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_bg_fs.glsl");
        String dotVs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_dot_vs.glsl");
        String dotFs = AssetLoader.readText(mContext, "noisefield/shaders/GLES/noisefield_dot_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        mDotProgram = createProgram(dotVs, dotFs);
        if (mBgProgram == 0 || mDotProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            return;
        }

        mBgPositionLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_position");
        mBgColorLoc = GLES20.glGetAttribLocation(mBgProgram, "ATTRIB_color");

        mDotPositionLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_position");
        mDotSpeedLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_speed");
        mDotAlphaLoc = GLES20.glGetAttribLocation(mDotProgram, "ATTRIB_alpha");
        mDotMvpLoc = GLES20.glGetUniformLocation(mDotProgram, "UNI_MVP");
        mDotScaleLoc = GLES20.glGetUniformLocation(mDotProgram, "UNI_scaleSize");
        mDotTexLoc = GLES20.glGetUniformLocation(mDotProgram, "UNI_Tex0");

        createBackgroundMesh();
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
        mBgPosBuffer = toFloatBuffer(mBgPositions);
        mBgColorBuffer = toFloatBuffer(mBgColors);
    }

    private void drawBackground() {
        GLES20.glUseProgram(mBgProgram);

        GLES20.glEnableVertexAttribArray(mBgPositionLoc);
        GLES20.glEnableVertexAttribArray(mBgColorLoc);

        GLES20.glVertexAttribPointer(mBgPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mBgPosBuffer);
        GLES20.glVertexAttribPointer(mBgColorLoc, 4, GLES20.GL_FLOAT, false, 0, mBgColorBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mBgVertexCount);

        GLES20.glDisableVertexAttribArray(mBgPositionLoc);
        GLES20.glDisableVertexAttribArray(mBgColorLoc);
    }

    private void drawDots() {
        GLES20.glUseProgram(mDotProgram);
        GLES20.glUniformMatrix4fv(mDotMvpLoc, 1, false, mMvp, 0);
        GLES20.glUniform1f(mDotScaleLoc, mScaleSize);
        GLES20.glUniform1i(mDotTexLoc, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDotTexture);

        GLES20.glEnableVertexAttribArray(mDotPositionLoc);
        GLES20.glEnableVertexAttribArray(mDotSpeedLoc);
        GLES20.glEnableVertexAttribArray(mDotAlphaLoc);

        GLES20.glVertexAttribPointer(mDotPositionLoc, 3, GLES20.GL_FLOAT, false, 0, mDotPosBuffer);
        GLES20.glVertexAttribPointer(mDotSpeedLoc, 1, GLES20.GL_FLOAT, false, 0, mDotSpeedBuffer);
        GLES20.glVertexAttribPointer(mDotAlphaLoc, 1, GLES20.GL_FLOAT, false, 0, mDotAlphaBuffer);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, NoiseFieldScene.DOT_COUNT);

        GLES20.glDisableVertexAttribArray(mDotPositionLoc);
        GLES20.glDisableVertexAttribArray(mDotSpeedLoc);
        GLES20.glDisableVertexAttribArray(mDotAlphaLoc);
    }

    private void updateMvp() {
        float[] proj = new float[16];
        float[] tmp = new float[16];

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
        mDotPosBuffer = toFloatBuffer(mScene.getDotPositions());
        mDotSpeedBuffer = toFloatBuffer(mScene.getDotSpeeds());
        mDotAlphaBuffer = toFloatBuffer(mScene.getDotAlpha());
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture: " + assetPath);
            return 0;
        }
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int textureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }

    private FloatBuffer toFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
