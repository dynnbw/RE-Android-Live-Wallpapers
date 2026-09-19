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
import com.reandroid.utils.SkyField;
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
    /** assets/fall/drawable/ 下 leaves_N.png 的文件数（绿叶全开时用满）。 */
    private static final int LEAF_TEXTURE_FILES = 20;

    private int mProgram;       // leaf shader
    private int mWaterProgram;  // water shader (GPU ripple)
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mAlphaHandle;
    private int mSamplerHandle;
    private int mColorHandle;
    // Water shader uniforms
    private int mWMatrixHandle;
    private int mWAlphaHandle;
    private int mWMaskHandle;
    private int mWSkyHandle;
    private int mWColorHandle;
    private int mWPositionHandle;
    private int mWTexCoordHandle;
    private int mWGlHeightHandle;
    private int mWBgScaleHandle;
    private int mWMeshScaleXHandle;
    private int mWMeshScaleYHandle;
    private int mWDxMulHandle;
    private int mWXOffsetHandle;
    private int mWRotateHandle;
    private int mWDropHandle;
    private int mWDropCountHandle;
    private int[] mLeafTextures;
    /** 河床的树：单通道遮罩（白=天空 黑=树）。 */
    private int mMaskTexture;
    /** 河床的天空：由 pond_sky_fields.txt 生成的 24x64 竖直色带。 */
    private int mSkyTexture;
    private FloatBuffer mWaterMeshVertexBuffer;
    private FloatBuffer mWaterMeshTexCoordBuffer;
    private FloatBuffer mLeafQuadVertexBuffer;
    /**
     * 叶片四边形的顶点模板（见 drawQuad）：z 恒为 0，uv 固定，
     * 每帧只有 4 个角点的 x/y 会变，所以模板只需要作一次，
     * 调用时改写那 8 个槽位即可，不必每次重新构造整个数组。
     */
    private static final float[] LEAF_QUAD_TEMPLATE = {
            0f, 0f, 0f, 0.0f, 0.0f,
            0f, 0f, 0f, 1.0f, 0.0f,
            0f, 0f, 0f, 1.0f, 1.0f,
            0f, 0f, 0f, 0.0f, 1.0f
    };
    private final float[] mLeafQuadVertices = LEAF_QUAD_TEMPLATE.clone();
    private ShortBuffer mWaterIndexBuffer;
    private final float[] mModelMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];
    /** drawLeafQuad 的临时矩阵：每片叶子用两次，不能每帧新分配。 */
    private final float[] mLeafMvMatrix = new float[16];
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

        if (mMaskTexture != 0 || mSkyTexture != 0) {
            int[] tex = new int[] { mMaskTexture, mSkyTexture };
            GLES20.glDeleteTextures(2, tex, 0);
            mMaskTexture = 0;
            mSkyTexture = 0;
        }

        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mWaterProgram != 0) {
            GLES20.glDeleteProgram(mWaterProgram);
            mWaterProgram = 0;
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
        createWaterProgram();

        try {
            mLeafTextures = loadLeafTextures();
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载枫叶纹理失败", e);
            mLeafTextures = createPlaceholderLeafTextures();
        }
        // ensureResources() already set mLeafTextureCount from prefs — don't override
        try {
            mMaskTexture = loadMaskTexture("fall/drawable/pond_mask.png");
            mSkyTexture = createPondSkyTexture();
        } catch (Exception e) {
            Log.e(TAG, "GL线程加载河床纹理失败", e);
            mMaskTexture = 0;
            mSkyTexture = 0;
        }
        // 任一缺失都会让水面变成未定义采样，各自退回占位（全白遮罩 = 整屏天空）
        if (mMaskTexture == 0) {
            mMaskTexture = createSolidMaskTexture();
        }
        if (mSkyTexture == 0) {
            mSkyTexture = createPlaceholderTexture(256, 256, Color.parseColor("#4A6FA5"));
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
                if (!mScene.isSwipeRippleEnabled()) {
                    break;
                }
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

    /** 滑动水波纹开关（滑动每 42px 触发一次水波纹），供 FallView 查询。 */
    public boolean isSwipeRippleEnabled() {
        return mScene.isSwipeRippleEnabled();
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

        drawWaterQuad(sceneData);  // uses mWaterProgram internally
        if (mProgram == 0) return;
        GLES20.glUseProgram(mProgram);
        for (FallScene.Leaf leaf : sceneData.getLeaves()) {
            drawLeaf(leaf, sceneData);
        }

        /*
         * glGetError 是同步查询，可能强制冲刷管线、把帧时间拉长，所以只在诊断模式下做。
         * 原来每帧无条件调用。
         */
        if (mAnrDiagEnabled) {
            int glError = GLES20.glGetError();
            if (glError != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "drawFrame中GL错误: " + glError);
            }
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
        }
        // Texcoords static — ripple displacement computed in vertex shader
    }

    private void drawWaterQuad(FallScene.SceneData sceneData) {
        if (mWaterProgram == 0) return;
        GLES20.glUseProgram(mWaterProgram);

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.multiplyMM(mViewMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mViewMatrix, 0);
        GLES20.glUniformMatrix4fv(mWMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniform1f(mWAlphaHandle, 1.0f);
        GLES20.glUniform4f(mWColorHandle, 1.0f, 1.0f, 1.0f, 1.0f);

        // Scene parameters for GPU ripple
        GLES20.glUniform1f(mWGlHeightHandle, sceneData.getGlHeight());
        GLES20.glUniform1f(mWBgScaleHandle, sceneData.getBgScale());
        GLES20.glUniform1f(mWMeshScaleXHandle, sceneData.getMeshScaleX());
        GLES20.glUniform1f(mWMeshScaleYHandle, sceneData.getMeshScaleY());
        GLES20.glUniform1f(mWDxMulHandle, sceneData.getDxMul());
        GLES20.glUniform1f(mWXOffsetHandle, sceneData.getXOffset());
        GLES20.glUniform1f(mWRotateHandle, (float) sceneData.getRotate());
        GLES20.glUniform1f(mWDropCountHandle, (float) sceneData.getActiveDropCount());
        GLES20.glUniform4fv(mWDropHandle, Math.max(1, sceneData.getActiveDropCount()), sceneData.getDropData(), 0);

        GLES20.glEnableVertexAttribArray(mWPositionHandle);
        mWaterMeshVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mWPositionHandle, 3, GLES20.GL_FLOAT, false, 12, mWaterMeshVertexBuffer);

        // 水面顶点着色器不使用 aTexCoord 属性（vTexCoord 由 VS 程序化计算），
        // 未声明的属性 location 为 -1，启用/绑定它会触发 GL_INVALID_VALUE (1281)。
        if (mWTexCoordHandle >= 0) {
            GLES20.glEnableVertexAttribArray(mWTexCoordHandle);
            mWaterMeshTexCoordBuffer.position(0);
            GLES20.glVertexAttribPointer(mWTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mWaterMeshTexCoordBuffer);
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mMaskTexture);
        GLES20.glUniform1i(mWMaskHandle, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mSkyTexture);
        GLES20.glUniform1i(mWSkyHandle, 1);

        int indexCount = sceneData.getWaterMeshIndexCount();
        if (indexCount > 0) {
            if (mWaterIndexBuffer == null || mWaterIndexBuffer.capacity() != sceneData.getWaterMeshIndices().length) {
                mWaterIndexBuffer = ByteBuffer.allocateDirect(sceneData.getWaterMeshIndices().length * 2)
                        .order(ByteOrder.nativeOrder()).asShortBuffer();
                mWaterIndexBuffer.put(sceneData.getWaterMeshIndices()).position(0);
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, mWaterIndexBuffer);
        }

        GLES20.glDisableVertexAttribArray(mWPositionHandle);
        if (mWTexCoordHandle >= 0) {
            GLES20.glDisableVertexAttribArray(mWTexCoordHandle);
        }
    }

    private void drawLeaf(FallScene.Leaf leaf, FallScene.SceneData sceneData) {
        if (mLeafTextures == null || mLeafTextures.length == 0) {
            return;
        }
        float sizeMul = mScene.getLeafSizeMultiplier();

        if (leaf.altitude > 0.0f) {
            float shadowAlpha = 1.0f;
            if (leaf.altitude >= 0.4f) {
                shadowAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            shadowAlpha = MathUtils.clamp(shadowAlpha, 0.0f, 1.0f) * 0.15f;

            float shadowOffset = leaf.altitude * 0.2f;
            int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
            drawLeafQuad(leaf.x - shadowOffset, leaf.y - shadowOffset, leaf.scale * sizeMul, leaf.angle,
                    texture, shadowAlpha, true, sceneData);
        }

        float leafAlpha = 1.0f;
        if (leaf.altitude > 0.0f) {
            if (leaf.altitude >= 0.4f) {
                leafAlpha = 1.0f - (leaf.altitude - 0.4f) / 0.1f;
            }
            leafAlpha = MathUtils.clamp(leafAlpha, 0.0f, 1.0f);
        }

        int texture = mLeafTextures[leaf.leafTextureIndex % mLeafTextures.length];
        drawLeafQuad(leaf.x, leaf.y, leaf.scale * sizeMul, leaf.angle, texture, leafAlpha, false,
                sceneData);
    }

    private void drawLeafQuad(float x, float y, float scale, float rotation, int texture, float alpha,
            boolean silhouette, FallScene.SceneData sceneData) {
        float drawX = x - sceneData.getXOffset() * 2.0f;
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, drawX, y, 0);
        Matrix.rotateM(mModelMatrix, 0, rotation, 0, 0, 1);
        Matrix.scaleM(mModelMatrix, 0, scale, scale, 1);

        float[] mvMatrix = mLeafMvMatrix;
        Matrix.multiplyMM(mvMatrix, 0, sceneData.getViewMatrix(), 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, sceneData.getProjectionMatrix(), 0, mvMatrix, 0);

        drawQuad(-FallScene.LEAF_SIZE, -FallScene.LEAF_SIZE, FallScene.LEAF_SIZE, FallScene.LEAF_SIZE,
                texture, alpha, silhouette);
    }

    private void drawQuad(float left, float top, float right, float bottom, int texture, float alpha,
            boolean silhouette) {
        float[] vertices = mLeafQuadVertices;
        vertices[0] = left;   vertices[1] = bottom;
        vertices[5] = right;  vertices[6] = bottom;
        vertices[10] = right; vertices[11] = top;
        vertices[15] = left;  vertices[16] = top;

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

    private void createWaterProgram() {
        // Generate vertex shader with dynamic drop array size (no hard limit)
        int maxDrops = WallpaperSettings.getFallMaxDrops(80);
        String template = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_water_vs.glsl");
        String vertexShader = template.replace("$DROP_SIZE", String.valueOf(maxDrops));
        String fragmentShader = AssetLoader.readText(mContext, "fall/shaders/GLES/fall_water_fs.glsl");
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "Water着色器编译失败!");
            if (vs != 0) GLES20.glDeleteShader(vs);
            if (fs != 0) GLES20.glDeleteShader(fs);
            return;
        }

        mWaterProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mWaterProgram, vs);
        GLES20.glAttachShader(mWaterProgram, fs);
        GLES20.glLinkProgram(mWaterProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mWaterProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Water程序链接失败: " + GLES20.glGetProgramInfoLog(mWaterProgram));
        }

        mWPositionHandle     = GLES20.glGetAttribLocation(mWaterProgram, "aPosition");
        mWTexCoordHandle     = GLES20.glGetAttribLocation(mWaterProgram, "aTexCoord");
        mWMatrixHandle       = GLES20.glGetUniformLocation(mWaterProgram, "uMVPMatrix");
        mWAlphaHandle        = GLES20.glGetUniformLocation(mWaterProgram, "uAlpha");
        mWMaskHandle         = GLES20.glGetUniformLocation(mWaterProgram, "uMask");
        mWSkyHandle          = GLES20.glGetUniformLocation(mWaterProgram, "uSky");
        mWColorHandle        = GLES20.glGetUniformLocation(mWaterProgram, "uColor");
        mWGlHeightHandle     = GLES20.glGetUniformLocation(mWaterProgram, "u_glHeight");
        mWBgScaleHandle      = GLES20.glGetUniformLocation(mWaterProgram, "u_bgScale");
        mWMeshScaleXHandle   = GLES20.glGetUniformLocation(mWaterProgram, "u_meshScaleX");
        mWMeshScaleYHandle   = GLES20.glGetUniformLocation(mWaterProgram, "u_meshScaleY");
        mWDxMulHandle        = GLES20.glGetUniformLocation(mWaterProgram, "u_dxMul");
        mWXOffsetHandle      = GLES20.glGetUniformLocation(mWaterProgram, "u_xOffset");
        mWRotateHandle       = GLES20.glGetUniformLocation(mWaterProgram, "u_rotate");
        mWDropHandle         = GLES20.glGetUniformLocation(mWaterProgram, "u_drop");
        mWDropCountHandle    = GLES20.glGetUniformLocation(mWaterProgram, "u_dropCount");

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
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

    /**
     * 把白底黑图的河床遮罩上传成单通道纹理（GL_ALPHA）。
     * 解码与条带化的细节见 {@link AssetLoader#decodeMask}。
     */
    private int loadMaskTexture(String assetPath) {
        AssetLoader.Mask mask = AssetLoader.decodeMask(mContext, assetPath);
        if (mask == null || mask.pixels.length == 0) {
            return 0;
        }

        ByteBuffer buf = ByteBuffer.allocateDirect(mask.pixels.length).order(ByteOrder.nativeOrder());
        buf.put(mask.pixels).position(0);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, mask.width, mask.height, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** 1×1 全白遮罩：贴图缺失时的退路，效果是整屏天空。 */
    private int createSolidMaskTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
        buf.put((byte) 255).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 1, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** 从 pond_sky_fields.txt 的 SKY_FIELD_DUSK 段生成 24×64 的竖直天空色带。 */
    private int createPondSkyTexture() {
        String text = AssetLoader.readText(mContext, "fall/data/pond_sky_fields.txt");
        int[][] field = SkyField.parseSection(text, "SKY_FIELD_DUSK");
        if (field == null) {
            Log.e(TAG, "天空色场解析失败");
            return 0;
        }
        return SkyField.createTexture(field, false);
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
        // 只加载场景真正会用到的那几张：绿叶关掉时是 14，多传 6 张 512² 会白占约 8 MB 显存
        // （含 mipmap）。getLeafTextureCount() 在 ensureResources() 里就已按 prefs 定好。
        int leafCount = MathUtils.clamp(mScene.getLeafTextureCount(), 1, LEAF_TEXTURE_FILES);
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
