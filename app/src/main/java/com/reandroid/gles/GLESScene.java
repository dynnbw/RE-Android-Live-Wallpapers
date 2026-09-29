package com.reandroid.gles;

import android.content.res.Resources;
import android.opengl.GLES30;
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
        return GlProgramUtils.compileShader(getClass().getSimpleName(), type, source);
    }

    /** Link a GL program from vertex + fragment source. Returns 0 on failure. */
    protected int createProgram(String vertexSource, String fragmentSource) {
        return GlProgramUtils.createProgram(getClass().getSimpleName(), vertexSource, fragmentSource);
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

    /** 桌面能不能随屏滚动；见 {@link #setScrollStep(float)}。 */
    private boolean mLauncherScrolls = true;

    /**
     * 桌面能不能随屏滚动（{@code xStep <= 0} 表示不能）。
     *
     * <p>国产 ROM 的默认桌面基本都不支持随屏滚动，实测（澎湃 3，小米桌面）它会回调
     * {@code onOffsetsChanged} 并把步长报成 <b>-1</b>、"宿主没有声明虚拟屏数量"，
     * 同时偏移固定为 0。画面编排若依赖偏移量，照单全收就会偏到一边去。
     *
     * <p>存下来供 {@link #canScrollLauncher()} 取用；不关心它的场景什么都不用做。
     */
    public void setScrollStep(float xStep) {
        mLauncherScrolls = xStep > 0.0f;
    }

    /**
     * 桌面是否支持随屏滚动。默认 {@code true} —— 拿不到这个信息时维持原行为，
     * 只有明确"桌面不滚动"时才该走回退。
     */
    protected boolean canScrollLauncher() {
        return mLauncherScrolls;
    }
    public void onCommand(String action, int x, int y, int z) {}
    public void onTouchEvent(MotionEvent event) {}

    // Called each frame on GL thread
    public abstract void drawFrame(long timeMs);
}
