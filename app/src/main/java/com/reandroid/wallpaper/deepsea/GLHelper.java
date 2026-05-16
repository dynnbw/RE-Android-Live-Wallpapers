package com.reandroid.wallpaper.deepsea;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.ETC1Util;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import com.reandroid.wallpaper.R;
import java.io.IOException;
import java.io.InputStream;

class GLHelper {

    public static int getCompiledShader(int i, String str) {
        int shader = GLES20.glCreateShader(i);
        if (shader == 0) {
            return 0;
        }
        GLES20.glShaderSource(shader, str);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, 35713, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Test", "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public static int getCreatedAndLinkedProgram(int i, int i2) {
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            return 0;
        }
        GLES20.glAttachShader(program, i);
        GLES20.glAttachShader(program, i2);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, 35714, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e("Test", "Error compiling program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(i);
            GLES20.glDeleteShader(i2);
            return 0;
        }
        return program;
    }

    public static int getTexture(Context context, int i) {
        Bitmap bitmap = null;
        int[] iArr = new int[1];
        int resolvedId = resolveResourceId(i);
        Log.d("DeepSea", "getTexture called with ID: " + i + " -> " + resolvedId);
        try {
            // 使用 BitmapFactory.decodeResource 来加载 drawable 目录中的资源
            bitmap = BitmapFactory.decodeResource(context.getResources(), resolvedId);
            if (bitmap != null) {
                Log.d("DeepSea", "Bitmap decoded successfully: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                Log.e("DeepSea", "BitmapFactory.decodeResource returned null for resource ID: " + resolvedId);
            }
            GLES20.glGenTextures(1, iArr, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iArr[0]);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10240, 9729.0f);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10241, 9729.0f);
            if (bitmap != null) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                Log.d("DeepSea", "Texture created with ID: " + iArr[0]);
            } else {
                Log.e("DeepSea", "Cannot create texture because bitmap is null");
            }
        } catch (Exception e) {
            Log.e("DeepSea", "Error loading texture: " + e.toString(), e);
        }
        if (bitmap != null) {
            bitmap.recycle();
        }
        Log.d("DeepSea", "Returning texture ID: " + iArr[0]);
        return iArr[0];
    }

    public static int getCompressedTexture(Context context, int i) {
        int[] iArr = new int[1];
        GLES20.glGenTextures(1, iArr, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iArr[0]);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10240, 9729.0f);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10241, 9729.0f);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10242, 33071.0f);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10243, 33071.0f);
        InputStream openRawResource = context.getResources().openRawResource(resolveResourceId(i));
        try {
            try {
                ETC1Util.loadTexture(GLES20.GL_TEXTURE_2D, 0, 0, 6407, 33635, openRawResource);
            } catch (IOException e) {
                Log.d("Test", "error compressing texture : " + e.toString());
                try {
                    openRawResource.close();
                } catch (IOException e2) {
                    Log.d("Test", "error closing input compressing texture : " + e2.toString());
                }
            }
            return iArr[0];
        } finally {
            try {
                openRawResource.close();
            } catch (IOException e3) {
                Log.d("Test", "error closing input compressing texture : " + e3.toString());
            }
        }
    }

    public static int getTextureByBitmap(Bitmap bitmap) {
        int[] iArr = new int[1];
        try {
            GLES20.glGenTextures(1, iArr, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iArr[0]);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10240, 9729.0f);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 10241, 9729.0f);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        } catch (Exception e) {
            Log.d("Test", e.toString() + ":" + e.getMessage() + ":" + e.getLocalizedMessage());
        }
        return iArr[0];
    }

    public static void deleteTextures(int i) {
        GLES20.glDeleteTextures(1, new int[]{i}, 0);
    }

    private static int resolveResourceId(int resId) {
        switch (resId) {
            case 2131034112:
                return R.drawable.bg_s512x512_opt;
            case 2131034113:
                return R.drawable.light_animation_0_s512x512_opt;
            case 2131034114:
                return R.drawable.light_animation_1_s512x512_opt;
            case 2131034115:
                return R.drawable.light_s512x512_opt;
            case 2131034116:
                return R.drawable.particle_mip_0;
            case 2131034117:
                return R.drawable.particle_mip_0_alpha;
            case 2131034118:
                return R.drawable.thumbnail;
            case 2131034119:
                return R.drawable.unit_a_blured_12_s256x256_mip_0;
            case 2131034120:
                return R.drawable.unit_a_blured_12_s256x256_mip_0_alpha;
            case 2131034121:
                return R.drawable.unit_a_core_glow_s256x256_eeee_mip_0;
            case 2131034122:
                return R.drawable.unit_a_core_glow_s256x256_eeee_mip_0_alpha;
            case 2131034123:
                return R.drawable.unit_a_s256x256_e_mip_0;
            case 2131034124:
                return R.drawable.unit_a_s256x256_e_mip_0_alpha;
            case 2131034125:
                return R.drawable.unit_b_blured_12_s256x256_mip_0;
            case 2131034126:
                return R.drawable.unit_b_blured_12_s256x256_mip_0_alpha;
            case 2131034127:
                return R.drawable.unit_b_core_glow_s256x256_e_mip_0;
            case 2131034128:
                return R.drawable.unit_b_core_glow_s256x256_e_mip_0_alpha;
            case 2131034129:
                return R.drawable.unit_b_s256x256_e_mip_0;
            case 2131034130:
                return R.drawable.unit_b_s256x256_e_mip_0_alpha;
            default:
                return resId;
        }
    }
}
