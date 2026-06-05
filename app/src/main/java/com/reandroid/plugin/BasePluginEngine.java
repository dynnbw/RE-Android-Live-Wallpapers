package com.reandroid.plugin;

import android.content.Context;
import android.content.res.Resources;
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
 *
 * GL init is deferred until the render thread has a current EGL context,
 * so scene.onCreate() can safely call GL commands.
 */
public abstract class BasePluginEngine implements WallpaperEngine {

    private static final String TAG = "BasePluginEngine";

    protected final Context mContext;
    protected final WallpaperPluginHost mHost;
    protected GLESScene mScene;

    private EGLDisplay mDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mEglCreated;
    private boolean mEglCurrent;

    // Deferred-init state: stored in onSurfaceChanged, applied in drawFrame after EGL is current
    private boolean mSceneInitPending;
    private Surface mPendingSurface;
    private Resources mPendingResources;
    private boolean mPendingPreview;

    protected int mWidth = 256, mHeight = 256;
    private boolean mPreview;

    public BasePluginEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
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
        mSceneInitPending = false;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {}

    @Override
    public void setPreview(boolean isPreview) {
        mPreview = isPreview;
    }

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
            mSceneInitPending = false;
            if (mEglCreated) {
                mScene = createScene(mWidth, mHeight, mContext);
                // Defer init() until drawFrame — EGL must be current for GL calls in onCreate()
                mPendingSurface = surface;
                mPendingResources = mContext.getResources();
                mPendingPreview = mPreview;
                mSceneInitPending = true;
                tryInjectPrefs(mScene);
            }
        } else if (mScene != null) {
            mScene.resize(width, height);
        }
    }

    /** Injects plugin SharedPreferences into the scene via reflection. */
    protected void tryInjectPrefs(GLESScene scene) {
        if (mHost == null || scene == null) return;
        try {
            java.lang.reflect.Method m = scene.getClass()
                    .getMethod("setPluginPrefs", android.content.SharedPreferences.class);
            m.invoke(scene, mHost.getSharedPreferences());
        } catch (NoSuchMethodException ignored) {
            // Scene doesn't support plugin prefs — it will read from its own source
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject prefs into " + scene.getClass().getSimpleName(), e);
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
        if (!mEglCreated) { Log.w(TAG, "drawFrame skipped: EGL not created"); return; }
        if (mScene == null) { Log.w(TAG, "drawFrame skipped: scene is null"); return; }

        if (!mEglCurrent) {
            mEglCurrent = EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
            if (!mEglCurrent) {
                Log.e(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            Log.d(TAG, "EGL current, scene pending init=" + mSceneInitPending);
            try {
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glEnable(GLES20.GL_BLEND);
            } catch (Exception ignored) {}

            if (mSceneInitPending) {
                Log.d(TAG, "Deferred scene.init() for " + mScene.getClass().getSimpleName());
                mScene.init(mPendingSurface, mPendingResources, mPendingPreview);
                mSceneInitPending = false;
            }

            mScene.resize(mWidth, mHeight);
            mScene.start();
            Log.d(TAG, "Scene started: " + mScene.getClass().getSimpleName());
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
