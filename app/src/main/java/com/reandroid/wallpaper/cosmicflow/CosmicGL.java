package com.reandroid.wallpaper.cosmicflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;

import com.reandroid.gles.GLESScene;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Cosmic Flow 壁纸 GLES 渲染器 —— Sony Xperia Cosmic Flow 移植。
 * <p>
 * 管线:加性混合(GL_ONE, GL_ONE);先背景(方形网格 + bg_grey 纹理 + primary 色调 +
 * 波浪位移),再流场(100×100 噪声位移网格,flow_greyscale alpha 加权次级色)。
 * 原版帧限(30/20/12 FPS 按触摸年龄)忠实保留以省电。
 */
public class CosmicGL extends GLESScene {


    // 流场网格(原版 Config.FLOW_MESH_SIZE = 100)
    private static final short FLOW_MESH_SIZE = 100;
    private static final int FLOW_VERTICES = FLOW_MESH_SIZE * FLOW_MESH_SIZE;       // 10000
    private static final int FLOW_INDICES = (FLOW_MESH_SIZE - 1) * (FLOW_MESH_SIZE * 2 + 2); // 19998

    private final Context mContext;
    private final CosmicScene mScene;

    // ---- Program ----
    private int mBgProgram;
    private int mFlowProgram;

    // ---- Background attributes/uniforms ----
    private int mBgAPosition;
    private int mBgATexCoord;
    private int mBgUTimeHeightScaleColorXOffset;
    private int mBgUPrimaryColor;
    private int mBgUSampler;

    // ---- Flow attributes/uniforms ----
    private int mFlowAPosition;
    private int mFlowUMvpMatrix;
    private int mFlowUTimeNoiseScaleColor;
    private int mFlowUNoisePos01;
    private int mFlowUPrimaryColor;
    private int mFlowUSecondaryColor;
    private int mFlowUSampler;

    // ---- Textures / buffers ----
    private int mBgTexture;
    private int mFlowTexture;
    private int mSquarePosVbo;
    private int mSquareIndexVbo;
    private int mFlowPosVbo;
    private int mFlowIndexVbo;
    private final FloatBuffer mSquareTexCoord;

