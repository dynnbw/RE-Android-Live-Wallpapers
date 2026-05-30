package com.reandroid.wallpaper.nexus;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import com.reandroid.gles.AssetLoader;

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

        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        if (vs == 0 || fs == 0) {
            if (vs != 0) {
                GLES20.glDeleteShader(vs);
            }
            if (fs != 0) {
                GLES20.glDeleteShader(fs);
            }
            return null;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return null;
        }

        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return null;
        }

        Handles handles = new Handles();
        handles.program = program;
        handles.position = GLES20.glGetAttribLocation(program, "aPosition");
        handles.texCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        handles.matrix = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        handles.color = GLES20.glGetUniformLocation(program, "uColor");
        handles.texture = GLES20.glGetUniformLocation(program, "uTexture");

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return handles;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
