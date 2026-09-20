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

package com.reandroid.wallpaper.holospiral;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.FloatBuffer;

public class HoloSpiralGL extends GLESScene {
    private static final String TAG = "HoloSpiralGL";

    private static final float NEAR_PLANE = 1.0f;
    private static final float FAR_PLANE = 55.0f;

    private static final int STRIDE_BYTES = HoloSpiralScene.FLOATS_PER_VERTEX * 4;

    // ---- 场景逻辑层（非 GL）：参数、配色、几何数据、旋转角 ----
    private final HoloSpiralScene mScene = new HoloSpiralScene();

    private int mProgramBackground;
    private int mProgramGeometry;

    private int mBgPositionHandle;
    private int mBgColorHandle;

    private int mGeoPositionHandle;
    private int mGeoColorHandle;
    private int mGeoModelViewProjHandle;
    private int mGeoMaxPointSizeHandle;
    private int mGeoFarPlaneHandle;
    private int mGeoTextureHandle;

    private FloatBuffer mBackgroundBuffer;
    private FloatBuffer mInnerBuffer;
    private FloatBuffer mOuterBuffer;

    private int mPointTextureId;

    private final Context mContext;

    private final float[] mProjection = new float[16];
    private final float[] mBaseModelView = new float[16];
    private final float[] mModelView = new float[16];
    private final float[] mTempModelView = new float[16];
    private final float[] mMvp = new float[16];

    private float mXOffset;
    private boolean mInitialized;

