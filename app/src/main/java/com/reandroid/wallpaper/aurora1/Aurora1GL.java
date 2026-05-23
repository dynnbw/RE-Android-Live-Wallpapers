package com.reandroid.wallpaper.aurora1;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import com.reandroid.gles.RawResourceLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class Aurora1GL extends GLESScene {
    private static final String TAG = "Aurora1GL";

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

    public Aurora1GL(int width, int height) {
        super(width, height);
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
        float[] vertices = RawResourceLoader.readRawFloatArray(mResources, R.raw.aurora1_quad_pos);
        float[] texCoords = RawResourceLoader.readRawFloatArray(mResources, R.raw.aurora1_quad_tex);
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
                RawResourceLoader.readRawText(mResources, R.raw.aurora1_sprite_vs),
                RawResourceLoader.readRawText(mResources, R.raw.aurora1_sprite_fs));
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
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;

        mTextures[Aurora1Scene.TEXTURE_BG] = loadTexture(R.drawable.aurora1_bg, options);
        mTextures[Aurora1Scene.TEXTURE_BG_LANDSCAPE] = loadTexture(R.drawable.aurora1_bg_landscape, options);
        mTextures[Aurora1Scene.TEXTURE_AURORA_1] = loadTexture(R.drawable.aurora1_aurora_1, options);
        mTextures[Aurora1Scene.TEXTURE_AURORA_2] = loadTexture(R.drawable.aurora1_aurora_2, options);
        mTextures[Aurora1Scene.TEXTURE_AURORA_3] = loadTexture(R.drawable.aurora1_aurora_3, options);
        mTextures[Aurora1Scene.TEXTURE_STAR] = loadTexture(R.drawable.aurora1_star, options);
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_1] = loadTexture(R.drawable.aurora1_particle_p1_1, options);
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_2] = loadTexture(R.drawable.aurora1_particle_p1_2, options);
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_P1_3] = loadTexture(R.drawable.aurora1_particle_p1_3, options);
        mTextures[Aurora1Scene.TEXTURE_PARTICLE_S1] = loadTexture(R.drawable.aurora1_particle_s1, options);
    }

    private int loadTexture(int resId, BitmapFactory.Options options) {
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeResource(mResources, resId, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode resource " + resId, e);
            return 0;  // 返回0让drawSprite跳过这个精灵
        }
        
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture " + resId + " - bitmap is null");
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
        Log.d(TAG, "Loaded texture " + resId + " -> " + texture[0]);
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

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Aurora1 program link failed: " + info);
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Aurora1 shader compile failed: " + info);
        }
        return shader;
    }
}