package com.reandroid.gles;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
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
    private final Object mSceneLock = new Object();
    private volatile Thread mThread;
    private volatile boolean mRunning;
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private EGLSurface mSurface;
    private volatile int mPendingWidth;
    private volatile int mPendingHeight;

    public GLESPreviewView(Context context, SceneFactory factory) {
        super(context);
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
        synchronized (mSceneLock) {
            if (mScene != null) {
                mScene.resize(width, height);
            } else {
                mPendingWidth = width;
                mPendingHeight = height;
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        synchronized (mSceneLock) {
            if (mScene != null) {
                mScene.onTouchEvent(event);
                return true;
            }
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

    public void stopRenderer() {
        mRunning = false;
        // 捕获局部引用：渲染线程退出时会把 mThread 置 null，
        // 若直接读字段，join 期间线程退出会导致 mThread.isAlive() NPE。
        Thread thread = mThread;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            if (thread.isAlive()) {
                // 超时未退出：保留引用，由渲染线程退出时自行清理并销毁EGL，
                // 避免旧线程未结束时又启动新线程导致并发渲染/双重释放。
                Log.w(TAG, "Render thread did not exit within 1s");
            }
        }
        synchronized (mSceneLock) {
            if (mScene != null) {
                mScene.stop();
            }
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
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

            // 预览跟随全局帧率设置（与桌面引擎一致），每秒重读一次
            long lastFpsCheckMs = 0;
            long targetFrameMs = 33L;
            while (mRunning) {
                long now = System.currentTimeMillis();
                if (now - lastFpsCheckMs >= 1000L) {
                    lastFpsCheckMs = now;
                    int fps = com.reandroid.settings.WallpaperSettings.getGlobalFrameRate(30);
                    targetFrameMs = Math.max(1L, 1000L / Math.max(1, fps));
                }
                try {
                    synchronized (mSceneLock) {
                        scene = mScene;
                        if (scene != null) {
                            scene.drawFrame(now);
                        } else {
                            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                        }
                    }
                } catch (Exception e) {
                    // 单帧异常不能杀死渲染线程，否则预览会永久冻结
                    Log.e(TAG, "drawFrame failed", e);
                }
                if (!EGL14.eglSwapBuffers(mDisplay, mSurface)) {
                    int error = EGL14.eglGetError();
                    Log.e(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(error));
                    mRunning = false;
                    break;
                }
                long frameCost = System.currentTimeMillis() - now;
                long sleepMs = Math.max(1L, targetFrameMs - frameCost);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                }
            }
        } finally {
            // 仅当当前线程仍持有槽位时才释放场景并清理线程引用：
            // 避免旧线程晚退时清掉新线程正在使用的场景，或覆盖新线程的引用。
            if (mThread == Thread.currentThread()) {
                GLESScene sceneToRelease;
                synchronized (mSceneLock) {
                    sceneToRelease = mScene;
                    mScene = null;
                }
                if (sceneToRelease != null) {
                    sceneToRelease.stop();
                    sceneToRelease.release();
                }
                mThread = null;
            }
            // EGL 始终由渲染线程自身销毁，避免 stopRenderer 在 UI 线程
            // 与仍在运行的渲染线程并发操作同一 display。
            destroyEgl();
        }
    }

    private boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) return false;

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) return false;

        // 优先带深度的配置，没有就退回不带深度的，不能让壁纸直接渲染不出来。
        EGLConfig config = EglSetup.chooseConfig(mDisplay, true);
        if (config == null) config = EglSetup.chooseConfig(mDisplay, false);
        if (config == null) return false;

        mContext = EglSetup.createContext(mDisplay, config);
        if (mContext == EGL14.EGL_NO_CONTEXT) return false;

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        mSurface = EGL14.eglCreateWindowSurface(mDisplay, config, surface, surfaceAttribs, 0);
        if (mSurface == null || mSurface == EGL14.EGL_NO_SURFACE) return false;

        boolean current = EGL14.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
        if (current) EglSetup.logDriverVersion(TAG);
        return current;
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
