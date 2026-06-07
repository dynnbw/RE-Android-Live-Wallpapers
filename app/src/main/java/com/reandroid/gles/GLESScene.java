package com.reandroid.gles;

import android.content.res.Resources;
import android.opengl.GLES20;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public abstract class GLESScene {

    /** Allocate a direct FloatBuffer and fill it with data (shared by all subclasses). */
    protected FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    /** Compile a GL shader. Returns 0 on failure. */
    protected int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(getClass().getSimpleName(), "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Link a GL program from vertex + fragment source. Returns 0 on failure. */
    protected int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) return 0;
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] link = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] == 0) {
            Log.e(getClass().getSimpleName(), "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }
    protected int mWidth;
    protected int mHeight;
    protected boolean mPreview;
    protected Resources mResources;
    protected Surface mSurface;

    public GLESScene(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public void init(Surface surface, Resources res, boolean isPreview) {
        mSurface = surface;
        mResources = res;
        mPreview = isPreview;
        onCreate();
    }

    // Allow engine to (re)set resources and trigger onCreate again if needed.
    public void setResources(Resources res) {
        mResources = res;
        onCreate();
    }

    public boolean isPreview() {
        return mPreview;
    }

    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public Resources getResources() { return mResources; }

    protected abstract void onCreate();

    public void start() {}
    public void stop() {}
    // 释放GL资源（在GL线程中调用）
    public void release() {}
    public void resize(int width, int height) { mWidth = width; mHeight = height; }
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {}
    public void onCommand(String action, int x, int y, int z) {}
    public void onTouchEvent(MotionEvent event) {}

    // Called each frame on GL thread
    public abstract void drawFrame(long timeMs);
}
