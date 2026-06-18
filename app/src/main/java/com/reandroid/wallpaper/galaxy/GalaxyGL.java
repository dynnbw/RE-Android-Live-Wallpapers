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

package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;
import com.reandroid.settings.WallpaperSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 星系动态壁纸 - 从RenderScript完全移植到OpenGL ES 2.0
 * 100%还原视觉效果，包含12000个在椭圆轨道运行的粒子
 */
public class GalaxyGL extends GLESScene {
    private static final String TAG = "GalaxyGL";
    private static final long PERF_SYNC_INTERVAL_MS = 1000L;
    private static final long ANR_FRAME_THRESHOLD_MS = 200L;

    private final Context mContext;
    private boolean mGLInitialized = false;
    private int mBgProgram;
    private int mParticleProgram;
    private int mLightProgram;
    private int mTexSpace;
    private int mTexFlares;
    private int mTexLight1;
    private boolean mUseLight2;
    private FloatBuffer mParticlePositionBuffer;
    private FloatBuffer mParticleColorBuffer;
    private FloatBuffer mBgVertexBuffer;
    private FloatBuffer mLightQuadBuffer;
    private final float[] mLightQuadVerts = new float[20];
    private final GalaxyScene mScene;
    private android.content.SharedPreferences mPluginPrefs;
    private int mTargetFps = 30;
    private long mTargetFrameMs = 33L;
    private boolean mAnrDiagEnabled = false;
    private long mLastPerfSyncMs = 0L;
    private long mDiagFrameCount = 0L;
    private long mDiagAccumulatedMs = 0L;
    private long mDiagMaxMs = 0L;
    
    /**
     * 构造函数
     * @param width  屏幕宽度
     * @param height 屏幕高度
     * @param context 上下文对象
     */
    public GalaxyGL(int width, int height, Context context) {
        super(width, height);
        mContext = context;
        mScene = new GalaxyScene(width, height, context);
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(android.content.SharedPreferences prefs) {
        mPluginPrefs = prefs;
        mScene.setPluginPrefs(prefs);
    }

    private boolean readLight2FromPrefs() {
        if (mPluginPrefs != null) return mPluginPrefs.getBoolean("pref_galaxy_use_light2", false);
        Log.w(TAG, "mPluginPrefs is null, defaulting light2 to false");
        return false;
    }

    /**
     * 生命周期回调 - 创建时触发
     * GL初始化会在GL线程中执行
     */
    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate - 将在GL线程初始化");
    }

    @Override
    public void release() {
        int[] tex = new int[] { mTexSpace, mTexFlares, mTexLight1 };
        GLES20.glDeleteTextures(tex.length, tex, 0);
        mTexSpace = 0;
        mTexFlares = 0;
        mTexLight1 = 0;

        if (mBgProgram != 0) {
            GLES20.glDeleteProgram(mBgProgram);
            mBgProgram = 0;
        }
        if (mParticleProgram != 0) {
            GLES20.glDeleteProgram(mParticleProgram);
            mParticleProgram = 0;
        }
        if (mLightProgram != 0) {
            GLES20.glDeleteProgram(mLightProgram);
            mLightProgram = 0;
        }

        mParticlePositionBuffer = null;
        mParticleColorBuffer = null;
        mGLInitialized = false;
    }
    public void setParticleCount(int count) {
        mScene.setParticleCount(count);
    }

    public void setPreciseCalculation(boolean enabled) {
        mScene.setPreciseCalculation(enabled);
    }

    public void setParticleAlphaPercent(int alphaPercent) {
        mScene.setParticleAlphaPercent(alphaPercent);
    }

    public void setArmCount(int armCount) {
        mScene.setArmCount(armCount);
    }

    public void setArmOffset(float armOffset) {
        mScene.setArmOffset(armOffset);
    }

    public void setPitchAngleDeg(float pitchAngleDeg) {
        mScene.setPitchAngleDeg(pitchAngleDeg);
    }

    public void setInnerScatter(float innerScatter) {
        mScene.setInnerScatter(innerScatter);
    }

