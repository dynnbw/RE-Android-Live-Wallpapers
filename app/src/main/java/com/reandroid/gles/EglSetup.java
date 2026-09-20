package com.reandroid.gles;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES30;
import android.util.Log;

/**
 * ES3 专用的 EGL 上下文创建。
 *
 * <p>本分支不再支持 OpenGL ES 2：所有着色器都是 {@code #version 300 es}，在 ES2 上下文里
 * 根本编译不过，所以上下文一律申请 ES3、**不做回退**。设备够不够格由 AndroidManifest 里的
 * {@code <uses-feature android:glEsVersion="0x00030000" android:required="true">} 决定——
 * 应用商店据此过滤，装得上的机器就有 ES3。
 */
public final class EglSetup {

    private static final String TAG = "EglSetup";

    /**
     * EGL_OPENGL_ES3_BIT_KHR。EGL14 没有定义这个常量(EGL 1.5 里叫 EGL_OPENGL_ES3_BIT)，
     * 来自 EGL_KHR_create_context，Android 上始终可用。
     */
    public static final int EGL_OPENGL_ES3_BIT = 0x40;

    private EglSetup() {
    }

    /**
     * 选一个能渲染 ES3 的 EGLConfig。
     *
     * @param wantDepth 优先带 16 位深度缓冲。三维内容(Earth)需要它来遮挡背面；
     *                  其余场景本来就显式 glDisable(GL_DEPTH_TEST)，带深度对它们是惰性的。
     * @return 没有可用配置时返回 null
     */
    public static EGLConfig chooseConfig(EGLDisplay display, boolean wantDepth) {
        int[] attribs = wantDepth
                ? new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_DEPTH_SIZE, 16,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE}
                : new int[]{EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE};
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
     * 建 ES3 上下文。失败返回 {@link EGL14#EGL_NO_CONTEXT}——没有回退路径。
     */
    public static EGLContext createContext(EGLDisplay display, EGLConfig config) {
        int[] attribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext(ES3) failed: 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
            return EGL14.EGL_NO_CONTEXT;
        }
        return context;
    }

    /**
     * 上下文已 current 时调用，把驱动报告的真实版本写进日志。
     *
     * <p>GL_VERSION 报的是驱动实现支持的最高版本；GL_SHADING_LANGUAGE_VERSION 才反映
     * 当前上下文实际开放到哪一代着色语言。两个都打出来出错时才好判断。
     */
    public static void logDriverVersion(String tag) {
        try {
            Log.i(tag, "GL_VERSION = " + GLES30.glGetString(GLES30.GL_VERSION)
                    + ", GLSL = " + GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
        } catch (Throwable t) {
            Log.w(tag, "读取 GL_VERSION 失败", t);
        }
    }
}
