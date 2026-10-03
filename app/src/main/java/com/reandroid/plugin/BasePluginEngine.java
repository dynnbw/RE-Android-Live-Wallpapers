package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.reandroid.gles.EglSetup;
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

    /**
     * 画布尺寸。**只有渲染线程会写**，主线程只读（日志用）。
     *
     * <p>这里曾经由 onSurfaceChanged 在主线程直接赋值并调用 mScene.resize()，
     * 而 resize() 会原地改写投影矩阵、重新分配水面网格数组 —— 渲染线程同时正在读它们。
     * 读到撕裂的矩阵或半更新的数组时，画面会被拉伸变形并在一处被截断，而且只在
     * "加载期间尺寸恰好变化"时偶发。现在尺寸变化只发布到下面这对字段，由渲染线程取走。
     */
    protected int mWidth = 256, mHeight = 256;

    /** 主线程发布的待处理尺寸；成对读写，渲染线程在 drawFrame 开头取走。 */
    private final Object mResizeLock = new Object();

    private int mRequestedWidth = 256, mRequestedHeight = 256;
    private boolean mResizePending;

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
                mHost.getSharedPreferences()
                        .registerOnSharedPreferenceChangeListener(mPrefsListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register prefs change listener", e);
            }
        }
    }

    /** Create the GLESScene for this wallpaper. */
    protected abstract GLESScene createScene(int width, int height, Context context);

    @Override
    public void onCreate(SurfaceHolder holder) {
        mHolder = holder;
        /*
         * EGL 在这里建，而不是等 onSurfaceChanged 的尺寸。
         *
         * 宿主调 onCreate 的时机对应框架的 surfaceCreated —— 那是"这个 surface 已经可用"的
         * 权威信号（AOSP 自己的壁纸也在这一步建 EGL）。尺寸要晚一步才有，而建 EGL 并不需要它：
         * 原来两件事绑在一起，于是"拿不到尺寸 → 不通知 → EGL 也不建"，实测切换壁纸时出现过
         * 39 次引擎被创建却始终没有尺寸的情况，画面就一直停在上一个壁纸的最后一帧。
         */
        Surface surface = holder != null ? holder.getSurface() : null;
        if (surface != null && surface.isValid()) {
            attachEgl(surface);
        }
    }

    /**
     * 为这个 surface 建 EGL 并记录为当前 surface。
     *
     * <p>建完不建场景 —— 场景需要尺寸，等 {@link #onSurfaceChanged} 带来。
     */
    private void attachEgl(Surface surface) {
        mCurrentSurface = surface;
        mEglCreated = initEgl(surface);
        mEglCurrent = false;
        mSceneInitPending = false;
        // 建起来了就允许下一次再报"没有 EGL"（每段缺失期只留一行日志）
        if (mEglCreated) mEglMissingLogged = false;
    }

    @Override
    public void onDestroy() {
        mHolder = null;
        if (mHost != null) {
            try {
                mHost.getSharedPreferences()
                        .unregisterOnSharedPreferenceChangeListener(mPrefsListener);
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
            mScene.stop(); // pause audio capture to save power
        }
    }

    @Override
    public void setPreview(boolean isPreview) {
        mPreview = isPreview;
    }

    private Surface mCurrentSurface;

    /** onCreate 收到的 holder；EGL 没建起来时用它判定 surface 是否可用。 */
    private SurfaceHolder mHolder;

    /** 已经为"没有 EGL"打过日志，避免每帧一行（见 drawFrame）。 */
    private boolean mEglMissingLogged;

    /** 已经为"场景还没建"打过日志；同上。 */
    private boolean mSceneMissingLogged;

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        width = width > 0 ? width : 256;
        height = height > 0 ? height : 256;
        Surface surface = holder.getSurface();

        if (surface == null || !surface.isValid()) return;

        Log.d(
                TAG,
                "onSurfaceChanged: " + width + "x" + height
                        + " eglCreated=" + mEglCreated + " surfChanged="
                        + (mCurrentSurface != surface)
                        + " oldSize=" + mWidth + "x" + mHeight);

        /*
         * 只发布尺寸，不在这里动场景：mScene.resize() 会改写投影矩阵并重建水面网格，
         * 必须留在渲染线程执行，否则会与 drawFrame 竞态（表现为画面被拉伸和截断）。
         */
        boolean sizeChanged;
        synchronized (mResizeLock) {
            sizeChanged = mRequestedWidth != width || mRequestedHeight != height;
            mRequestedWidth = width;
            mRequestedHeight = height;
            mResizePending = true;
        }

        // Skip if nothing actually changed
        if (mEglCreated && mCurrentSurface == surface && !sizeChanged) {
            Log.d(TAG, "onSurfaceChanged: skipped (nothing changed)");
            return;
        }

        /*
         * EGL 的条件是「surface 换了**或**还没建起来」。
         *
         * 只看 surface 标识会漏掉一种状态：initEgl 失败过一次之后，mCurrentSurface 已经是这个
         * surface 而 mEglCreated 仍是 false —— 此时下面那个 `!=` 不成立，EGL 就再也建不起来，
         * 而系统不会再为同一个 surface 回调。所以这里把"还没建起来"也算作需要重建。
         */
        boolean surfaceChanged = mCurrentSurface != surface;
        if (surfaceChanged || !mEglCreated) {
            if (surfaceChanged && mEglCreated) {
                if (mScene != null) {
                    mScene.stop();
                    mScene.release();
                    mScene = null;
                }
                destroyEgl();
            }
            attachEgl(surface);
        }

        // 场景可能还没建：EGL 在 onCreate（surfaceCreated）里就建好了，那时还没有尺寸
        if (mEglCreated && mScene == null) {
            mScene = createScene(width, height, mContext);
            // Defer init() until drawFrame — EGL must be current for GL calls in onCreate()
            mPendingSurface = surface;
            mPendingResources = mContext.getResources();
            mPendingPreview = mPreview;
            mSceneInitPending = true;
            tryInjectPrefs(mScene);
        }
        // 尺寸变化不在这里应用 —— drawFrame 会取走 mResizePending 再 resize
    }

    /**
     * 取走主线程发布的尺寸变化（渲染线程调用）。
     *
     * @return 是否发生了尺寸变化；为 true 时 mWidth/mHeight 已更新
     */
    private boolean consumePendingResize() {
        synchronized (mResizeLock) {
            if (!mResizePending) {
                return false;
            }
            mResizePending = false;
            if (mWidth == mRequestedWidth && mHeight == mRequestedHeight) {
                return false;
            }
            mWidth = mRequestedWidth;
            mHeight = mRequestedHeight;
            return true;
        }
    }

    /** Injects plugin SharedPreferences (and cross-plugin access) into the scene via reflection. */
    protected void tryInjectPrefs(GLESScene scene) {
        if (mHost == null || scene == null) return;
        PluginPrefsInjector.inject(
                scene,
                mHost.getSharedPreferences(),
                pluginId -> mHost.getSharedPreferences(pluginId));
    }

    @Override
    public void onOffsetsChanged(
            float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
        if (mScene != null) {
            // 步长要单独播下去：setOffset 的四个参数里没有它，而"这个桌面到底能不能滚"
            // 只有它说得清（不滚动的桌面固定上报 xOffset=0，与"两页桌面的第 1 页"无法区分）。
            mScene.setScrollStep(xStep);
            mScene.setOffset(xOffset, yOffset, xPixels, yPixels);
        }
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
        if (!mEglCreated) {
            /*
             * 只在**进入**这个状态时打一行。这里原来是每帧一行 + 补发一次 onSurfaceChanged，
             * 那套防御把日志刷到了每秒上百行，真正的原因（初始化的哪一步失败）当场就被挤出
             * 缓冲、事后无从查起。没有 EGL 就只是"还没拿到可用的 surface"，安静等着即可。
             */
            if (!mEglMissingLogged) {
                mEglMissingLogged = true;
                Log.e(TAG, "没有 EGL 上下文，跳过绘制（等下一次 surface 回调）");
            }
            return;
        }
        if (mScene == null) {
            /*
             * EGL 建好、尺寸还没来时会走到这里（见 onCreate）。同样只报一次 ——
             * 每帧一行会在等尺寸的那一小段时间里刷满日志。
             */
            if (!mSceneMissingLogged) {
                mSceneMissingLogged = true;
                Log.w(TAG, "场景还没建（等尺寸），跳过绘制");
            }
            return;
        }
        mSceneMissingLogged = false;

        /*
         * 尺寸变化在这里取走并应用 —— 场景数据(投影矩阵、水面网格)由渲染线程独占，
         * 主线程只发布不落笔，免得 drawFrame 读到撕裂的矩阵或半更新的数组。
         */
        final boolean resized = consumePendingResize();

        if (!mEglCurrent) {
            mEglCurrent = EGL14.eglMakeCurrent(mDisplay, mEglSurface, mEglSurface, mEglContext);
            if (!mEglCurrent) {
                Log.e(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                return;
            }
            Log.d(TAG, "EGL current, scene pending init=" + mSceneInitPending);
            EglSetup.logDriverVersion(TAG);
            try {
                GLES30.glClearColor(0f, 0f, 0f, 1f);
                GLES30.glEnable(GLES30.GL_BLEND);
            } catch (Exception e) {
                Log.w(TAG, "GL clear/enable failed", e);
            }

            if (mSceneInitPending) {
                Log.d(TAG, "Deferred scene.init() for " + mScene.getClass().getSimpleName());
                mScene.init(mPendingSurface, mPendingResources, mPendingPreview);
                mSceneInitPending = false;
            }

            mScene.resize(mWidth, mHeight);
            mScene.start();
            mSceneStartPending = false;
            Log.d(TAG, "Scene started: " + mScene.getClass().getSimpleName());
        } else if (resized) {
            // EGL 已 current、尺寸变了 —— 与上面那条路径互斥，不会重复 resize
            Log.d(TAG, "Applying pending resize: " + mWidth + "x" + mHeight);
            mScene.resize(mWidth, mHeight);
        }

        // 可见性恢复触发的 start() 在渲染线程执行（EGL 上下文已 current）
        if (mSceneStartPending) {
            mSceneStartPending = false;
            mScene.start();
        }

        // Always sync viewport — it may have changed due to rotation with same EGL surface
        GLES30.glViewport(0, 0, mWidth, mHeight);

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

    /**
     * 建 EGL。**每一步失败都要留痕**：这四步原来除了 chooseConfig 全是静默 return false，
     * 于是一次失败的初始化只表现为"画不出来"，原因无从查起（日志滚掉之后再也补不回来）。
     */
    protected boolean initEgl(Surface surface) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        // 优先带深度的配置，没有就退回不带深度的，不能让所有壁纸都渲染不出来。
        EGLConfig config = EglSetup.chooseConfig(mDisplay, true);
        if (config == null) config = EglSetup.chooseConfig(mDisplay, false);
        if (config == null) {
            Log.e(TAG, "eglChooseConfig failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        int[] depthBits = new int[1];
        EGL14.eglGetConfigAttrib(mDisplay, config, EGL14.EGL_DEPTH_SIZE, depthBits, 0);
        Log.i(TAG, "EGL 配置深度位数 = " + depthBits[0]);
        mEglContext = EglSetup.createContext(mDisplay, config);
        if (mEglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        /*
         * validBefore/validAfter 是给 EGL_BAD_NATIVE_WINDOW 定性的：进函数时 Surface 还标称有效，
         * 若失败后立刻变成无效，说明是"建的过程中被销毁"（竞态）；若前后都有效，则说明
         * isValid() 这个判据本身不代表底层 ANativeWindow 可用 —— 那就得换判据，而不是重试。
         */
        boolean validBefore = surface.isValid();
        mEglSurface = EGL14.eglCreateWindowSurface(
                mDisplay, config, surface, new int[] {EGL14.EGL_NONE}, 0);
        if (mEglSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(
                    TAG,
                    "eglCreateWindowSurface failed: 0x"
                            + Integer.toHexString(EGL14.eglGetError())
                            + " surf="
                            + Integer.toHexString(System.identityHashCode(surface))
                            + " validBefore="
                            + validBefore
                            + " validAfter="
                            + surface.isValid());
            return false;
        }
        return true;
    }

    private void destroyEgl() {
        if (mDisplay != null && mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                    mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mEglSurface != null && mEglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(mDisplay, mEglSurface);
            if (mEglContext != null && mEglContext != EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(mDisplay, mEglContext);
        }
        /*
         * 到这里**不要**再 eglTerminate。它作用的是 display，而 EGL_DEFAULT_DISPLAY 是整个
         * 进程共享的 —— 设置页的预览（GLESPreviewView）和这个引擎跑在同一个进程里，谁先销毁
         * 就把对方的 context / surface 一并端掉。surface 与 context 上面已经各自销毁，
         * eglTerminate 剩下的只有这个副作用（而且它本来也不保证释放 EGL 资源）。
         */
        mEglCreated = false;
        mEglCurrent = false;
        mDisplay = null;
    }
}
