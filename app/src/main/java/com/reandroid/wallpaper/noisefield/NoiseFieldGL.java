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

import com.reandroid.utils.MathUtils;
import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * NoiseField (泡泡) - RenderScript 完整移植到 OpenGL ES 2.0
 */
public class NoiseFieldGL extends GLESScene {
    private static final String TAG = "NoiseFieldGL";


    private static final int DOT_COUNT = 83;
    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;

    private final Context mContext;
    private final Random mRandom = new Random();

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

    private final float[] mDotPositions = new float[DOT_COUNT * 3];
    private final float[] mDotSpeeds = new float[DOT_COUNT];
    private final float[] mDotAlpha = new float[DOT_COUNT];
    private final float[] mDotAlphaStart = new float[DOT_COUNT];
    private final float[] mDotWander = new float[DOT_COUNT];
    private final int[] mDotLife = new int[DOT_COUNT];
    private final int[] mDotDeath = new int[DOT_COUNT];

    private FloatBuffer mDotPosBuffer;
    private FloatBuffer mDotSpeedBuffer;
    private FloatBuffer mDotAlphaBuffer;

    private final int[] p = new int[B + B + 2];
    private final float[][] g2 = new float[B + B + 2][2];

    private boolean mTouchDown = false;
    private float mTouchInfluence = 0f;
    private float mTouchX = 0f;
    private float mTouchY = 0f;
    private long mLastFrameTimeMs = 0L;
    private float mTouchFrameScale = 1f;
    private static final float BASE_FRAME_MS = 35f;

