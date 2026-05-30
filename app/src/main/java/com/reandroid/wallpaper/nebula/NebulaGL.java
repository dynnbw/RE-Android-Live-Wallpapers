package com.reandroid.wallpaper.nebula;

import android.opengl.GLES20;
import android.util.Log;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;
import android.content.Context;
import com.reandroid.utils.AssetLoader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class NebulaGL extends GLESScene {
    private static final String TAG = "NebulaGL";

    private final Context mContext;

    private int mProgram;
    private int mPositionHandle;
    private int mResolutionHandle;
    private int mTimeHandle;
    private int mOffsetHandle;
    private int mBrightnessHandle;
    private int mQualityHandle;

    private FloatBuffer mQuadBuffer;
    private float mXOffset = 0.5f;
    private boolean mInitialized;

    public NebulaGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
    }

    @Override
    protected void onCreate() {
        if (mQuadBuffer == null) {
            float[] quad = {
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f
            };
            ByteBuffer bb = ByteBuffer.allocateDirect(quad.length * 4);
            bb.order(ByteOrder.nativeOrder());
            mQuadBuffer = bb.asFloatBuffer();
            mQuadBuffer.put(quad);
            mQuadBuffer.position(0);
        }
    }

    @Override
    public void start() {
        initIfNeeded();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mXOffset = xOffset;
    }

    @Override
    public void drawFrame(long timeMs) {
        initIfNeeded();
        if (!mInitialized) {
            return;
        }

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);

        GLES20.glUniform2f(mResolutionHandle, Math.max(1, mWidth), Math.max(1, mHeight));
        GLES20.glUniform1f(mTimeHandle, timeMs * 0.001f);
        GLES20.glUniform2f(mOffsetHandle, mXOffset, 0.0f);
        GLES20.glUniform1f(mBrightnessHandle, mPreview ? 1.08f : 1.0f);
        GLES20.glUniform1f(mQualityHandle, mPreview ? 0.85f : 1.0f);

        mQuadBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mQuadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }

    @Override
    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        mInitialized = false;
    }

    private void initIfNeeded() {
        if (mInitialized || mResources == null) {
            return;
        }

        String vertexSource = AssetLoader.readText(mContext, "nebula/shaders/GLES/nebula_vs.glsl");
        String fragmentSource = AssetLoader.readText(mContext, "nebula/shaders/GLES/nebula_fs.glsl");
        mProgram = createProgram(vertexSource, fragmentSource);
        if (mProgram == 0) {
            Log.e(TAG, "Unable to create shader program");
            return;
        }

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mResolutionHandle = GLES20.glGetUniformLocation(mProgram, "uResolution");
        mTimeHandle = GLES20.glGetUniformLocation(mProgram, "uTime");
        mOffsetHandle = GLES20.glGetUniformLocation(mProgram, "uOffset");
        mBrightnessHandle = GLES20.glGetUniformLocation(mProgram, "uBrightness");
        mQualityHandle = GLES20.glGetUniformLocation(mProgram, "uQuality");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0.01f, 0.01f, 0.03f, 1.0f);
        mInitialized = true;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
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
            Log.e(TAG, "Shader compilation failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}