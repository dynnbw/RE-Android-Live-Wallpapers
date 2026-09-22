package com.reandroid.gles;

import android.opengl.GLES30;
import android.util.Log;

/**
 * 着色器编译与链接。
 *
 * <p>从 {@link GLESScene} 里抽出来的 —— 那两个 helper 是 {@code protected}，
 * 而不属于 Scene 的类（如 {@link GlowRenderer}）用不了。
 *
 * <p>函数体与抽取前**逐字相同**，只把日志 tag 从 {@code getClass().getSimpleName()}
 * 换成调用方传入的值 —— 那是唯一依赖实例的地方。
 */
public final class GlProgramUtils {

    private GlProgramUtils() {
    }

    /** Compile a GL shader. Returns 0 on failure. */
    public static int compileShader(String tag, int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(tag, "Shader compile failed: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Link a GL program from vertex + fragment source. Returns 0 on failure. */
    public static int createProgram(String tag, String vertexSource, String fragmentSource) {
        int vs = compileShader(tag, GLES30.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(tag, GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) return 0;
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);
        int[] link = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0);
        if (link[0] == 0) {
            Log.e(tag, "Program link failed: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteProgram(program);
            return 0;
        }
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return program;
    }
}
