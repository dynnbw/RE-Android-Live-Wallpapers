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

package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.utils.MathUtils;
import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.settings.WallpaperSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class FallGL extends GLESScene {
    private static final String TAG = "FallGL";
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;

    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mAlphaHandle;
    private int mSamplerHandle;
    private int mColorHandle;
    private int[] mLeafTextures;
    private int mRiverbedTexture;
    private FloatBuffer mWaterMeshVertexBuffer;
    private FloatBuffer mWaterMeshTexCoordBuffer;
    private FloatBuffer mLeafQuadVertexBuffer;
    private ShortBuffer mWaterIndexBuffer;
    private final float[] mModelMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];
    private boolean mGLInitialized = false;
    private int mFrameCount = 0;
    private final FallScene mScene;
    private final Context mContext;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled = false;
    private long mLastPerfSyncMs = 0L;
    private long mDiagFrameCount = 0L;
    private long mDiagAccumulatedMs = 0L;
    private long mDiagMaxMs = 0L;
    private static final float TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX = 42.0f;
    private float mLastTouchTriggerX = -1.0f;
    private float mLastTouchTriggerY = -1.0f;

    public FallGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new FallScene(width, height);
        Log.d(TAG, "FallGL创建: " + width + "x" + height);
    }

    /** Plugin path: set host-provided prefs for settings isolation. */
    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate()调用, mResources=" + mResources + ", 宽度=" + mWidth + ", 高度=" + mHeight);
    }

    @Override
    public void release() {
        if (mLeafTextures != null && mLeafTextures.length > 0) {
            GLES20.glDeleteTextures(mLeafTextures.length, mLeafTextures, 0);
            mLeafTextures = null;
        }

        if (mRiverbedTexture != 0) {
            int[] tex = new int[] { mRiverbedTexture };
            GLES20.glDeleteTextures(1, tex, 0);
            mRiverbedTexture = 0;
        }

        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        mWaterMeshVertexBuffer = null;
        mWaterMeshTexCoordBuffer = null;
        mGLInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void start() {
        if (mGLInitialized) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        mGLInitialized = true;
        mScene.ensureResources();
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glViewport(0, 0, mWidth, mHeight);

        createProgram();

        try {
            mLeafTextures = loadLeafTextures();
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载枫叶纹理失败", e);
            mLeafTextures = createPlaceholderLeafTextures();
        }
        // ensureResources() already set mLeafTextureCount from prefs — don't override
        try {
            mRiverbedTexture = loadTexture("fall/drawable/pond.jpg");
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载河床纹理失败", e);
            mRiverbedTexture = createPlaceholderTexture(256, 256, Color.parseColor("#4A6FA5"));
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z) {
        if (action != null && action.toLowerCase().contains("tap")) {
            mScene.addDrop(x, y);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) {
            return;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mScene.addDrop((int) x, (int) y);
                mLastTouchTriggerX = x;
                mLastTouchTriggerY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mLastTouchTriggerX < 0.0f || mLastTouchTriggerY < 0.0f) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                    break;
                }
                float dx = x - mLastTouchTriggerX;
                float dy = y - mLastTouchTriggerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance >= TOUCH_TRIGGER_DISTANCE_THRESHOLD_PX) {
                    mScene.addDrop((int) x, (int) y);
                    mLastTouchTriggerX = x;
                    mLastTouchTriggerY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mLastTouchTriggerX = -1.0f;
                mLastTouchTriggerY = -1.0f;
                break;
            default:
                break;
        }
    }

    public void addDrop(int x, int y) {
        mScene.addDrop(x, y);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mFrameCount == 0) {
            Log.d(TAG, "首次drawFrame()开始");
        }
        mFrameCount++;

        long frameStart = SystemClock.uptimeMillis();
        syncPerfSettingsIfNeeded(frameStart);

        if (!mGLInitialized) {
            if (mResources != null) {
                start();
            } else {
                return;
            }
        }

        if (mProgram == 0) {
            Log.w(TAG, "程序未初始化");
            return;
        }

        mScene.update(timeMs);
        FallScene.SceneData sceneData = mScene.getSceneData();
        syncWaterMeshBuffers(sceneData);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);

        drawWaterQuad(sceneData);
        for (FallScene.Leaf leaf : sceneData.getLeaves()) {
            drawLeaf(leaf, sceneData);
        }

        int glError = GLES20.glGetError();
        if (glError != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "drawFrame中GL错误: " + glError);
        }

        long frameCost = SystemClock.uptimeMillis() - frameStart;
        recordFrameCost(frameCost);
    }

    private void syncWaterMeshBuffers(FallScene.SceneData sceneData) {
        boolean rebuildBuffers = mScene.consumeMeshBufferRebuildRequested()
                || mWaterMeshVertexBuffer == null
                || mWaterMeshTexCoordBuffer == null
                || mWaterMeshVertexBuffer.capacity() != sceneData.getWaterMeshVertices().length
                || mWaterMeshTexCoordBuffer.capacity() != sceneData.getWaterMeshTexCoords().length;

        if (rebuildBuffers) {
            mWaterMeshVertexBuffer = createFloatBuffer(sceneData.getWaterMeshVertices());
            mWaterMeshTexCoordBuffer = createFloatBuffer(sceneData.getWaterMeshTexCoords());
            return;
        }

        if (mScene.consumeWaterTexCoordsDirty()) {
            mWaterMeshTexCoordBuffer.position(0);
            mWaterMeshTexCoordBuffer.put(sceneData.getWaterMeshTexCoords());
            mWaterMeshTexCoordBuffer.position(0);
        }
    }

    private void drawWaterQuad(FallScene.SceneData sceneData) {
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.multiplyMM(mViewMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mViewMatrix, 0);
        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform1f(mAlphaHandle, 1.0f);
        GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        mWaterMeshVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 12, mWaterMeshVertexBuffer);

        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        mWaterMeshTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mWaterMeshTexCoordBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mRiverbedTexture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        int indexCount = sceneData.getWaterMeshIndexCount();
        if (indexCount > 0) {
            if (mWaterIndexBuffer == null || mWaterIndexBuffer.capacity() != sceneData.getWaterMeshIndices().length) {
                mWaterIndexBuffer = ByteBuffer.allocateDirect(sceneData.getWaterMeshIndices().length * 2)
                        .order(ByteOrder.nativeOrder()).asShortBuffer();
                mWaterIndexBuffer.put(sceneData.getWaterMeshIndices()).position(0);
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, mWaterIndexBuffer);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void drawLeaf(FallScene.Leaf leaf, FallScene.SceneData sceneData) {
        if (mLeafTextures == null || mLeafTextures.length == 0) {
            return;
        }

        if (leaf.altitude > 0.0f) {
            float shadowAlpha = 1.0f;
            if (leaf.altitude >= 0.4f) {
                shadowAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            shadowAlpha = MathUtils.clamp(shadowAlpha, 0.0f, 1.0f) * 0.15f;

            float shadowOffset = leaf.altitude * 0.2f;
            int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
            drawLeafQuad(leaf.x - shadowOffset, leaf.y - shadowOffset, leaf.scale, leaf.angle, texture,
                    shadowAlpha, true, sceneData);
        }

        float leafAlpha = 1.0f;
        if (leaf.altitude > 0.0f) {
            if (leaf.altitude >= 0.4f) {
                leafAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            leafAlpha = MathUtils.clamp(leafAlpha, 0.0f, 1.0f);
        }

        int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
        drawLeafQuad(leaf.x, leaf.y, leaf.scale, leaf.angle, texture, leafAlpha, false, sceneData);
    }

    private void drawLeafQuad(float x, float y, float scale, float rotation, int texture, float alpha,
            boolean silhouette, FallScene.SceneData sceneData) {
        float drawX = x - sceneData.getXOffset() * 2.0f;
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, drawX, y, 0);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0, 0, 1);
        Matrix.scaleM(mModelMatrix, 0, scale, scale, 1);

        float[] mvMatrix = new float[16];
        Matrix.multiplyMM(mvMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mvMatrix, 0);

        drawQuad(-FallScene.LEAF_SIZE, -FallScene.LEAF_SIZE, FallScene.LEAF_SIZE, FallScene.LEAF_SIZE,
                texture, alpha, silhouette);
    }

    private void drawQuad(float left, float top, float right, float bottom, int texture, float alpha,
            boolean silhouette) {
        float[] vertices = {
                left, bottom, 0, 0.0f, 0.0f,
                right, bottom, 0, 1.0f, 0.0f,
                right, top, 0, 1.0f, 1.0f,
                left, top, 0, 0.0f, 1.0f
        };

        if (mLeafQuadVertexBuffer == null || mLeafQuadVertexBuffer.capacity() != vertices.length) {
            mLeafQuadVertexBuffer = createFloatBuffer(vertices);
        } else {
            mLeafQuadVertexBuffer.position(0);
            mLeafQuadVertexBuffer.put(vertices);
            mLeafQuadVertexBuffer.position(0);
        }

        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform1f(mAlphaHandle, alpha);
        if (silhouette) {
            GLES20.glUniform4f(mColorHandle, 0.0f, 0.0f, 0.0f, 1.0f);
        } else {
            GLES20.glUniform4f(mColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);
        }

        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 20, mLeafQuadVertexBuffer);

        mLeafQuadVertexBuffer.position(3);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 20, mLeafQuadVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(mSamplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void createProgram() {
        String vertexShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_vs.glsl");
        String fragmentShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_fs.glsl");
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "着色器编译失败!");
            return;
        }

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vs);
        GLES20.glAttachShader(mProgram, fs);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "程序链接失败: " + GLES20.glGetProgramInfoLog(mProgram));
        }

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mAlphaHandle = GLES20.glGetUniformLocation(mProgram, "uAlpha");
        mSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uSampler");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
    }

    private int compileShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private int loadTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            return 0;
        }

        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return texture[0];
    }

    private int createPlaceholderTexture(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);

        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return texture[0];
    }

    private int[] loadLeafTextures() {
        // Always load all 20 leaf textures; mLeafTextureCount controls which ones are used
        int leafCount = 20;
        int[] textures = new int[leafCount];
        for (int i = 0; i < leafCount; i++) {
            textures[i] = loadLeafTexture("fall/drawable/leaves_" + i + ".png");
        }
        return textures;
    }

    private int loadLeafTexture(String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        if (bitmap == null) {
            return 0;
        }

        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        bitmap.recycle();
        return texture[0];
    }

    private int[] createPlaceholderLeafTextures() {
        int[] textures = new int[14];
        int[] colors = {
                Color.parseColor("#8B4513"), Color.parseColor("#A0522D"),
                Color.parseColor("#8B6914"), Color.parseColor("#B8860B"),
                Color.parseColor("#D2691E"), Color.parseColor("#CD853F"),
                Color.parseColor("#DEB887"), Color.parseColor("#F4A460"),
                Color.parseColor("#FF8C00"), Color.parseColor("#FFA500"),
                Color.parseColor("#FFD700"), Color.parseColor("#FFFF00"),
                Color.parseColor("#FF6347"), Color.parseColor("#FF4500")
        };
        for (int i = 0; i < textures.length; i++) {
            textures[i] = createPlaceholderTexture(64, 64, colors[i]);
        }
        return textures;
    }

    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private void syncPerfSettingsIfNeeded(long nowMs) {
        if (nowMs - mLastPerfSyncMs < PERF_SYNC_INTERVAL_MS) {
            return;
        }
        mLastPerfSyncMs = nowMs;
        int fps = WallpaperSettings.getGlobalFrameRate(30);
        mTargetFps = Math.max(1, fps);
        mTargetFrameMs = Math.max(1L, 1000L / mTargetFps);
        mAnrDiagEnabled = WallpaperSettings.isVulkanAnrDiagnosticsEnabled(true);
    }

    private void recordFrameCost(long frameCostMs) {
        if (!mAnrDiagEnabled) {
            return;
        }
        if (frameCostMs >= ANR_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "Slow frame: " + frameCostMs + "ms, targetFps=" + mTargetFps);
        }
        mDiagFrameCount++;
        mDiagAccumulatedMs += frameCostMs;
        if (frameCostMs > mDiagMaxMs) {
            mDiagMaxMs = frameCostMs;
        }
        if (mDiagFrameCount >= 120) {
            long avg = mDiagAccumulatedMs / Math.max(1L, mDiagFrameCount);
            Log.i(TAG, "FrameStats avg=" + avg + "ms max=" + mDiagMaxMs + "ms fpsTarget=" + mTargetFps);
            mDiagFrameCount = 0L;
            mDiagAccumulatedMs = 0L;
            mDiagMaxMs = 0L;
        }
    }

}
