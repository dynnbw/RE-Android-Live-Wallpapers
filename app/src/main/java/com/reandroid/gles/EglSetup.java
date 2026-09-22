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
     * 本分支要求的最低版本。
     *
     * <p>EGL 那边不需要区分小版本 —— {@code EGL_CONTEXT_CLIENT_VERSION} 只认主版本，
     * 写 3 拿到的就是驱动支持的最高 3.x。版本够不够由 manifest 声明 + 这里的运行期检查
     * 共同保证，见 {@link #meetsRequiredVersion()}。
     */
    public static final int REQUIRED_MAJOR = 3;
    public static final int REQUIRED_MINOR = 2;

    /**
     * 把 {@code GL_VERSION} 解析成 {@code {major, minor}}；解析不出来返回 null。
     *
     * <p>驱动的字符串长这样：{@code "OpenGL ES 3.2 V@0530.0"}，ANGLE 上会长得多
     * （{@code "OpenGL ES 3.2 (ANGLE 2.1...)"}），所以只取 "OpenGL ES " 之后紧邻的 X.Y，
     * 不假设后面是什么。
     */
    public static int[] parseGlVersion(String glVersion) {
        if (glVersion == null) {
            return null;
        }
        int at = glVersion.indexOf("OpenGL ES ");
        if (at < 0) {
            return null;
        }
        int i = at + "OpenGL ES ".length();
        int major = 0;
        boolean any = false;
        while (i < glVersion.length() && Character.isDigit(glVersion.charAt(i))) {
            major = major * 10 + (glVersion.charAt(i) - '0');
            i++;
            any = true;
        }
        if (!any) {
            return null;
        }
        int minor = 0;
        if (i < glVersion.length() && glVersion.charAt(i) == '.') {
            i++;
            while (i < glVersion.length() && Character.isDigit(glVersion.charAt(i))) {
                minor = minor * 10 + (glVersion.charAt(i) - '0');
                i++;
            }
        }
        return new int[]{major, minor};
    }

    /**
     * 当前上下文是否达到 {@link #REQUIRED_MAJOR}.{@link #REQUIRED_MINOR}。
     *
     * <p><b>为什么要运行期再查一次</b>：manifest 里的 {@code glEsVersion} 只挡得住应用商店。
     * 侧载的 APK 装在一台只有 ES 3.0 的机器上照样能拿到 3.0 上下文，然后 compute 着色器
     * **根本编不过**（不是报错，是那个 program 建不出来）—— 雨就静默消失了。
     * 所以这里确认一次并留下明确日志，而不是让现象去解释。
     */
    public static boolean meetsRequiredVersion() {
        try {
            int[] v = parseGlVersion(GLES30.glGetString(GLES30.GL_VERSION));
            if (v == null) {
                return false;
            }
            return v[0] > REQUIRED_MAJOR || (v[0] == REQUIRED_MAJOR && v[1] >= REQUIRED_MINOR);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 上下文已 current 时调用，把驱动报告的真实版本写进日志。
     *
     * <p>GL_VERSION 报的是驱动实现支持的最高版本；GL_SHADING_LANGUAGE_VERSION 才反映
     * 当前上下文实际开放到哪一代着色语言。两个都打出来出错时才好判断。
     */
    public static void logDriverVersion(String tag) {
        try {
            String version = GLES30.glGetString(GLES30.GL_VERSION);
            Log.i(tag, "GL_VERSION = " + version
                    + ", GLSL = " + GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
            if (!meetsRequiredVersion()) {
                // 不是致命错误但后果很隐蔽：compute 着色器编不出来，用到它的效果
                // （雨的粒子）会静默消失，看着像"这个功能没做"。日志里得说清楚。
                Log.e(tag, "本分支要求 OpenGL ES " + REQUIRED_MAJOR + "." + REQUIRED_MINOR
                        + "，当前上下文只有 " + version
                        + " —— compute / SSBO 相关的效果不会工作。"
                        + "（manifest 里的 glEsVersion 拦不住侧载安装）");
            }
        } catch (Throwable t) {
            Log.w(tag, "读取 GL_VERSION 失败", t);
        }
    }
}
