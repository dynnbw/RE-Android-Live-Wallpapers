package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * WallpaperEngine implementation for the Fall wallpaper.
 * Manages its own EGL context and wraps FallGL.
 * This is the pilot for Plan B: plugin-managed EGL.
 */
public class FallEngine implements WallpaperEngine {

    private static final String TAG = "FallEngine";

    private final Context mContext;
    private final WallpaperPluginHost mHost;
    private FallGL mScene;

    private EGLDisplay mDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private Thread mRenderThread;
    private volatile boolean mRunning;
    private volatile boolean mVisible;
    private Surface mSurface;

    private int mWidth = 256, mHeight = 256;

    public FallEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
    }

    // ---- WallpaperEngine lifecycle ----

    @Override
    public void onCreate(SurfaceHolder holder) {
        mSurface = holder.getSurface();
        ensureRenderThread();
    }

    @Override
    public void onDestroy() {
        stopRenderThread();
        destroyEgl();
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        mVisible = visible;
        if (visible) {
            ensureRenderThread();
        }
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width > 0 ? width : 256;
        mHeight = height > 0 ? height : 256;
        mSurface = holder.getSurface();
        if (mScene != null) {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                  int xPixels, int yPixels) {
        if (mScene != null) {
            mScene.setOffset(xOffset, yOffset, xPixels, yPixels);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) {
            mScene.onTouchEvent(event);
        }
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (mScene != null) {
            mScene.onCommand(action, x, y, z);
        }
    }

    @Override
    public void drawFrame(long timeMs) {
        if (mScene != null) {
            mScene.drawFrame(timeMs);
        }
    }

    @Override
    public void release() {
        if (mScene != null) {
            mScene.release();
            mScene = null;
        }
    }

    // ---- EGL + render thread ----

    private void ensureRenderThread() {
        if (mRenderThread != null) return;
        mRunning = true;
        mRenderThread = new Thread("FallEngineRenderer") {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                Surface surf = mSurface;
                if (surf == null || !surf.isValid()) {
                    mRunning = false;
                    return;
                }
                if (!initEgl(surf)) {
                    mRunning = false;
                    return;
                }
                mScene = new FallGL(mContext, mWidth, mHeight);
                mScene.init(surf, mContext.getResources(), false);
                mScene.start();

                while (mRunning) {
                    if (mVisible) {
                        drawFrame(System.currentTimeMillis());
                        if (!EGL14.eglSwapBuffers(mDisplay, mEglSurface)) {
                            Log.e(TAG, "eglSwapBuffers failed");
                            mRunning = false;
                            break;
                        }
                    }
                    try { Thread.sleep(16); } catch (InterruptedException ignored) {}
                }

                if (mScene != null) {
                    mScene.stop();
                    mScene.release();
                    mScene = null;
                }
            }
        };
        mRenderThread.start();
    }

    private void stopRenderThread() {
        mRunning = false;
        if (mRenderThread != null) {
            try { mRenderThread.join(1000); } catch (InterruptedException ignored) {}
            mRenderThread = null;
        }
    }

    private boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) return false;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) return false;

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, numConfig, 0);
        EGLConfig config = configs[0];

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mEglContext = EGL14.eglCreateContext(mDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (mEglContext == EGL14.EGL_NO_CONTEXT) return false;

        mEglSurface = EGL14.eglCreateWindowSurface(mDisplay, config, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (mEglSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) return false;

        return EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
    }

    private void destroyEgl() {
        if (mDisplay != null && mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (mEglSurface != null && mEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mDisplay, mEglSurface);
            }
            if (mEglContext != null && mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mDisplay, mEglContext);
            }
            EGL14.eglTerminate(mDisplay);
        }
        mDisplay = null;
    }
}
