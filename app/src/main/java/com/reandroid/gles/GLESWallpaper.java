package com.reandroid.gles;

import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.content.res.Resources;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.MotionEvent;

public abstract class GLESWallpaper extends WallpaperService {
    private static final String TAG = "GLESWallpaper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static Context sAppContext;

    public static Context getAppContext() {
        if (sAppContext == null) {
            Context resolved = resolveCurrentApplicationContext();
            if (resolved != null) {
                sAppContext = resolved;
            }
        }
        return sAppContext;
    }

    public static void initializeAppContext(Context context) {
        if (context == null) {
            return;
        }
        if (sAppContext == null) {
            Context appContext = context.getApplicationContext();
            sAppContext = appContext != null ? appContext : context;
        }
    }

    private static Context resolveCurrentApplicationContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object app = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                Context appContext = ((Context) app).getApplicationContext();
                return appContext != null ? appContext : (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public Engine onCreateEngine() {
        initializeAppContext(this);
        logD("onCreateEngine");
        try {
            GLESEngine engine = new GLESEngine();
            logD("Engine created");
            return engine;
        } catch (Exception e) {
            Log.e(TAG, "onCreateEngine失败", e);
            throw e;
        }
    }

    protected abstract GLESScene createScene(int width, int height);

    /**
     * 获取目标帧数（从SharedPreferences）
     */
    private int getTargetFrameRate() {
        try {
            SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            String value = prefs.getString("global_frame_rate", "60");
            int fps = Integer.parseInt(value);
            return Math.max(1, fps);
        } catch (Exception e) {
            Log.w("GLESWallpaper", "Failed to get frame rate setting, using default 60 FPS");
            return 60;
        }
    }

    private class GLESEngine extends Engine implements Runnable {
        private volatile Thread mThread;
        private volatile boolean mRunning = false;
        private boolean mVisible = false;
        private GLESScene mScene;
        private SurfaceHolder mHolder;
        private final Object mSceneLock = new Object();
        private long mLastResizeTimeMs = 0L;
        private int mLastWidth = -1;
        private int mLastHeight = -1;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            surfaceHolder.setSizeFromLayout();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopRenderer();
        }

        private void stopRenderer() {
            mRunning = false;
            // 捕获局部引用：渲染线程退出时会把 mThread 置 null，
            // 若直接读字段，join 期间线程退出会导致 mThread.isAlive() NPE。
            Thread thread = mThread;
            if (thread != null) {
                try { thread.join(2000); } catch (InterruptedException ignored) {}
                if (thread.isAlive()) {
                    // 超时未退出：保留引用，由渲染线程退出时自行清理，
                    // 避免旧线程未结束时又启动新线程导致并发渲染/EGL互相销毁。
                    Log.w(TAG, "Render thread did not exit within 2s");
                }
            }
            synchronized (mSceneLock) {
                if (mScene != null) {
                    mScene.stop();
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            logD("onVisibilityChanged: " + visible);
            mVisible = visible;
            if (visible) {
                startRenderer();
            } else {
                stopRenderer();
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            mHolder = holder;
            logD("onSurfaceCreated: holder准备完成");
            
            // Try to get size from surface frame
            android.graphics.Rect frame = holder.getSurfaceFrame();
            if (frame != null && (frame.width() > 0 && frame.height() > 0)) {
                int width = frame.width();
                int height = frame.height();
                logD("surface frame: " + width + "x" + height);
                
                // Create scene immediately if we have size
                synchronized (mSceneLock) {
                    if (mScene == null) {
                        mScene = createScene(width, height);
                        Resources res = getApplicationContext() != null ? getApplicationContext().getResources() : getResources();
                        mScene.init(holder.getSurface(), res, isPreview());
                        mScene.setResources(res);
                        mScene.resize(width, height);
                    }
                }
            } else {
                logD("surface frame不可用，等待onSurfaceChanged");
            }

            if (mVisible) {
                startRenderer();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            logD("onSurfaceDestroyed");
            mHolder = null;
            stopRenderer();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mHolder = holder;

            android.graphics.Rect frame = holder.getSurfaceFrame();
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                width = frame.width();
                height = frame.height();
            }
            if (width <= 0 || height <= 0) {
                logD("onSurfaceChanged: invalid size, skip resize");
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (width == mLastWidth && height == mLastHeight && (now - mLastResizeTimeMs) < 100) {
                return; // 简单防抖，避免频繁resize触发重建
            }
            mLastResizeTimeMs = now;
            mLastWidth = width;
            mLastHeight = height;

            synchronized (mSceneLock) {
                if (mScene == null) {
                    mScene = createScene(width, height);
                    // 优先使用Application资源，避免早期生命周期空指针
                    Resources res = getApplicationContext() != null ? getApplicationContext().getResources() : getResources();
                    mScene.init(holder.getSurface(), res, isPreview());
                    mScene.setResources(res);
                    mScene.resize(width, height);
                } else {
                    mScene.resize(width, height);
                }
            }

            if (mVisible) {
                startRenderer();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xStep, float yStep, int xPixels, int yPixels) {
            synchronized (mSceneLock) {
                if (mScene != null) mScene.setOffset(xOffset, yOffset, xPixels, yPixels);
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z,
                Bundle extras, boolean resultRequested) {
            synchronized (mSceneLock) {
                if (mScene != null) {
                    mScene.onCommand(action, x, y, z);
                }
            }
            return null;
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            synchronized (mSceneLock) {
                if (mScene != null) {
                    mScene.onTouchEvent(event);
                }
            }
        }

        private void startRenderer() {
            if (mThread != null) return;

            if (!mVisible) {
                logD("startRenderer: 不可见，跳过");
                return;
            }

            Surface surface = mHolder == null ? null : mHolder.getSurface();
            if (surface == null || !surface.isValid()) {
                logD("startRenderer: Surface未就绪，等待后续回调");
                return;
            }

            mRunning = true;
            mThread = new Thread(this, "GLESWallpaperThread");
            mThread.start();
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            logD("GL线程启动");

            EGLDisplay display = EGL14.EGL_NO_DISPLAY;
            EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
            EGLContext context = EGL14.EGL_NO_CONTEXT;
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (display == null || display == EGL14.EGL_NO_DISPLAY) {
                    Log.e(TAG, "eglGetDisplay failed");
                    return;
                }

                int[] version = new int[2];
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    Log.e(TAG, "eglInitialize failed");
                    return;
                }

                int[] attribList = {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfig = new int[1];
                if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfig, 0)
                        || numConfig[0] <= 0
                        || configs[0] == null) {
                    Log.e(TAG, "eglChooseConfig failed");
                    return;
                }
                EGLConfig config = configs[0];

                int[] attrib_list = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrib_list, 0);
                if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                    Log.e(TAG, "eglCreateContext failed");
                    return;
                }

                Surface surface = mHolder == null ? null : mHolder.getSurface();

                if (surface != null && surface.isValid()) {
                    int[] surfaceAttribs = {EGL14.EGL_NONE};
                    eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0);
                }

                if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "eglSurface无效，停止渲染");
                    return;
                }

                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    Log.e(TAG, "eglMakeCurrent failed");
                    return;
                }