    public void setOuterScatter(float outerScatter) {
        mScene.setOuterScatter(outerScatter);
    }

    public void setTurbulence(float turbulence) {
        mScene.setTurbulence(turbulence);
    }

    public void setForbiddenRadius(float forbiddenRadius) {
        mScene.setForbiddenRadius(forbiddenRadius);
    }

    public void setEllipseRatio(float ellipseRatio) {
        mScene.setEllipseRatio(ellipseRatio);
    }

    public void setEllipseTwist(float ellipseTwist) {
        mScene.setEllipseTwist(ellipseTwist);
    }

    private void initGL() {
        if (mGLInitialized || mResources == null) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        Log.d(TAG, "initGL 开始执行");
        mGLInitialized = true;
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);

        createPrograms();
        loadTextures();

        Log.d(TAG, "initGL 执行完成");
    }

    private void createPrograms() {
        mBgProgram = createProgram(
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_bg_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_bg_fs.glsl")
        );

        mParticleProgram = createProgram(
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_particle_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_particle_fs.glsl")
        );

        mLightProgram = createProgram(
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_light_vs.glsl"),
            AssetLoader.readText(mContext, "galaxy/shaders/GLES/galaxy_light_fs.glsl")
        );

        Log.d(TAG, "着色器程序创建完成");
    }



    private void loadTextures() {
        mTexSpace = loadTexture(AssetLoader.decodeBitmap(mContext, "galaxy/drawable/galaxy_space.jpg"));
        mTexFlares = loadTexture(AssetLoader.decodeBitmap(mContext, "galaxy/drawable/galaxy_flares.png"));
        mUseLight2 = readLight2FromPrefs();
        mTexLight1 = loadTexture(AssetLoader.decodeBitmap(mContext,
                mUseLight2 ? "galaxy/drawable/light2.png" : "galaxy/drawable/light1.jpg"));

        Log.d(TAG, "纹理加载完成");
    }

    private void syncLightTexturePreference() {
        boolean desiredUseLight2 = readLight2FromPrefs();
        if (desiredUseLight2 == mUseLight2 || mContext == null) {
            return;
        }

        if (mTexLight1 != 0) {
            int[] tex = new int[] { mTexLight1 };
            GLES20.glDeleteTextures(1, tex, 0);
            mTexLight1 = 0;
        }

        mTexLight1 = loadTexture(AssetLoader.decodeBitmap(mContext,
                desiredUseLight2 ? "galaxy/drawable/light2.png" : "galaxy/drawable/light1.jpg"));
        mUseLight2 = desiredUseLight2;
    }

    private int loadTexture(Bitmap bitmap) {
        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        return textureHandle[0];
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void drawFrame(long timeMs) {
        long frameStart = SystemClock.uptimeMillis();
        syncPerfSettingsIfNeeded(frameStart);

        mScene.update(timeMs);
        GalaxyScene.SceneData sceneData = mScene.getSceneData();

        if (!mGLInitialized) {
            initGL();
        }

        syncLightTexturePreference();

        syncParticleBuffers(sceneData);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawBackground();
        drawParticles(sceneData);
        drawLights(sceneData);

        long frameCost = SystemClock.uptimeMillis() - frameStart;
        recordFrameCost(frameCost);
    }

    private void syncParticleBuffers(GalaxyScene.SceneData sceneData) {
        boolean rebuildBuffers = mScene.consumeParticleBufferRebuildRequested()
                || mParticlePositionBuffer == null
                || mParticleColorBuffer == null
                || mParticlePositionBuffer.capacity() != sceneData.getParticlePositions().length
                || mParticleColorBuffer.capacity() != sceneData.getParticleColors().length;

        if (rebuildBuffers) {
            mParticlePositionBuffer = createFloatBuffer(sceneData.getParticlePositions());
            mParticleColorBuffer = createFloatBuffer(sceneData.getParticleColors());
            return;
        }

        if (mScene.consumeParticlePositionsDirty()) {
            mParticlePositionBuffer.position(0);
            mParticlePositionBuffer.put(sceneData.getParticlePositions());
            mParticlePositionBuffer.position(0);
        }
    }

    private void drawBackground() {
        GLES20.glUseProgram(mBgProgram);

        float[] vertices = {
            -1, -1, 0, 1,
             1, -1, 1, 1,
            -1,  1, 0, 0,
             1,  1, 1, 0
        };

        if (mBgVertexBuffer == null || mBgVertexBuffer.capacity() != vertices.length) {
            mBgVertexBuffer = createFloatBuffer(vertices);
        } else {
            mBgVertexBuffer.position(0);
            mBgVertexBuffer.put(vertices);
            mBgVertexBuffer.position(0);
        }

        int posHandle = GLES20.glGetAttribLocation(mBgProgram, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mBgProgram, "aTexCoord");
        int samplerHandle = GLES20.glGetUniformLocation(mBgProgram, "uTexture");

        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        mBgVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, mBgVertexBuffer);
        mBgVertexBuffer.position(2);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, mBgVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexSpace);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private void drawParticles(GalaxyScene.SceneData sceneData) {
        GLES20.glUseProgram(mParticleProgram);

        int posHandle = GLES20.glGetAttribLocation(mParticleProgram, "aPosition");
        int colorHandle = GLES20.glGetAttribLocation(mParticleProgram, "aColor");
        int mvpHandle = GLES20.glGetUniformLocation(mParticleProgram, "uMVPMatrix");
        int samplerHandle = GLES20.glGetUniformLocation(mParticleProgram, "uTexture");
        int alphaHandle = GLES20.glGetUniformLocation(mParticleProgram, "uAlphaMultiplier");

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, sceneData.getMvpMatrix(), 0);
        GLES20.glUniform1f(alphaHandle, sceneData.getParticleAlphaMultiplier());

        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(colorHandle);

        mParticlePositionBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, mParticlePositionBuffer);

        mParticleColorBuffer.position(0);
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, mParticleColorBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexFlares);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, sceneData.getParticleCount());

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(colorHandle);
    }

    private void drawLights(GalaxyScene.SceneData sceneData) {
        GLES20.glUseProgram(mLightProgram);

        int posHandle = GLES20.glGetAttribLocation(mLightProgram, "aPosition");
        int texHandle = GLES20.glGetAttribLocation(mLightProgram, "aTexCoord");
        int mvpHandle = GLES20.glGetUniformLocation(mLightProgram, "uMVPMatrix");
        int samplerHandle = GLES20.glGetUniformLocation(mLightProgram, "uTexture");

        float sx = (512.0f / mWidth) * 1.1f;
        float sy = (512.0f / mWidth) * 1.2f;

        mLightQuadVerts[0]  = -sx; mLightQuadVerts[1]  = -sy; mLightQuadVerts[2] = 0; mLightQuadVerts[3] = 0; mLightQuadVerts[4] = 0;
        mLightQuadVerts[5]  =  sx; mLightQuadVerts[6]  = -sy; mLightQuadVerts[7] = 0; mLightQuadVerts[8] = 1; mLightQuadVerts[9] = 0;
        mLightQuadVerts[10] = -sx; mLightQuadVerts[11] =  sy; mLightQuadVerts[12]= 0; mLightQuadVerts[13]= 0; mLightQuadVerts[14]= 1;
        mLightQuadVerts[15] =  sx; mLightQuadVerts[16] =  sy; mLightQuadVerts[17]= 0; mLightQuadVerts[18]= 1; mLightQuadVerts[19]= 1;

        if (mLightQuadBuffer == null) {
            mLightQuadBuffer = createFloatBuffer(mLightQuadVerts);
        } else {
            mLightQuadBuffer.position(0);
            mLightQuadBuffer.put(mLightQuadVerts);
            mLightQuadBuffer.position(0);
        }

        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, sceneData.getMvpMatrix(), 0);

        mLightQuadBuffer.position(0);
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 20, mLightQuadBuffer);
        mLightQuadBuffer.position(3);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 20, mLightQuadBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexLight1);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
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