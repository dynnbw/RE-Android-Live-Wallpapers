package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.reandroid.wallpaper.gles.GLESScene;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 草地壁纸 GL 视图（适配 WallpaperService）
 */
public class GrassView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "GrassView";
    private GLESScene mGrassGL;
    private long mLastFrameTime;

    public GrassView(Context context) {
        super(context);
        init();
    }

    public GrassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 设置EGL版本（GLES2.0）
        setEGLContextClientVersion(2);
        setRenderer(this);
        // 连续渲染（动态壁纸需要）
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        // 禁用渲染线程暂停（壁纸后台运行）
        setPreserveEGLContextOnPause(true);
    }

    public void resume() {
        onResume();
    }

    public void pause() {
        onPause();
    }

    /**
     * 设置资源（必须调用，传递给 GrassGL）
     */
    public void setGrassResources(GLESScene grassGL) {
        mGrassGL = grassGL;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (mGrassGL != null) {
            mGrassGL.setResources(getResources());
            mGrassGL.start();
        }
        mLastFrameTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (mGrassGL != null) {
            mGrassGL.resize(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.currentTimeMillis();
        if (mGrassGL != null) {
            mGrassGL.drawFrame(now);
        }
        mLastFrameTime = now;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 可添加触摸交互（如点击生成风）
        return super.onTouchEvent(event);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mGrassGL != null) {
            mGrassGL.stop();
        }
    }
}