                // **CRITICAL BUGFIX**: If mScene is still null, create it now in the GL thread
                // This can happen if onSurfaceChanged() was never called by the system
                // OR if the scene was destroyed and we're restarting the renderer
                synchronized (mSceneLock) {
                    if (mScene == null && mHolder != null) {
                        android.graphics.Rect frame = mHolder.getSurfaceFrame();
                        if (frame != null && (frame.width() > 0 && frame.height() > 0)) {
                            int width = frame.width();
                            int height = frame.height();
                            mScene = createScene(width, height);
                            Resources res = getApplicationContext() != null ? getApplicationContext().getResources() : getResources();
                            mScene.init(surface, res, isPreview());
                            mScene.setResources(res);
                            mScene.resize(width, height);
                        }
                    }
                }

                GLESScene sceneRef;
                synchronized (mSceneLock) {
                    sceneRef = mScene;
                }
                if (sceneRef != null) sceneRef.start();

                // 获取全局帧数设置
                int targetFps = getTargetFrameRate();
                long targetFrameTimeMs = 1000 / targetFps;
                logD("目标FPS: " + targetFps);

                while (mRunning) {
                    long now = System.currentTimeMillis();
                    try {
                        synchronized (mSceneLock) {
                            if (mScene != null) mScene.drawFrame(now);
                        }
                    } catch (Exception e) {
                        // 单帧异常不能杀死渲染线程，否则壁纸会永久冻结
                        Log.e(TAG, "drawFrame异常", e);
                    }
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        int error = EGL14.eglGetError();
                        Log.e(TAG, "eglSwapBuffers失败: 0x" + Integer.toHexString(error));
                        mRunning = false;
                        break;
                    }
                    long frameTime = System.currentTimeMillis() - now;
                    long sleep = Math.max(1, targetFrameTimeMs - frameTime);
                    try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
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
                cleanupEgl(display, eglSurface, context);
            }
        }
    }

    private static void cleanupEgl(EGLDisplay display, EGLSurface surface, EGLContext context) {
        if (display == null || display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface);
        }
        if (context != null && context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context);
        }
        EGL14.eglTerminate(display);
    }

    private static void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