    public NoiseFieldGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        mScaleSize = mResources.getDisplayMetrics().densityDpi / 240.0f;
    }

    @Override
    public void start() {
        initNoise();
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP || act == MotionEvent.ACTION_CANCEL) {
            if (mTouchDown) {
                mTouchDown = false;
            }
            return;
        } else if (act == MotionEvent.ACTION_DOWN
                || act == MotionEvent.ACTION_MOVE
                || act == MotionEvent.ACTION_POINTER_DOWN) {
            if (!mTouchDown) {
                mTouchDown = true;
            }
            if (ev.getPointerCount() > 0) {
                touch(ev.getX(0), ev.getY(0));
            }
        }
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
            touch(x, y);
            mTouchDown = false;
            mTouchInfluence = 1.0f;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateMvp();
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        updateFrameScale(timeMs);

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawDots();
        drawBackground();

        updateParticles();
    }

    private void updateFrameScale(long timeMs) {
        if (mLastFrameTimeMs == 0L) {
            mLastFrameTimeMs = timeMs;
            mTouchFrameScale = 1f;
            return;
        }
        long delta = timeMs - mLastFrameTimeMs;
        if (delta < 1) delta = 1;
        float scale = delta / BASE_FRAME_MS;
        if (scale < 0.25f) scale = 0.25f;
        if (scale > 3.0f) scale = 3.0f;
        mTouchFrameScale = scale;
        mLastFrameTimeMs = timeMs;
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
        positionParticles();
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

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DOT_COUNT);

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

    private void positionParticles() {
        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;
            mDotPositions[idx] = rand(-1.0f, 1.0f);
            mDotPositions[idx + 1] = rand(-1.0f, 1.0f);
            mDotPositions[idx + 2] = 0.0f;
            mDotSpeeds[i] = rand(0.0002f, 0.02f);
            mDotWander[i] = rand(0.50f, 1.5f);
            mDotDeath[i] = 0;
            mDotLife[i] = randInt(300, 800);
            mDotAlphaStart[i] = rand(0.01f, 1.0f);
            mDotAlpha[i] = mDotAlphaStart[i];
        }
    }

    private void updateParticles() {
        for (int i = 0; i < DOT_COUNT; i++) {
            int idx = i * 3;

            if (mDotLife[i] < 0 || mDotPositions[idx] < -1.2f || mDotPositions[idx] > 1.2f
                    || mDotPositions[idx + 1] < -1.7f || mDotPositions[idx + 1] > 1.7f) {
                mDotPositions[idx] = rand(-1.0f, 1.0f);
                mDotPositions[idx + 1] = rand(-1.0f, 1.0f);
                mDotSpeeds[i] = rand(0.0002f, 0.02f);
                mDotWander[i] = rand(0.50f, 1.5f);
                mDotDeath[i] = 0;
                mDotLife[i] = randInt(300, 800);
                mDotAlphaStart[i] = rand(0.01f, 1.0f);
                mDotAlpha[i] = mDotAlphaStart[i];
            }

            float touchDist = (float) Math.sqrt(Math.pow(mTouchX - mDotPositions[idx], 2)
                    + Math.pow(mTouchY - mDotPositions[idx + 1], 2));

            float noiseval = noisef2(mDotPositions[idx], mDotPositions[idx + 1]);
            if (mTouchDown || mTouchInfluence > 0.0f) {
                if (mTouchDown) {
                    mTouchInfluence = 1.0f;
                }
                float rads = (float) Math.atan2(mTouchX - mDotPositions[idx] + noiseval,
                        mTouchY - mDotPositions[idx + 1] + noiseval);
                float speed;
                if (touchDist != 0) {
                    speed = ((0.25f + (noiseval * mDotSpeeds[i] + 0.01f)) / touchDist * 0.3f);
                    speed = speed * mTouchInfluence;
                } else {
                    speed = 0.3f;
                }
                mDotPositions[idx] += Math.cos(rads) * speed * 0.2f * mTouchFrameScale;
                mDotPositions[idx + 1] += Math.sin(rads) * speed * 0.2f * mTouchFrameScale;
            }

            float angle = 360f * noiseval * mDotWander[i];
            float speed = noiseval * mDotSpeeds[i] + 0.01f;
            float rads = (float) (angle * Math.PI / 180.0);

            mDotPositions[idx] += Math.cos(rads) * speed * 0.33f * mTouchFrameScale;
            mDotPositions[idx + 1] += Math.sin(rads) * speed * 0.33f * mTouchFrameScale;

            mDotLife[i]--;
            mDotDeath[i]++;

            float dist = (float) Math.sqrt(mDotPositions[idx] * mDotPositions[idx]
                    + mDotPositions[idx + 1] * mDotPositions[idx + 1]);
            if (dist < 0.95f) {
                dist = 0;
                mDotAlphaStart[i] *= (1 - dist);
            } else {
                dist = dist - 0.95f;
                if (mDotAlphaStart[i] < 1.0f) {
                    mDotAlphaStart[i] += 0.01f;
                    mDotAlphaStart[i] *= (1 - dist);
                }
            }

            if (mDotDeath[i] < 101) {
                mDotAlpha[i] = (mDotAlphaStart[i]) * (mDotDeath[i]) / 100.0f;
            } else if (mDotLife[i] < 101) {
                mDotAlpha[i] = mDotAlpha[i] * mDotLife[i] / 100.0f;
            } else {
                mDotAlpha[i] = mDotAlphaStart[i];
            }
        }

        if (mTouchInfluence > 0) {
            mTouchInfluence -= 0.01f * mTouchFrameScale;
            if (mTouchInfluence < 0f) mTouchInfluence = 0f;
        }

        updateParticleBuffers();
    }

    private void updateParticleBuffers() {
        mDotPosBuffer = toFloatBuffer(mDotPositions);
        mDotSpeedBuffer = toFloatBuffer(mDotSpeeds);
        mDotAlphaBuffer = toFloatBuffer(mDotAlpha);
    }

    private void touch(float x, float y) {
        boolean landscape = mWidth > mHeight;
        float wRatio;
        float hRatio;
        if (!landscape) {
            wRatio = 1.0f;
            hRatio = (float) mHeight / (float) mWidth;
        } else {
            hRatio = 1.0f;
            wRatio = (float) mWidth / (float) mHeight;
        }

        mTouchInfluence = 1.0f;
        mTouchX = x / mWidth * wRatio * 2f - wRatio;
        mTouchY = -(y / mHeight * hRatio * 2f - hRatio);
    }

    private void initNoise() {
        for (int i = 0; i < B; i++) {
            p[i] = i;
            g2[i][0] = rand(-1f, 1f);
            g2[i][1] = rand(-1f, 1f);
            normalize2(g2[i]);
        }

        for (int i = B - 1; i >= 0; i--) {
            int k = p[i];
            int j = mRandom.nextInt(B);
            p[i] = p[j];
            p[j] = k;
        }

        for (int i = 0; i < B + 2; i++) {
            p[B + i] = p[i];
            g2[B + i][0] = g2[i][0];
            g2[B + i][1] = g2[i][1];
        }
    }

    private float noisef2(float x, float y) {
        int bx0, bx1, by0, by1, b00, b10, b01, b11;
        float rx0, rx1, ry0, ry1, sx, sy, a, b, t, u, v;
        float[] q;

        t = x + N;
        bx0 = ((int) t) & BM;
        bx1 = (bx0 + 1) & BM;
        rx0 = t - (int) t;
        rx1 = rx0 - 1.0f;

        t = y + N;
        by0 = ((int) t) & BM;
        by1 = (by0 + 1) & BM;
        ry0 = t - (int) t;
        ry1 = ry0 - 1.0f;

        int i = p[bx0];
        int j = p[bx1];

        b00 = p[i + by0];
        b10 = p[j + by0];
        b01 = p[i + by1];
        b11 = p[j + by1];

        sx = noiseSCurve(rx0);
        sy = noiseSCurve(ry0);

        q = g2[b00];
        u = rx0 * q[0] + ry0 * q[1];
        q = g2[b10];
        v = rx1 * q[0] + ry0 * q[1];
        a = MathUtils.mix(u, v, sx);

        q = g2[b01];
        u = rx0 * q[0] + ry1 * q[1];
        q = g2[b11];
        v = rx1 * q[0] + ry1 * q[1];
        b = MathUtils.mix(u, v, sx);

        return 1.5f * MathUtils.mix(a, b, sy);
    }

    private float noiseSCurve(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private void normalize2(float[] v) {
        float s = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        v[0] = v[0] / s;
        v[1] = v[1] / s;
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

    private float rand(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    private int randInt(int min, int max) {
        return min + mRandom.nextInt(max - min + 1);
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
