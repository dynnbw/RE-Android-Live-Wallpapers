package com.reandroid.wallpaper.nexus;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;

import com.reandroid.utils.AssetLoader;

final class NexusShaderProgram {
    private static final String TAG = "NexusShaderProgram";

    static final class Handles {
        int program;
        int position;
        int texCoord;
        int matrix;
        int color;
        int texture;
    }

    static Handles create(Context context, android.content.res.Resources resources) {
        String vertexShader = AssetLoader.readText(context, "nexus/shaders/GLES/nexus_vs.glsl");
        String fragmentShader = AssetLoader.readText(context, "nexus/shaders/GLES/nexus_fs.glsl");

        int vs = compile(GLES30.GL_VERTEX_SHADER, vertexShader);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            if (vs != 0) {
                GLES30.glDeleteShader(vs);
            }
            if (fs != 0) {
                GLES30.glDeleteShader(fs);
            }
            return null;
        }

        int program = GLES30.glCreateProgram();
        if (program == 0) {
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            return null;
        }

        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteProgram(program);
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            return null;
        }

        Handles handles = new Handles();
        handles.program = program;
        handles.position = GLES30.glGetAttribLocation(program, "aPosition");
        handles.texCoord = GLES30.glGetAttribLocation(program, "aTexCoord");
        handles.matrix = GLES30.glGetUniformLocation(program, "uMVPMatrix");
        handles.color = GLES30.glGetUniformLocation(program, "uColor");
        handles.texture = GLES30.glGetUniformLocation(program, "uTexture");

        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        return handles;
    }

    private static int compile(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
