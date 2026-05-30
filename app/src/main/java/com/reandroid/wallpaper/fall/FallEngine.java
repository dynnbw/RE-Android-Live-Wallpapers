package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPluginHost;

/**
 * WallpaperEngine for Fall wallpaper. Manages its own EGL context.
 * drawFrame() is called from ProxyEngine's render thread.
 */
public class FallEngine implements WallpaperEngine {

    private static final String TAG = "FallEngine";

    private final Context mContext;
    private final WallpaperPluginHost mHost;
    private FallGL mScene;

    private EGLDisplay mDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mEglCreated;
    private boolean mEglCurrent;

    private int mWidth = 256, mHeight = 256;

    public FallEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
    }

    // ---- WallpaperEngine lifecycle ----

    @Override
    public void onCreate(SurfaceHolder holder) {
        // Defer EGL init to onSurfaceChanged where the surface is valid
    }

    @Override
    public void onDestroy() {
        if (mScene != null) {
            mScene.stop();
            mScene.release();
            mScene = null;
        }
        destroyEgl();
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        // No action needed
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width > 0 ? width : 256;
        mHeight = height > 0 ? height : 256;

        if (!mEglCreated) {
            Surface surface = holder.getSurface();
            if (surface != null && surface.isValid()) {
                mEglCreated = initEgl(surface);
                if (mEglCreated) {
                    mScene = new FallGL(mContext, mWidth, mHeight);
                    mScene.init(surface, mContext.getResources(), false);
                    if (mHost != null) mScene.setPluginPrefs(mHost.getSharedPreferences());
                    // start() deferred to render thread (needs EGL context)
                    Log.d(TAG, "EGL initialized, scene created");
                } else {
                    Log.e(TAG, "EGL init failed");
                }
            }
        } else if (mScene != null) {
            mScene.resize(width, height);
        }
    }

    @Override
    public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                  int xPixels, int yPixels) {
        if (mScene != null) mScene.setOffset(xOffset, yOffset, xPixels, yPixels);
    }

    @Override
    public void onTouchEvent(MotionEvent event) {
        if (mScene != null) mScene.onTouchEvent(event);
    }

    @Override
    public void onCommand(String action, int x, int y, int z, Bundle extras) {
        if (mScene != null) mScene.onCommand(action, x, y, z);
    }

    @Override
    public void drawFrame(long timeMs) {
        if (!mEglCreated || mScene == null) return;

        if (!mEglCurrent) {
            mEglCurrent = EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
            if (!mEglCurrent) {
                Log.e(TAG, "eglMakeCurrent failed on render thread: 0x"
                        + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            mScene.start();
            Log.d(TAG, "EGL context bound, scene started");
        }

        mScene.drawFrame(timeMs);
        boolean ok = EGL14.eglSwapBuffers(mDisplay, mEglSurface);
        if (!ok) {
            int err = EGL14.eglGetError();
            Log.e(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(err));
        }
    }

    @Override
    public void release() {
        if (mScene != null) {
            mScene.release();
            mScene = null;
        }
    }

    // ---- EGL ----

    private boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) return false;

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) return false;

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, numConfig, 0);

        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mEglContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (mEglContext == EGL14.EGL_NO_CONTEXT) return false;

        mEglSurface = EGL14.eglCreateWindowSurface(mDisplay, configs[0], surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (mEglSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) return false;

        // Defer makeCurrent to render thread (first drawFrame)
        return true;
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
        mEglCreated = false;
        mEglCurrent = false;
        mDisplay = null;
    }
}
