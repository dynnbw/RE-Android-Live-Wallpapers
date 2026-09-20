package com.reandroid.gles;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.util.Log;

/**
 * EGL 上下文创建与运行时 GL 版本记录。
 *
 * <p>设备一律先申请 OpenGL ES 3 上下文，失败再退回 ES 2。现有 shader 全部没有
 * {@code #version} 指令(即 #version 100)，在 ES 3 上下文中同样合法，所以升级上下文
 * 对现有壁纸是透明的；新壁纸则可以直接使用 {@code #version 300 es} 与 HDR 中间缓冲。
 *
 * <p>退回分支不是新增的代码路径——它就是本来一直在跑的那条，只是现在排在了第二位。
 *
 * <p>版本是设备属性，用一个静态值在渲染线程和 UI 线程之间共享。需要在不依赖壁纸
 * 是否在跑的场景(如设置页列壁纸)判断版本时，先调用 {@link #probe()}。
 */
public final class GlCapabilities {

    private static final String TAG = "GlCapabilities";

    /**
     * EGL_OPENGL_ES3_BIT_KHR。EGL14 没有定义这个常量(EGL 1.5 里叫 EGL_OPENGL_ES3_BIT)，
     * 来自 EGL_KHR_create_context，Android 上始终可用。
     */
    public static final int EGL_OPENGL_ES3_BIT = 0x40;

    /** 已建成的上下文主版本号(2 或 3)；0 表示还没建过、也不知道设备能力。 */
    private static volatile int sMajorVersion;

    private GlCapabilities() {
    }

    /**
     * 选一个能渲染目标版本的 EGLConfig：先试 ES3 位，拿不到再退 ES2 位。
     *
     * @param wantDepth 优先带 16 位深度缓冲。三维内容(Earth)需要它来遮挡背面；
     *                  其余场景本来就显式 glDisable(GL_DEPTH_TEST)，带深度对它们是惰性的。
     * @return 找不到任何可用配置时返回 null
     */
    public static EGLConfig chooseConfig(EGLDisplay display, boolean wantDepth) {
        EGLConfig config = chooseConfig(display, wantDepth, EGL_OPENGL_ES3_BIT);
        if (config == null) {
            config = chooseConfig(display, wantDepth, EGL14.EGL_OPENGL_ES2_BIT);
        }
        return config;
    }

    private static EGLConfig chooseConfig(EGLDisplay display, boolean wantDepth, int renderableType) {
        int[] attribs = wantDepth
                ? new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 16,
                        EGL14.EGL_RENDERABLE_TYPE, renderableType, EGL14.EGL_NONE}
                : new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, renderableType, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfig = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0)) {
            return null;
        }
        if (numConfig[0] <= 0 || configs[0] == null) {
            return null;
        }
        return configs[0];
    }

    /**
     * 建上下文：先要 ES3，拿不到退回 ES2，两种都失败返回 {@link EGL14#EGL_NO_CONTEXT}。
     *
     * <p>成功后记录拿到的主版本号。版本是设备属性，壁纸列表据此决定要不要显示
     * 声明了 minGlVersion 的壁纸。
     */
    public static EGLContext createContext(EGLDisplay display, EGLConfig config) {
        EGLContext context = tryCreateContext(display, config, 3);
        if (context != null && context != EGL14.EGL_NO_CONTEXT) {
            sMajorVersion = 3;
            return context;
        }
        context = tryCreateContext(display, config, 2);
        if (context != null && context != EGL14.EGL_NO_CONTEXT) {
            sMajorVersion = 2;
            return context;
        }
        Log.e(TAG, "eglCreateContext failed (ES3 与 ES2 都试过): 0x"
                + Integer.toHexString(EGL14.eglGetError()));
        return EGL14.EGL_NO_CONTEXT;
    }

    private static EGLContext tryCreateContext(EGLDisplay display, EGLConfig config, int clientVersion) {
        int[] attribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE};
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0);
    }

    /**
     * 主动探测设备能力——用于壁纸还没跑起来、但需要判断版本的场景(设置页列壁纸)。
     * 已经知道版本时直接返回，所以重复调用没有代价。探测用的上下文随即销毁。
     */
    public static synchronized void probe() {
        if (sMajorVersion > 0) {
            return;
        }
        try {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == null || display == EGL14.EGL_NO_DISPLAY) {
                return;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return;
            }
            EGLConfig config = chooseConfig(display, false);
            if (config == null) {
                return;
            }
            EGLContext context = createContext(display, config);
            if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
            // 不调用 eglTerminate：display 是进程级的，可能与正在运行的壁纸共用。
        } catch (Throwable t) {
            Log.w(TAG, "GL 能力探测失败", t);
        }
    }

    /** 当前生效的主版本号(2 或 3)；尚未探测且未建过上下文时返回 0。 */
    public static int getMajorVersion() {
        return sMajorVersion;
    }

    /** 是否已经拿到 OpenGL ES 3 上下文。 */
    public static boolean isEs3() {
        return sMajorVersion >= 3;
    }

    /**
     * 上下文已 current 时调用，把驱动报告的真实版本写进日志。
     *
     * <p>GL_VERSION 报的是驱动实现支持的最高版本，强制 ES2 时它可能仍报 3.x——
     * 上下文是否真的受限要看 GLSL 版本(ES2 上下文只能是 1.00)。两个都打出来才能分辨。
     */
    public static void logDriverVersion(String tag) {
        try {
            Log.i(tag, "GL_VERSION = " + GLES20.glGetString(GLES20.GL_VERSION)
                    + ", GLSL = " + GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
                    + " (client = ES" + sMajorVersion + ")");
        } catch (Throwable t) {
            Log.w(tag, "读取 GL_VERSION 失败", t);
        }
    }
}
