package com.reandroid.gles;

import android.content.res.Resources;
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
