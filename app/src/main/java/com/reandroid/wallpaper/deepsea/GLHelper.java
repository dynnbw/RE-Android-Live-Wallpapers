package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;
import com.reandroid.utils.AssetLoader;

class GLHelper {

    public static int getCompiledShader(int i, String str) {
        int shader = GLES30.glCreateShader(i);
        if (shader == 0) {
            return 0;
        }
        GLES30.glShaderSource(shader, str);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, 35713, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Test", "Error compiling shader: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static int getCreatedAndLinkedProgram(int i, int i2) {
        int program = GLES30.glCreateProgram();
        if (program == 0) {
            return 0;
        }
        GLES30.glAttachShader(program, i);
        GLES30.glAttachShader(program, i2);
        GLES30.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(program, 35714, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e("Test", "Error compiling program: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteProgram(program);
            GLES30.glDeleteShader(i);
            GLES30.glDeleteShader(i2);
            return 0;
        }
        return program;
    }

    public static int getTextureFromAsset(Context context, String assetPath) {
        int[] iArr = new int[1];
        Bitmap bitmap = null;
        try {
            bitmap = AssetLoader.decodeBitmap(context, assetPath);
            if (bitmap == null) {
                Log.e("DeepSea", "AssetLoader.decodeBitmap returned null for asset: " + assetPath);
            }
            GLES30.glGenTextures(1, iArr, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, iArr[0]);
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 10240, 9729.0f);
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 10241, 9729.0f);
            if (bitmap != null) {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
            }
        } catch (Exception e) {
            Log.e("DeepSea", "Error loading texture from asset: " + assetPath, e);
        }
        if (bitmap != null) {
            bitmap.recycle();
        }
        return iArr[0];
    }

    public static int getTextureByBitmap(Bitmap bitmap) {
        int[] iArr = new int[1];
        try {
            GLES30.glGenTextures(1, iArr, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, iArr[0]);
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 10240, 9729.0f);
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 10241, 9729.0f);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        } catch (Exception e) {
            Log.d("Test", e.toString() + ":" + e.getMessage() + ":" + e.getLocalizedMessage());
        }
        return iArr[0];
    }

    public static void deleteTextures(int i) {
        GLES30.glDeleteTextures(1, new int[]{i}, 0);
    }
}