    // ---- Matrices ----
    private final float[] mProjMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];

    // ---- Frame limiting ----
    private long mLastFrameMs = -1;
    private boolean mIsFirstFrame = true;

    public CosmicGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
        mScene = new CosmicScene();
        mSquareTexCoord = newFloatBuffer(new float[]{
                0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
    }

    public void setPluginPrefs(SharedPreferences p) {
        mScene.setPluginPrefs(p);
    }

    @Override
    protected void onCreate() {
        // 非 GL 初始化:无
    }

    /** 创建全部 GL 资源(GL 线程,惰性) */
    private void initGL() {
        String bgVs = AssetLoader.readText(mContext, "cosmicflow/shaders/GLES/bg_vs.glsl");
        String bgFs = AssetLoader.readText(mContext, "cosmicflow/shaders/GLES/bg_fs.glsl");
        String flowVs = AssetLoader.readText(mContext, "cosmicflow/shaders/GLES/flow_vs.glsl");
        String flowFs = AssetLoader.readText(mContext, "cosmicflow/shaders/GLES/flow_fs.glsl");
        mBgProgram = createProgram(bgVs, bgFs);
        mFlowProgram = createProgram(flowVs, flowFs);
        if (mBgProgram == 0 || mFlowProgram == 0) {
            return;
        }

        mBgAPosition = GLES30.glGetAttribLocation(mBgProgram, "vPosition");
        mBgATexCoord = GLES30.glGetAttribLocation(mBgProgram, "texCoord");
        mBgUTimeHeightScaleColorXOffset = GLES30.glGetUniformLocation(mBgProgram, "u_Time_HeightScale_Color_XOffset");
        mBgUPrimaryColor = GLES30.glGetUniformLocation(mBgProgram, "u_PrimaryColor");
        mBgUSampler = GLES30.glGetUniformLocation(mBgProgram, "sTexture");

        mFlowAPosition = GLES30.glGetAttribLocation(mFlowProgram, "aPosition");
        mFlowUMvpMatrix = GLES30.glGetUniformLocation(mFlowProgram, "uMVPMatrix");
        mFlowUTimeNoiseScaleColor = GLES30.glGetUniformLocation(mFlowProgram, "u_Time_NoiseScale_Color");
        mFlowUNoisePos01 = GLES30.glGetUniformLocation(mFlowProgram, "u_NoisePos01");
        mFlowUPrimaryColor = GLES30.glGetUniformLocation(mFlowProgram, "u_PrimaryColor");
        mFlowUSecondaryColor = GLES30.glGetUniformLocation(mFlowProgram, "u_SecondaryColor");
        mFlowUSampler = GLES30.glGetUniformLocation(mFlowProgram, "sTexture");

        // 方形背景网格:(-1,1,0.02)(1,1,0.02)(-1,-1,0.02)(1,-1,0.02)
        FloatBuffer squarePos = newFloatBuffer(new float[]{
                -1f, 1f, 0.02f, 1f, 1f, 0.02f, -1f, -1f, 0.02f, 1f, -1f, 0.02f});
        ShortBuffer squareIdx = newShortBuffer(new short[]{0, 1, 2, 1, 2, 3});
        int[] bufs = new int[4];
        GLES30.glGenBuffers(4, bufs, 0);
        mSquarePosVbo = bufs[0];
        mSquareIndexVbo = bufs[1];
        mFlowPosVbo = bufs[2];
        mFlowIndexVbo = bufs[3];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mSquarePosVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, squarePos.capacity() * 4, squarePos, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mSquareIndexVbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, squareIdx.capacity() * 2, squareIdx, GLES30.GL_STATIC_DRAW);

        // 流场网格:100×100,顶点 (j/100, i·0.7/100, 0)
        FloatBuffer flowPos = newFloatBuffer(FLOW_VERTICES * 3);
        for (int i = 0; i < FLOW_MESH_SIZE; i++) {
            for (int j = 0; j < FLOW_MESH_SIZE; j++) {
                flowPos.put(j / (float) FLOW_MESH_SIZE);
                flowPos.put(i * 0.7f / FLOW_MESH_SIZE);
                flowPos.put(0f);
            }
        }
        flowPos.position(0);
        // 索引:99 行 × 202,zig-zag 行条带 + 2 个退化重启(原版 FlowMeshPrimitive.indices)
        ShortBuffer flowIdx = newShortBuffer(FLOW_INDICES);
        int idxSizeStrip = FLOW_MESH_SIZE * 2 + 2;
        for (int i = 0; i < FLOW_MESH_SIZE - 1; i++) {
            int i2 = i * idxSizeStrip;
            for (int s3 = 0; s3 < FLOW_MESH_SIZE; s3++) {
                int s2 = i2 + 1;
                flowIdx.put((short) (i * FLOW_MESH_SIZE + s3));
                i2 = s2 + 1;
                flowIdx.put((short) ((i + 1) * FLOW_MESH_SIZE + s3));
            }
            int s2 = i2 + 1;
            flowIdx.put((short) (((i + 1) * FLOW_MESH_SIZE + FLOW_MESH_SIZE) - 1));
            i2 = s2 + 1;
            flowIdx.put((short) ((i + 1) * FLOW_MESH_SIZE));
        }
        flowIdx.position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mFlowPosVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, flowPos.capacity() * 4, flowPos, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mFlowIndexVbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, flowIdx.capacity() * 2, flowIdx, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

        loadTextures();

        GLES30.glEnable(GLES30.GL_BLEND);
        // 原版 glBlendFunc(770, 1):770 十进制 = 0x0302 = GL_SRC_ALPHA,即 (SRC_ALPHA, ONE)。
        // 流场片段 alpha(pow 2.3 透明度)调制颜色浓淡,不能按 GL_ONE 加性处理。
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);

        updateProjection(mWidth, mHeight);
        mScene.readPrefs();
    }

    private void loadTextures() {
        if (mBgTexture != 0) GLES30.glDeleteTextures(1, new int[]{mBgTexture}, 0);
        if (mFlowTexture != 0) GLES30.glDeleteTextures(1, new int[]{mFlowTexture}, 0);
        mBgTexture = createTexture(AssetLoader.decodeBitmap(mContext, "cosmicflow/drawable/bg_grey.png"));
        mFlowTexture = createTexture(AssetLoader.decodeBitmap(mContext, "cosmicflow/drawable/flow_greyscale.png"));
    }

    private int createTexture(Bitmap bitmap) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return tex[0];
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        updateProjection(width, height);
    }

    /** 原版 onSurfaceChanged 投影:竖屏 ±aspect×±1,横屏 ±0.7×±aspect */
    private void updateProjection(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float ratio;
        if (width <= height) {
            ratio = (float) width / (float) height;
            Matrix.frustumM(mProjMatrix, 0, -ratio, ratio, -1.0f, 1.0f, 2.5f, 10.0f);
        } else {
            ratio = (float) height / (float) width;
            Matrix.frustumM(mProjMatrix, 0, -0.7f, 0.7f, -ratio, ratio, 2.5f, 10.0f);
        }
        Matrix.setLookAtM(mViewMatrix, 0, 0f, 0f, -5f, 0f, 0f, 0f, 0f, 1f, 0f);
        matrixSetUp();
    }

    /** 原版 matrixSetUp:模型 = rotateX90 × scale(6,4,7) × translate(-0.47+xOffset, -0.5, 0) */
    private void matrixSetUp() {
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.rotateM(mModelMatrix, 0, 90.0f, 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(mModelMatrix, 0, 0.0f, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(mModelMatrix, 0, 6.0f, 4.0f, 7.0f);
        Matrix.translateM(mModelMatrix, 0, -0.47f + mScene.xOffsetEff, -0.5f, 0.0f);
        Matrix.multiplyMM(mMvpMatrix, 0, mViewMatrix, 0, mModelMatrix, 0);
        Matrix.multiplyMM(mMvpMatrix, 0, mProjMatrix, 0, mMvpMatrix, 0);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setScroll(xOffset, SystemClock.uptimeMillis());
        matrixSetUp();
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mScene.setTouched(true);
            mScene.onTap(SystemClock.uptimeMillis());
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mScene.setTouched(false);
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mBgProgram == 0) {
            initGL();
        }
        if (mBgProgram == 0 || mFlowProgram == 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        float dt = mLastFrameMs < 0 ? 0.016f : (now - mLastFrameMs) / 1000.0f;
        mScene.update(Math.min(dt, 0.1f), now);

        // 原版帧限:按触摸年龄睡到目标帧间隔,首帧跳过
        float target = mScene.frameTargetMs;
        float elapsed = mLastFrameMs < 0 ? 0f : (now - mLastFrameMs);
        if (elapsed < target && !mIsFirstFrame) {
            long sleep = (long) (target - elapsed);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }
            }
        }
        mIsFirstFrame = false;
        mLastFrameMs = SystemClock.uptimeMillis();

        GLES30.glViewport(0, 0, mWidth, mHeight);
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        renderBackground();
        renderFlow();
    }

    // ---- 背景(原版 renderBackground)----

    private void renderBackground() {
        GLES30.glUseProgram(mBgProgram);
        GLES30.glUniform4f(mBgUTimeHeightScaleColorXOffset,
                mScene.animationTimeMs / 1000.0f * 0.6f,       // time
                1.0f / (mScene.noiseScale / 8.0f),              // heightScale = 8/noiseScale
                0.0f,                                           // color index(原版恒 0)
                mScene.xOffsetEff);                             // x offset
        GLES30.glUniform3f(mBgUPrimaryColor,
                mScene.primaryColor[0], mScene.primaryColor[1], mScene.primaryColor[2]);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBgTexture);
        GLES30.glUniform1i(mBgUSampler, 0);

        GLES30.glEnableVertexAttribArray(mBgATexCoord);
        mSquareTexCoord.position(0);
        GLES30.glVertexAttribPointer(mBgATexCoord, 2, GLES30.GL_FLOAT, false, 0, mSquareTexCoord);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mSquarePosVbo);
        GLES30.glEnableVertexAttribArray(mBgAPosition);
        GLES30.glVertexAttribPointer(mBgAPosition, 3, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mSquareIndexVbo);
        GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, 6, GLES30.GL_UNSIGNED_SHORT, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glDisableVertexAttribArray(mBgATexCoord);
        GLES30.glDisableVertexAttribArray(mBgAPosition);
    }

    // ---- 流场(原版 renderFlow)----

    private void renderFlow() {
        GLES30.glUseProgram(mFlowProgram);
        float t = mScene.animationTimeMs / 1000.0f;
        GLES30.glUniform3f(mFlowUTimeNoiseScaleColor,
                t * 0.6f, mScene.noiseScale, 0.0f);
        GLES30.glUniformMatrix4fv(mFlowUMvpMatrix, 1, false, mMvpMatrix, 0);
        // 噪声源点漂移(原版公式,所有频率 ×0.6)
        float noisePos0X = 0.45f + 0.7f * (0.32f * (float) Math.cos(t * 0.02f * 0.6f)
                + 0.1f * (float) Math.sin(t * 0.01f * 0.6f)
                + 0.07f * (float) Math.sin(t * 0.005f * 0.6f));
        float noisePos0Y = 0.5f + 0.7f * (0.30f * (float) Math.sin(t * 0.02f * 0.6f)
                + 0.1f * (float) Math.cos(t * 0.01f * 0.6f)
                + 0.07f * (float) Math.cos(t * 0.005f * 0.6f));
        GLES30.glUniform3f(mFlowUPrimaryColor,
                mScene.primaryColor[0], mScene.primaryColor[1], mScene.primaryColor[2]);
        GLES30.glUniform3f(mFlowUSecondaryColor,
                mScene.secondaryColor[0], mScene.secondaryColor[1], mScene.secondaryColor[2]);
        GLES30.glUniform4f(mFlowUNoisePos01, noisePos0X, noisePos0Y, 0.0f, 0.0f);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mFlowTexture);
        GLES30.glUniform1i(mFlowUSampler, 0);

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mFlowPosVbo);
        GLES30.glEnableVertexAttribArray(mFlowAPosition);
        GLES30.glVertexAttribPointer(mFlowAPosition, 3, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mFlowIndexVbo);
        GLES30.glDrawElements(GLES30.GL_TRIANGLE_STRIP, FLOW_INDICES, GLES30.GL_UNSIGNED_SHORT, 0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        GLES30.glDisableVertexAttribArray(mFlowAPosition);
    }

    @Override
    public void release() {
        GLES30.glDeleteProgram(mBgProgram);
        GLES30.glDeleteProgram(mFlowProgram);
        int[] bufs = new int[]{mSquarePosVbo, mSquareIndexVbo, mFlowPosVbo, mFlowIndexVbo};
        GLES30.glDeleteBuffers(4, bufs, 0);
        if (mBgTexture != 0) GLES30.glDeleteTextures(1, new int[]{mBgTexture}, 0);
        if (mFlowTexture != 0) GLES30.glDeleteTextures(1, new int[]{mFlowTexture}, 0);
        mBgProgram = 0;
        mFlowProgram = 0;
        mBgTexture = 0;
        mFlowTexture = 0;
        mLastFrameMs = -1;
        mIsFirstFrame = true;
    }

    // ---- 工具 ----

    private static FloatBuffer newFloatBuffer(float[] data) {
        FloatBuffer b = newFloatBuffer(data.length);
        b.put(data);
        b.position(0);
        return b;
    }

    private static FloatBuffer newFloatBuffer(int count) {
        return ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static ShortBuffer newShortBuffer(short[] data) {
        ShortBuffer b = newShortBuffer(data.length);
        b.put(data);
        b.position(0);
        return b;
    }

    private static ShortBuffer newShortBuffer(int count) {
        return ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
    }
}
