package com.reandroid.wallpaper.aurora2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Aurora2View extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final Aurora2GL mScene;

    public Aurora2View(Context context) {
        super(context);
        mScene = new Aurora2GL(1, 1);
        init();
    }

    public Aurora2View(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScene = new Aurora2GL(1, 1);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setPreserveEGLContextOnPause(true);
    }

    public void resume() {
        onResume();
    }

    public void pause() {
        onPause();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        mScene.setResources(getResources());
        mScene.start();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mScene.resize(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        mScene.drawFrame(System.currentTimeMillis());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScene.onTouchEvent(event);
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        mScene.stop();
    }
}