    public HoloSpiralGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
    }

    @Override
    protected void onCreate() {
        if (mInitialized) {
            return;
        }
        if (mResources == null) {
            Log.w(TAG, "onCreate() called without resources");
            return;
        }
        mInitialized = true;

        createPrograms();
        getHandles();
        createGeometry();
        createTexture();
        initTransforms();

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);

        mScene.resetAnimation();
        resize(mWidth, mHeight);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        float aspect = width > 0 && height > 0 ? (float) width / (float) height : 1.0f;
        Matrix.perspectiveM(mProjection, 0, mScene.fov, aspect, NEAR_PLANE, FAR_PLANE);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (mScene.consumeGeometryDirty()) {
            createGeometry();
        }
        float dt = mScene.tickTime(timeMs);

        drawBackground();
        drawGeometry(dt);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    @Override
    public void release() {
        if (mPointTextureId != 0) {
            int[] textures = {mPointTextureId};
            GLES30.glDeleteTextures(1, textures, 0);
            mPointTextureId = 0;
        }
        if (mProgramBackground != 0) {
            GLES30.glDeleteProgram(mProgramBackground);
            mProgramBackground = 0;
        }
        if (mProgramGeometry != 0) {
            GLES30.glDeleteProgram(mProgramGeometry);
            mProgramGeometry = 0;
        }
        mInitialized = false;
    }

    private void createPrograms() {
        String bgVs = AssetLoader.readText(mContext, "holospiral/shaders/GLES/holospiral_vertex_background.glsl");
        String bgFs = AssetLoader.readText(mContext, "holospiral/shaders/GLES/holospiral_fragment_background.glsl");
        String geoVs = AssetLoader.readText(mContext, "holospiral/shaders/GLES/holospiral_vertex_geometry.glsl");
        String geoFs = AssetLoader.readText(mContext, "holospiral/shaders/GLES/holospiral_fragment_geometry.glsl");

        mProgramBackground = createProgram(bgVs, bgFs);
        mProgramGeometry = createProgram(geoVs, geoFs);
    }

    private void getHandles() {
        mBgPositionHandle = GLES30.glGetAttribLocation(mProgramBackground, "aPosition");
        mBgColorHandle = GLES30.glGetAttribLocation(mProgramBackground, "aColor");

        mGeoPositionHandle = GLES30.glGetAttribLocation(mProgramGeometry, "aPosition");
        mGeoColorHandle = GLES30.glGetAttribLocation(mProgramGeometry, "aColor");
        mGeoModelViewProjHandle = GLES30.glGetUniformLocation(mProgramGeometry, "uModelViewProj");
        mGeoMaxPointSizeHandle = GLES30.glGetUniformLocation(mProgramGeometry, "uMaxPointSize");
        mGeoFarPlaneHandle = GLES30.glGetUniformLocation(mProgramGeometry, "uFarPlane");
        mGeoTextureHandle = GLES30.glGetUniformLocation(mProgramGeometry, "uTexture0");
    }

    private void createGeometry() {
        mBackgroundBuffer = createFloatBuffer(mScene.buildBackgroundData());
        mInnerBuffer = createFloatBuffer(mScene.buildSpiralData(mScene.numInnerPoints,
                HoloSpiralScene.INNER_SPIRAL_DEPTH, mScene.innerRadius,
                HoloSpiralScene.SEPARATION_DEG, mScene.innerColorPrimary, mScene.innerColorSecondary));
        mOuterBuffer = createFloatBuffer(mScene.buildSpiralData(mScene.numOuterPoints,
                HoloSpiralScene.OUTER_SPIRAL_DEPTH, mScene.outerRadius,
                HoloSpiralScene.SEPARATION_DEG, mScene.outerColor, mScene.outerColor));
    }

    private void createTexture() {
        Bitmap bmp = AssetLoader.decodeBitmap(mContext, "holospiral/drawable/points_red_green.png");
        if (bmp == null) {
            Log.e(TAG, "Failed to decode point texture");
            return;
        }
        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        mPointTextureId = textures[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mPointTextureId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    private void initTransforms() {
        Matrix.setIdentityM(mBaseModelView, 0);
        Matrix.translateM(mBaseModelView, 0, -3.0f, -5.0f, -18.0f);
        Matrix.rotateM(mBaseModelView, 0, 20.0f, 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(mBaseModelView, 0, -10.0f, 1.0f, 0.0f, 0.0f);
    }

    private void drawBackground() {
        GLES30.glUseProgram(mProgramBackground);
        GLES30.glDisable(GLES30.GL_BLEND);

        mBackgroundBuffer.position(0);
        GLES30.glVertexAttribPointer(mBgPositionHandle, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, mBackgroundBuffer);
        GLES30.glEnableVertexAttribArray(mBgPositionHandle);

        mBackgroundBuffer.position(3);
        GLES30.glVertexAttribPointer(mBgColorHandle, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, mBackgroundBuffer);
        GLES30.glEnableVertexAttribArray(mBgColorHandle);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(mBgPositionHandle);
        GLES30.glDisableVertexAttribArray(mBgColorHandle);
    }

    private void drawGeometry(float dt) {
        if (mPointTextureId == 0) {
            return;
        }
        GLES30.glUseProgram(mProgramGeometry);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        GLES30.glUniform1f(mGeoMaxPointSizeHandle, mScene.maxPointSize);
        GLES30.glUniform1f(mGeoFarPlaneHandle, FAR_PLANE);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mPointTextureId);
        GLES30.glUniform1i(mGeoTextureHandle, 0);

        Matrix.setIdentityM(mModelView, 0);
        System.arraycopy(mBaseModelView, 0, mModelView, 0, mBaseModelView.length);
        Matrix.rotateM(mModelView, 0, mXOffset * -mScene.spiralRotateSpeed, 0.0f, 1.0f, 0.0f);

        drawSpiral(mOuterBuffer, mScene.numOuterPoints, -mScene.getOuterRotateAngle());
        drawSpiral(mInnerBuffer, mScene.numInnerPoints, mScene.getInnerRotateAngle());

        // 先按当前角度画、画完再推进（与原实现同序，提前推进会让相位差一帧）
        mScene.advanceRotateAngles(dt);
    }

    private void drawSpiral(FloatBuffer buffer, int count, float rotationZ) {
        System.arraycopy(mModelView, 0, mTempModelView, 0, mModelView.length);
        Matrix.rotateM(mTempModelView, 0, rotationZ, 0.0f, 0.0f, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mTempModelView, 0);
        GLES30.glUniformMatrix4fv(mGeoModelViewProjHandle, 1, false, mMvp, 0);

        buffer.position(0);
        GLES30.glVertexAttribPointer(mGeoPositionHandle, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, buffer);
        GLES30.glEnableVertexAttribArray(mGeoPositionHandle);

        buffer.position(3);
        GLES30.glVertexAttribPointer(mGeoColorHandle, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, buffer);
        GLES30.glEnableVertexAttribArray(mGeoColorHandle);

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count);

        GLES30.glDisableVertexAttribArray(mGeoPositionHandle);
        GLES30.glDisableVertexAttribArray(mGeoColorHandle);
    }

}
