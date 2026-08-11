package com.reandroid.wallpaper.cube;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.util.Log;

import com.reandroid.gles.GLESScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CubeGL extends GLESScene {
    private static final String TAG = "CubeGL";

    public static final String PREFS_NAME = CubeScene.PREFS_NAME;
    public static final String KEY_SHAPE = CubeScene.KEY_SHAPE;

    private final CubeScene mScene;

    private int mLineProgram;
    private int mLinePositionLoc;
    private int mLineColorLoc;

    private static final int CIRCLE_SEGMENTS = 32;
    private static final float TOUCH_CIRCLE_RADIUS = 0.08f;

    private boolean mInitialized;
    private float mHalfWidth;
    private float mHalfHeight;
    private float[] mLineVerts = new float[0];
    private FloatBuffer mLineBuf;
    private float[] mRingVerts = new float[CIRCLE_SEGMENTS * 2];
    private FloatBuffer mRingBuf;

    private static final String LINE_VS =
            "attribute vec2 aPosition;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "}";

    private static final String LINE_FS =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "void main() {\n" +
            "    gl_FragColor = uColor;\n" +
            "}";

    public CubeGL(int width, int height, Context context) {
        super(width, height);
        mScene = new CubeScene(context);
    }

    @Override
    protected void onCreate() {
        if (mResources == null) return;
        mScene.mScaleSize = mResources.getDisplayMetrics().densityDpi / 240f;
        mScene.ensurePrefs();
        SharedPreferences prefs = mScene.getPrefs();
        if (prefs != null) {
            String shape = prefs.getString(KEY_SHAPE, "cube");
            mScene.loadShape(shape);
        }
    }

    @Override
    public void start() {
        mScene.mStartTimeMs = System.currentTimeMillis();
    }

    @Override
    public void release() {
        if (mLineProgram != 0) {
            GLES20.glDeleteProgram(mLineProgram);
            mLineProgram = 0;
        }
        mInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        mHalfWidth = width / 2f;
        mHalfHeight = height / 2f;
        mScene.setScreenSize(width, height);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScene.setOffset(xOffset);
    }

    @Override
    public void onTouchEvent(android.view.MotionEvent event) {
        mScene.onTouchEvent(event);
    }

    public void setPluginPrefs(SharedPreferences prefs) {
        mScene.setPluginPrefs(prefs);
        if (prefs != null) {
            String shape = prefs.getString(KEY_SHAPE, "cube");
            mScene.loadShape(shape);
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        initGLIfNeeded();
        if (!mInitialized) return;

        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        drawLines(timeMs);
        drawTouchPoint();
    }

    private void initGLIfNeeded() {
        if (mInitialized || mResources == null) return;
        if (mScene.mOriginalPoints == null) {
            String shape = mScene.getPrefs() != null
                    ? mScene.getPrefs().getString(KEY_SHAPE, "cube") : "cube";
            mScene.loadShape(shape);
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        mLineProgram = createProgram(LINE_VS, LINE_FS);
        if (mLineProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            return;
        }

        mLinePositionLoc = GLES20.glGetAttribLocation(mLineProgram, "aPosition");
        mLineColorLoc = GLES20.glGetUniformLocation(mLineProgram, "uColor");

        mHalfWidth = mWidth / 2f;
        mHalfHeight = mHeight / 2f;
        mScene.setScreenSize(mWidth, mHeight);

        mInitialized = true;
    }

    private void drawLines(long timeMs) {
        mScene.rotateAndProject(timeMs);

        int lineCount = mScene.mLines.length;
        int vertCount = lineCount * 2;
        int needed = vertCount * 2;
        if (mLineVerts.length < needed) mLineVerts = new float[needed];

        for (int i = 0; i < lineCount; i++) {
            CubeScene.ThreeDLine line = mScene.mLines[i];
            float x1 = mScene.mProjectedX[line.startPoint];
            float y1 = mScene.mProjectedY[line.startPoint];
            float x2 = mScene.mProjectedX[line.endPoint];
            float y2 = mScene.mProjectedY[line.endPoint];

            int base = i * 4;
            mLineVerts[base] = x1 / mHalfWidth;
            mLineVerts[base + 1] = -y1 / mHalfHeight;
            mLineVerts[base + 2] = x2 / mHalfWidth;
            mLineVerts[base + 3] = -y2 / mHalfHeight;
        }

        if (mLineBuf == null || mLineBuf.capacity() < needed) {
            mLineBuf = createFloatBuffer(mLineVerts);
        } else {
            mLineBuf.position(0);
            mLineBuf.put(mLineVerts, 0, needed);
            mLineBuf.position(0);
        }

        GLES20.glUseProgram(mLineProgram);
        GLES20.glUniform4f(mLineColorLoc, 1f, 1f, 1f, 1f);

        GLES20.glEnableVertexAttribArray(mLinePositionLoc);
        GLES20.glVertexAttribPointer(mLinePositionLoc, 2, GLES20.GL_FLOAT, false, 0, mLineBuf);
        GLES20.glLineWidth(2f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertCount);
        GLES20.glDisableVertexAttribArray(mLinePositionLoc);
    }

    private void drawTouchPoint() {
        if (mScene.mTouchX < 0 || mScene.mTouchY < 0) return;

        float ndcX = (mScene.mTouchX / mScene.mScreenWidth) * 2f - 1f;
        float ndcY = -((mScene.mTouchY / mScene.mScreenHeight) * 2f - 1f);

        float ringRX = TOUCH_CIRCLE_RADIUS * mHalfHeight / mHalfWidth;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = i * 2.0 * Math.PI / CIRCLE_SEGMENTS;
            mRingVerts[i * 2] = ndcX + (float) Math.cos(angle) * ringRX;
            mRingVerts[i * 2 + 1] = ndcY + (float) Math.sin(angle) * TOUCH_CIRCLE_RADIUS;
        }

        if (mRingBuf == null) {
            mRingBuf = createFloatBuffer(mRingVerts);
        } else {
            mRingBuf.position(0);
            mRingBuf.put(mRingVerts);
            mRingBuf.position(0);
        }

        GLES20.glUseProgram(mLineProgram);
        GLES20.glUniform4f(mLineColorLoc, 1f, 1f, 1f, 0.6f);

        GLES20.glEnableVertexAttribArray(mLinePositionLoc);
        GLES20.glVertexAttribPointer(mLinePositionLoc, 2, GLES20.GL_FLOAT, false, 0, mRingBuf);
        GLES20.glLineWidth(2f);
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, CIRCLE_SEGMENTS);
        GLES20.glDisableVertexAttribArray(mLinePositionLoc);
    }



}
