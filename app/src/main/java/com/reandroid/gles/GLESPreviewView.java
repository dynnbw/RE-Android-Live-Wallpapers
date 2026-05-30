package com.reandroid.gles;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.os.Process;
import android.view.MotionEvent;

public class GLESPreviewView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final String TAG = "GLESPreviewView";
    private static final boolean DEBUG = android.util.Log.isLoggable("GLESPreviewView", android.util.Log.DEBUG);
    public interface SceneFactory {
        GLESScene create(int width, int height);
    }

    private final SceneFactory mFactory;
    private volatile GLESScene mScene;
    private Thread mThread;
    private boolean mRunning;
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private EGLSurface mSurface;
    private int mPendingWidth;
    private int mPendingHeight;

    public GLESPreviewView(Context context, SceneFactory factory) {
        super(context);
        mFactory = factory;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        getHolder().addCallback(this);
    }

    public GLESPreviewView(Context context, AttributeSet attrs, SceneFactory factory) {
        super(context, attrs);
        mFactory = factory;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        getHolder().addCallback(this);
    }

    /**
     * 获取当前的Scene对象
     */
    public GLESScene getScene() {
        return mScene;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startRenderer(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mScene != null) {
            mScene.resize(width, height);
        } else {
            mPendingWidth = width;
            mPendingHeight = height;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mScene != null) {
            mScene.onTouchEvent(event);
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void startRenderer(SurfaceHolder holder) {
        if (mThread != null) return;
        int width = holder.getSurfaceFrame() != null ? holder.getSurfaceFrame().width() : 0;
        int height = holder.getSurfaceFrame() != null ? holder.getSurfaceFrame().height() : 0;
        
        // 如果SurfaceFrame尺寸无效，尝试从View本身获取（可能需要等待布局完成）
        if (width <= 0 || height <= 0) {
            width = getWidth();
            height = getHeight();
        }
        
        // 最后的备选方案：使用最小可用尺寸
        if (width <= 0) width = 256;
        if (height <= 0) height = 256;

        mPendingWidth = width;
        mPendingHeight = height;
        
        if (DEBUG) {
            android.util.Log.d("GLESPreviewView", "startRenderer: width=" + width + ", height=" + height);
        }
        
        mScene = mFactory.create(width, height);

        mRunning = true;
        mThread = new Thread(this, "GLESPreviewThread");
        mThread.start();
    }

    private void stopRenderer() {
        mRunning = false;
        if (mThread != null) {
            try { mThread.join(1000); } catch (InterruptedException ignored) {}
            mThread = null;
        }
        if (mScene != null) {
            mScene.stop();
            mScene.release();
            mScene = null;
        }
        destroyEgl();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Surface surface = getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            mRunning = false;
            return;
        }
        if (!initEgl(surface)) {
            mRunning = false;
            return;
        }

        GLESScene scene = mScene;
        if (scene != null) {
            int width = mPendingWidth > 0 ? mPendingWidth : getWidth();
            int height = mPendingHeight > 0 ? mPendingHeight : getHeight();
            if (width <= 0) width = 256;
            if (height <= 0) height = 256;
            scene.init(surface, getResources(), true);
            scene.setResources(getResources());
            scene.resize(width, height);
            scene.start();
        }

        while (mRunning) {
            long now = System.currentTimeMillis();
            scene = mScene;
            if (scene != null) {
                scene.drawFrame(now);
            } else {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            if (!EGL14.eglSwapBuffers(mDisplay, mSurface)) {
                int error = EGL14.eglGetError();
                Log.e(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(error));
                mRunning = false;
                break;
            }
            try {
                Thread.sleep(33);
            } catch (InterruptedException ignored) {
            }
        }

        scene = mScene;
        if (scene != null) {
            scene.stop();
        }
    }

    private boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) return false;

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) return false;

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        EGL14.eglChooseConfig(mDisplay, attribList, 0, configs, 0, 1, numConfig, 0);
        if (numConfig[0] == 0) return false;
        EGLConfig config = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mContext = EGL14.eglCreateContext(mDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mContext == EGL14.EGL_NO_CONTEXT) return false;

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        mSurface = EGL14.eglCreateWindowSurface(mDisplay, config, surface, surfaceAttribs, 0);
        if (mSurface == null || mSurface == EGL14.EGL_NO_SURFACE) return false;

        return EGL14.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
    }

    private void destroyEgl() {
        if (mDisplay != null && mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mSurface != null && mSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mDisplay, mSurface);
            }
            if (mContext != null && mContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mDisplay, mContext);
            }
            EGL14.eglTerminate(mDisplay);
        }
        mSurface = null;
        mContext = null;
        mDisplay = null;
    }
}
