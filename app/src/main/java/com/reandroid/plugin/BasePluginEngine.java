package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
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

    // start() 延迟标记：由可见性变化置位，在渲染线程 drawFrame 中消费，
    // 保证场景 GL 初始化始终在 EGL 上下文 current 的线程上执行。
    private volatile boolean mSceneStartPending;

    protected int mWidth = 256, mHeight = 256;
    private boolean mPreview;

    /**
     * 插件设置变更 → 重注入场景。部分场景在 setPluginPrefs 时缓存值到字段，
     * 没有此监听器的话改动要等引擎重建（重启/换壁纸）才生效。
     * 监听器在主线程触发（设置页写入线程），setPluginPrefs 各实现均为纯字段写入，安全。
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> {
                if (mScene != null) {
                    tryInjectPrefs(mScene);
                }
            };

    public BasePluginEngine(Context context, WallpaperPluginHost host) {
        mContext = context;
        mHost = host;
        GLESWallpaper.initializeAppContext(context);
        if (mHost != null) {
            try {
                mHost.getSharedPreferences().registerOnSharedPreferenceChangeListener(mPrefsListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register prefs change listener", e);
            }
        }
    }

    /** Create the GLESScene for this wallpaper. */
    protected abstract GLESScene createScene(int width, int height, Context context);

    @Override
    public void onCreate(SurfaceHolder holder) {}

    @Override
    public void onDestroy() {
        if (mHost != null) {
            try {
                mHost.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister prefs change listener", e);
            }
        }
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
    public void onVisibilityChanged(boolean visible) {
        if (mScene == null) return;
        if (visible) {
            // start() 不能在此线程(主线程)直接调用：EGL 上下文尚未 current，
            // 场景的 GL 初始化(program/纹理)会全部失败(如 FallGL 着色器编译失败)，
            // 且纹理加载会阻塞主线程导致壁纸加载缓慢。延迟到渲染线程 drawFrame 执行。
            mSceneStartPending = true;
        } else {
            mScene.stop();            // pause audio capture to save power
        }
    }

    @Override
    public void setPreview(boolean isPreview) {
        mPreview = isPreview;
    }

    private Surface mCurrentSurface;

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        width = width > 0 ? width : 256;
        height = height > 0 ? height : 256;
        Surface surface = holder.getSurface();

        if (surface == null || !surface.isValid()) return;

        Log.d(TAG, "onSurfaceChanged: " + width + "x" + height
                + " eglCreated=" + mEglCreated + " surfChanged=" + (mCurrentSurface != surface)
                + " oldSize=" + mWidth + "x" + mHeight);

        // Skip if nothing actually changed
        if (mEglCreated && mCurrentSurface == surface && mWidth == width && mHeight == height) {
            Log.d(TAG, "onSurfaceChanged: skipped (nothing changed)");
            return;
        }

        mWidth = width;
        mHeight = height;

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
        } catch (NoSuchMethodException e) {
            Log.i(TAG, scene.getClass().getSimpleName() + " has no setPluginPrefs — using default prefs source");
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
            } catch (Exception e) { Log.w(TAG, "GL clear/enable failed", e); }

            if (mSceneInitPending) {
                Log.d(TAG, "Deferred scene.init() for " + mScene.getClass().getSimpleName());
                mScene.init(mPendingSurface, mPendingResources, mPendingPreview);
                mSceneInitPending = false;
            }

            mScene.resize(mWidth, mHeight);
            mScene.start();
            mSceneStartPending = false;
            Log.d(TAG, "Scene started: " + mScene.getClass().getSimpleName());
        }

        // 可见性恢复触发的 start() 在渲染线程执行（EGL 上下文已 current）
        if (mSceneStartPending) {
            mSceneStartPending = false;
            mScene.start();
        }

        // Always sync viewport — it may have changed due to rotation with same EGL surface
        GLES20.glViewport(0, 0, mWidth, mHeight);

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
        if (!EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, numConfig, 0)
                || numConfig[0] <= 0
                || configs[0] == null) {
            Log.e(TAG, "eglChooseConfig failed");
            return false;
        }
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
