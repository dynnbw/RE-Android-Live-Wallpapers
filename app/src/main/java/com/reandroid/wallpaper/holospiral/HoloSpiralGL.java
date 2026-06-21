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
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class HoloSpiralGL extends GLESScene {
    private static final String TAG = "HoloSpiralGL";

    private static final float NEAR_PLANE = 1.0f;
    private static final float FAR_PLANE = 55.0f;

    // ---- User-configurable parameters (initialized from SharedPreferences) ----
    private int mNumInnerPoints = 100;
    private float mInnerSpiralDepth = 50.0f;
    private float mInnerRadius = 5.0f;
    private float mInnerSeparationDeg = 23.0f;
    private int mNumOuterPoints = 50;
    private float mOuterSpiralDepth = 30.0f;
    private float mOuterRadius = 10.0f;
    private float mOuterSeparationDeg = 23.0f;
    private float mFov = 60.0f;
    private float mSpiralRotateSpeed = 15.0f;
    private float mInnerRotateSpeed = 1.5f;
    private float mOuterRotateSpeed = 0.5f;
    private float mMaxPointSize = 75.0f;
    private int mInnerColorPrimary = 0xB30000FF;
    private int mInnerColorSecondary = 0xD2A633FF;
    private int mOuterColor = 0xDC267894;
    private int mBgColorTop = 0xFF08001A;
    private int mBgColorBottom = 0xFF1A1A53;

    private static final int FLOATS_PER_VERTEX = 7;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    // ---- Prefs ----
    private SharedPreferences mPluginPrefs;
    private boolean mGeometryDirty;
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (sp, key) -> { if (key != null && key.startsWith("holospiral_")) mGeometryDirty = true; };

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
    private float mInnerRotateAngle;
    private float mOuterRotateAngle;
    private long mLastTimeMs;
    private boolean mInitialized;

    public HoloSpiralGL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
    }

    /** Called by BasePluginEngine via reflection to inject plugin-isolated prefs. */
    public void setPluginPrefs(SharedPreferences prefs) {
        if (mPluginPrefs != null) {
            mPluginPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        }
        mPluginPrefs = prefs;
        readParamsFromPrefs();
        prefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    private void readParamsFromPrefs() {
        if (mPluginPrefs == null) return;
        SharedPreferences p = mPluginPrefs;
        String scheme = p.getString("holospiral_color_scheme", "default");

        mNumInnerPoints = p.getInt("holospiral_inner_points", 100);
        mNumOuterPoints = p.getInt("holospiral_outer_points", 50);
        mMaxPointSize = p.getInt("holospiral_point_size", 75);
        mInnerRadius = p.getInt("holospiral_inner_radius", 5);
        mOuterRadius = p.getInt("holospiral_outer_radius", 10);
        mFov = p.getInt("holospiral_fov", 60);
        mSpiralRotateSpeed = p.getInt("holospiral_rotate_speed", 15);
        mInnerSeparationDeg = 23.0f;
        mOuterSeparationDeg = 23.0f;
        mInnerSpiralDepth = 50.0f;
        mOuterSpiralDepth = 30.0f;
        mInnerRotateSpeed = 1.5f;
        mOuterRotateSpeed = 0.5f;

        applyColorScheme(scheme);
        mGeometryDirty = true;
    }

    private void applyColorScheme(String scheme) {
        switch (scheme) {
            case "purple":
                mInnerColorPrimary = 0xB36600FF; mInnerColorSecondary = 0xD2CC33FF;
                mOuterColor = 0xDC8B00FF; mBgColorTop = 0xFF0D001A; mBgColorBottom = 0xFF1A0A3A;
                break;
            case "red":
                mInnerColorPrimary = 0xB3FF3300; mInnerColorSecondary = 0xD2FFAA33;
                mOuterColor = 0xDCFF6633; mBgColorTop = 0xFF1A0008; mBgColorBottom = 0xFF3A0A1A;
                break;
            case "green":
                mInnerColorPrimary = 0xB300FF44; mInnerColorSecondary = 0xD233FFAA;
                mOuterColor = 0xDC26FF78; mBgColorTop = 0xFF001A08; mBgColorBottom = 0xFF0A3A1A;
                break;
            case "gold":
                mInnerColorPrimary = 0xB3FFAA00; mInnerColorSecondary = 0xD2FFDD66;
                mOuterColor = 0xDCFFCC33; mBgColorTop = 0xFF1A1000; mBgColorBottom = 0xFF3A2A0A;
                break;
            case "ice":
                mInnerColorPrimary = 0xB300CCFF; mInnerColorSecondary = 0xD266EEFF;
                mOuterColor = 0xDC44CCFF; mBgColorTop = 0xFF00101A; mBgColorBottom = 0xFF0A2030;
                break;
            default: // "default"
                mInnerColorPrimary = 0xB30000FF; mInnerColorSecondary = 0xD2A633FF;
                mOuterColor = 0xDC267894; mBgColorTop = 0xFF08001A; mBgColorBottom = 0xFF1A1A53;
                break;
        }
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

        readParamsFromPrefs();
        createPrograms();
        getHandles();
        createGeometry();
        createTexture();
        initTransforms();

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        mLastTimeMs = 0L;
        resize(mWidth, mHeight);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        float aspect = width > 0 && height > 0 ? (float) width / (float) height : 1.0f;
        Matrix.perspectiveM(mProjection, 0, mFov, aspect, NEAR_PLANE, FAR_PLANE);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (mGeometryDirty) {
            mGeometryDirty = false;
            readParamsFromPrefs();
            createGeometry();
        }
        float dt = updateTime(timeMs);

        drawBackground();
        drawGeometry(dt);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    @Override
    public void release() {
        if (mPluginPrefs != null) {
            mPluginPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            mPluginPrefs = null;
        }
        if (mPointTextureId != 0) {
            int[] textures = {mPointTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            mPointTextureId = 0;
        }
        if (mProgramBackground != 0) {
            GLES20.glDeleteProgram(mProgramBackground);
            mProgramBackground = 0;
        }
        if (mProgramGeometry != 0) {
            GLES20.glDeleteProgram(mProgramGeometry);
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
        mBgPositionHandle = GLES20.glGetAttribLocation(mProgramBackground, "aPosition");
        mBgColorHandle = GLES20.glGetAttribLocation(mProgramBackground, "aColor");

        mGeoPositionHandle = GLES20.glGetAttribLocation(mProgramGeometry, "aPosition");
        mGeoColorHandle = GLES20.glGetAttribLocation(mProgramGeometry, "aColor");
        mGeoModelViewProjHandle = GLES20.glGetUniformLocation(mProgramGeometry, "uModelViewProj");
        mGeoMaxPointSizeHandle = GLES20.glGetUniformLocation(mProgramGeometry, "uMaxPointSize");
        mGeoFarPlaneHandle = GLES20.glGetUniformLocation(mProgramGeometry, "uFarPlane");
        mGeoTextureHandle = GLES20.glGetUniformLocation(mProgramGeometry, "uTexture0");
    }

    private void createGeometry() {
        mBackgroundBuffer = buildBackgroundBuffer();
        mInnerBuffer = buildSpiralBuffer(mNumInnerPoints, mInnerSpiralDepth, mInnerRadius,
                mInnerSeparationDeg, mInnerColorPrimary, mInnerColorSecondary);
        mOuterBuffer = buildSpiralBuffer(mNumOuterPoints, mOuterSpiralDepth, mOuterRadius,
                mOuterSeparationDeg, mOuterColor, mOuterColor);
    }

    private void createTexture() {
        Bitmap bmp = AssetLoader.decodeBitmap(mContext, "holospiral/drawable/points_red_green.png");
        if (bmp == null) {
            Log.e(TAG, "Failed to decode point texture");
            return;
        }
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mPointTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mPointTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    private void initTransforms() {
        Matrix.setIdentityM(mBaseModelView, 0);
        Matrix.translateM(mBaseModelView, 0, -3.0f, -5.0f, -18.0f);
        Matrix.rotateM(mBaseModelView, 0, 20.0f, 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(mBaseModelView, 0, -10.0f, 1.0f, 0.0f, 0.0f);

        mInnerRotateAngle = 0.0f;
        mOuterRotateAngle = 0.0f;
    }

    private float updateTime(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
            return 0.0f;
        }
        float dt = (timeMs - mLastTimeMs) * 0.001f;
        mLastTimeMs = timeMs;
        return dt;
    }

    private void drawBackground() {
        GLES20.glUseProgram(mProgramBackground);
        GLES20.glDisable(GLES20.GL_BLEND);

        mBackgroundBuffer.position(0);
        GLES20.glVertexAttribPointer(mBgPositionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, mBackgroundBuffer);
        GLES20.glEnableVertexAttribArray(mBgPositionHandle);

        mBackgroundBuffer.position(3);
        GLES20.glVertexAttribPointer(mBgColorHandle, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, mBackgroundBuffer);
        GLES20.glEnableVertexAttribArray(mBgColorHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mBgPositionHandle);
        GLES20.glDisableVertexAttribArray(mBgColorHandle);
    }

    private void drawGeometry(float dt) {
        if (mPointTextureId == 0) {
            return;
        }
        GLES20.glUseProgram(mProgramGeometry);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUniform1f(mGeoMaxPointSizeHandle, mMaxPointSize);
        GLES20.glUniform1f(mGeoFarPlaneHandle, FAR_PLANE);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mPointTextureId);
        GLES20.glUniform1i(mGeoTextureHandle, 0);

        Matrix.setIdentityM(mModelView, 0);
        System.arraycopy(mBaseModelView, 0, mModelView, 0, mBaseModelView.length);
        Matrix.rotateM(mModelView, 0, mXOffset * -mSpiralRotateSpeed, 0.0f, 1.0f, 0.0f);

        drawSpiral(mOuterBuffer, mNumOuterPoints, -mOuterRotateAngle);
        drawSpiral(mInnerBuffer, mNumInnerPoints, mInnerRotateAngle);

        mOuterRotateAngle = modulo360(mOuterRotateAngle + (dt * mOuterRotateSpeed));
        mInnerRotateAngle = modulo360(mInnerRotateAngle + (dt * mInnerRotateSpeed));
    }

    private void drawSpiral(FloatBuffer buffer, int count, float rotationZ) {
        System.arraycopy(mModelView, 0, mTempModelView, 0, mModelView.length);
        Matrix.rotateM(mTempModelView, 0, rotationZ, 0.0f, 0.0f, 1.0f);
        Matrix.multiplyMM(mMvp, 0, mProjection, 0, mTempModelView, 0);
        GLES20.glUniformMatrix4fv(mGeoModelViewProjHandle, 1, false, mMvp, 0);

        buffer.position(0);
        GLES20.glVertexAttribPointer(mGeoPositionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer);
        GLES20.glEnableVertexAttribArray(mGeoPositionHandle);

        buffer.position(3);
        GLES20.glVertexAttribPointer(mGeoColorHandle, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer);
        GLES20.glEnableVertexAttribArray(mGeoColorHandle);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);

        GLES20.glDisableVertexAttribArray(mGeoPositionHandle);
        GLES20.glDisableVertexAttribArray(mGeoColorHandle);
    }

    private FloatBuffer buildBackgroundBuffer() {
        float[] blue = convertColor(mBgColorTop);
        float[] black = convertColor(mBgColorBottom);

        float[] data = new float[] {
            -1.0f,  1.0f, 0.0f, blue[0],  blue[1],  blue[2],  blue[3],
            -1.0f, -1.0f, 0.0f, black[0], black[1], black[2], black[3],
             1.0f,  1.0f, 0.0f, blue[0],  blue[1],  blue[2],  blue[3],
             1.0f, -1.0f, 0.0f, black[0], black[1], black[2], black[3]
        };
        return createFloatBuffer(data);
    }

    private FloatBuffer buildSpiralBuffer(int count, float depth, float radius,
            float separationDegrees, int primaryColor, int secondaryColor) {
        float[] primary = convertColor(primaryColor);
        float[] secondary = convertColor(secondaryColor);

        float separationRads = (separationDegrees / 360.0f) * 2.0f * (float) Math.PI;
        float halfDepth = depth / 2.0f;
        float radians = 0.0f;

        float[] data = new float[count * FLOATS_PER_VERTEX];
        int idx = 0;

        for (int i = 0; i < count; i++) {
            float percentage = (float) i / (float) count;
            float x = radius * (float) Math.cos(radians);
            float y = radius * (float) Math.sin(radians);
            float z = (percentage * depth) - halfDepth;

            float r = (float) Math.sin(radians / 2.0f);
            float colorR = primary[0] + ((secondary[0] - primary[0]) * r);
            float colorG = primary[1] + ((secondary[1] - primary[1]) * r);
            float colorB = primary[2] + ((secondary[2] - primary[2]) * r);
            float colorA = primary[3] + ((secondary[3] - primary[3]) * r);

            data[idx++] = x;
            data[idx++] = y;
            data[idx++] = z;
            data[idx++] = colorR;
            data[idx++] = colorG;
            data[idx++] = colorB;
            data[idx++] = colorA;

            radians += separationRads;
        }

        return createFloatBuffer(data);
    }


    private float[] convertColor(int argb) {
        float a = ((argb >> 24) & 0xff) / 255.0f;
        float r = ((argb >> 16) & 0xff) / 255.0f;
        float g = ((argb >> 8) & 0xff) / 255.0f;
        float b = (argb & 0xff) / 255.0f;
        return new float[] {r, g, b, a};
    }

    private float modulo360(float value) {
        int multiplier = (int) (value * (1.0f / 360.0f));
        return value - (multiplier * 360.0f);
    }
}
