package com.reandroid.plugin;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.gles.GLESScene;
import com.reandroid.gles.GLESWallpaper;

/**
 * Generic WallpaperEngine that manages its own EGL context.
 * Subclasses only need to implement createScene().
 */
public abstract class BasePluginEngine implements WallpaperEngine {

    private static final String TAG = "BasePluginEngine";

    protected final Context mContext;
    protected GLESScene mScene;

    private EGLDisplay mDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mEglCreated;
    private boolean mEglCurrent;

    protected int mWidth = 256, mHeight = 256;

    public BasePluginEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        GLESWallpaper.initializeAppContext(context);
    }

    /** Create the GLESScene for this wallpaper. */
    protected abstract GLESScene createScene(int width, int height, Context context);

    @Override
    public void onCreate(SurfaceHolder holder) {}

    @Override
    public void onDestroy() {
        if (mScene != null) {
            mScene.stop();
            mScene.release();
            mScene = null;
        }
        destroyEgl();
        mCurrentSurface = null;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {}

    private Surface mCurrentSurface;

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mWidth = width > 0 ? width : 256;
        mHeight = height > 0 ? height : 256;
        Surface surface = holder.getSurface();

        if (surface == null || !surface.isValid()) return;

        // Recreate EGL if surface changed
        if (mCurrentSurface != surface) {
            if (mEglCreated) {
                if (mScene != null) { mScene.stop(); mScene.release(); mScene = null; }
                destroyEgl();
            }
            mCurrentSurface = surface;
            mEglCreated = initEgl(surface);
            mEglCurrent = false;
            if (mEglCreated) {
                mScene = createScene(mWidth, mHeight, mContext);
                mScene.init(surface, mContext.getResources(), false);
                tryInjectPrefs(mScene);
            }
        } else if (mScene != null) {
            mScene.resize(width, height);
        }
    }

    /** Override to inject plugin prefs via reflection. */
    protected void tryInjectPrefs(GLESScene scene) {}

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
                Log.e(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            // Default GL state that all wallpapers need
            try {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            } catch (Exception ignored) {}
            mScene.start();
        }

        mScene.drawFrame(timeMs);
        EGL14.eglSwapBuffers(mDisplay, mEglSurface);
    }

    @Override
    public void release() {
        if (mScene != null) {
            mScene.release();
            mScene = null;
        }
    }

    protected boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) return false;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) return false;
        int[] attribs = {EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, numConfig, 0);
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mEglContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (mEglContext == EGL14.EGL_NO_CONTEXT) return false;
        mEglSurface = EGL14.eglCreateWindowSurface(mDisplay, configs[0], surface,
                new int[]{EGL14.EGL_NONE}, 0);
        return mEglSurface != null && mEglSurface != EGL14.EGL_NO_SURFACE;
    }

    private void destroyEgl() {
        if (mDisplay != null && mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mEglSurface != null && mEglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(mDisplay, mEglSurface);
            if (mEglContext != null && mEglContext != EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(mDisplay, mEglContext);
            EGL14.eglTerminate(mDisplay);
        }
        mEglCreated = false;
        mEglCurrent = false;
        mDisplay = null;
    }
}
