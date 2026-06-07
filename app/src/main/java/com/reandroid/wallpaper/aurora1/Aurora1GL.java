package com.reandroid.wallpaper.aurora1;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class Aurora1GL extends GLESScene {
    private static final String TAG = "Aurora1GL";

    private final Context mContext;
    private final Aurora1Scene mScene;
    private final float[] mModelMatrix = new float[16];
    private final float[] mMvpMatrix = new float[16];
    private final float[] mTempMatrix = new float[16];

    private boolean mGlInitialized;
    private int mProgram;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMatrixHandle;
    private int mColorHandle;
    private int mTextureHandle;
    private int mFlowHandle;
    private int mDistortHandle;
    private int mTimeHandle;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private final int[] mTextures = new int[10];
    private long mShaderStartTimeMs;

    public Aurora1GL(Context context, int width, int height) {
        super(width, height);
        mContext = context;
        mScene = new Aurora1Scene(width, height);
    }

    @Override
    protected void onCreate() {
        Log.d(TAG, "onCreate");
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mScene.resize(width, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        mScene.onTouchEvent(event);
    }

    @Override
    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        GLES20.glDeleteTextures(mTextures.length, mTextures, 0);
        for (int i = 0; i < mTextures.length; i++) {
            mTextures[i] = 0;
        }
        mGlInitialized = false;
        mShaderStartTimeMs = 0L;
    }

    @Override
    public void drawFrame(long timeMs) {
        ensureGl();
        if (!mGlInitialized) {
            return;
        }

        mScene.update(timeMs);
        Aurora1Scene.SceneData data = mScene.getSceneData();

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        mTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        if (mShaderStartTimeMs == 0L) {
            mShaderStartTimeMs = timeMs;
        }
        float shaderTimeSec = ((timeMs - mShaderStartTimeMs) % 600000L) / 1000.0f;
        GLES20.glUniform1f(mTimeHandle, shaderTimeSec);

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawSprite(data.projectionMatrix, data.background);

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE);
        for (Aurora1Scene.Sprite sprite : data.starSprites) {
            drawSprite(data.projectionMatrix, sprite);
        }
        for (Aurora1Scene.Sprite sprite : data.auroraSprites) {
            drawSprite(data.projectionMatrix, sprite);
        }
        for (int i = 0; i < data.activeParticleCount; i++) {
            drawSprite(data.projectionMatrix, data.particleSprites[i]);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }

    private void ensureGl() {
        if (mGlInitialized || mResources == null) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }

        mGlInitialized = true;
        GLES20.glClearColor(0.01f, 0.01f, 0.04f, 1.0f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);

        initBuffers();
        initProgram();
        initTextures();
    }

    private void initBuffers() {
        float[] vertices = AssetLoader.readFloatArray(mContext, "aurora1/data/aurora1_quad_pos.csv");
        float[] texCoords = AssetLoader.readFloatArray(mContext, "aurora1/data/aurora1_quad_tex.csv");
        if (vertices.length != 8 || texCoords.length != 8) {
            throw new IllegalStateException("Aurora1 quad data size mismatch");
        }
        mVertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertexBuffer.put(vertices).position(0);

        mTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mTexCoordBuffer.put(texCoords).position(0);
    }

    private void initProgram() {
        mProgram = createProgram(
                AssetLoader.readText(mContext, "aurora1/shaders/GLES/aurora1_sprite_vs.glsl"),
                AssetLoader.readText(mContext, "aurora1/shaders/GLES/aurora1_sprite_fs.glsl"));
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
        mTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
        mFlowHandle = GLES20.glGetUniformLocation(mProgram, "uFlow");
        mDistortHandle = GLES20.glGetUniformLocation(mProgram, "uDistort");
        mTimeHandle = GLES20.glGetUniformLocation(mProgram, "uTime");
    }

    private void initTextures() {
        mTextures[Aurora1Scene.TEXTURE_BG] = loadTextureFromAsset("aurora1/drawable/aurora1_bg.png");
        mTextures[Aurora1Scene.TEXTURE_BG_LANDSCAPE] = loadTextureFromAsset("aurora1/drawable/aurora1_bg_landscape.png");
        mTextures[Aurora1Scene.TEXTURE_AURORA_1] = loadTextureFromAsset("aurora1/drawable/aurora1_aurora_1.png");
        mTextures[Aurora1Scene.TEXTURE_AURORA_2] = loadTextureFromAsset("aurora1/drawable/aurora1_aurora_2.png");
        mTextures[Aurora1Scene.TEXTURE_AURORA_3] = loadTextureFromAsset("aurora1/drawable/aurora1_aurora_3.png");
        mTextures[Aurora1Scene.TEXTURE_STAR] = loadTextureFromAsset("aurora1/drawable/aurora1_star.png");
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_1] = loadTextureFromAsset("aurora1/drawable/aurora1_particle_p1_1.png");
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_2] = loadTextureFromAsset("aurora1/drawable/aurora1_particle_p1_2.png");
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_3] = loadTextureFromAsset("aurora1/drawable/aurora1_particle_p1_3.png");
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_S1] = loadTextureFromAsset("aurora1/drawable/aurora1_particle_s1.png");
    }

    private int loadTextureFromAsset(String assetPath) {
        Bitmap bitmap = null;
        try {
            bitmap = AssetLoader.decodeBitmap(mContext, assetPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode asset " + assetPath, e);
            return 0;
        }

        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture " + assetPath + " - bitmap is null");
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
        Log.d(TAG, "Loaded texture " + assetPath + " -> " + texture[0]);
        return texture[0];
    }

    private void drawSprite(float[] projectionMatrix, Aurora1Scene.Sprite sprite) {
        int textureId = mTextures[sprite.textureId];
        if (textureId == 0 || sprite.alpha <= 0.001f || sprite.width <= 0.0f || sprite.height <= 0.0f) {
            return;
        }

        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.translateM(mModelMatrix, 0, sprite.x, sprite.y, 0.0f);
        Matrix.rotateM(mModelMatrix, 0, sprite.rotationDeg, 0.0f, 0.0f, 1.0f);
        Matrix.scaleM(mModelMatrix, 0, sprite.width, sprite.height, 1.0f);
        Matrix.multiplyMM(mTempMatrix, 0, projectionMatrix, 0, mModelMatrix, 0);

        GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mTempMatrix, 0);
        GLES20.glUniform4f(mColorHandle, sprite.red, sprite.green, sprite.blue, sprite.alpha);
        GLES20.glUniform2f(mFlowHandle, sprite.flowX, sprite.flowY);
        GLES20.glUniform1f(mDistortHandle, sprite.distort);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(mTextureHandle, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }


}