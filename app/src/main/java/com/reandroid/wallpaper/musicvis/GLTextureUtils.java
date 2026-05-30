package com.reandroid.wallpaper.musicvis;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.gles.AssetLoader;

public final class GLTextureUtils {
    private static final String TAG = "GLTextureUtils";

    private GLTextureUtils() {}

    public static int loadTexture(Resources res, int resId) {
        return loadTexture(res, resId, true, false);
    }

    public static int loadTexture(Resources res, int resId, boolean linear, boolean repeat) {
        final int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        if (textureId == 0) return 0;

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        Bitmap bitmap = BitmapFactory.decodeResource(res, resId, options);
        if (bitmap == null) {
            GLES20.glDeleteTextures(1, textures, 0);
            return 0;
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        int minFilter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        int magFilter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        int wrap = repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        return textureId;
    }

    public static int loadTextureFromAsset(Context context, String assetPath) {
        return loadTextureFromAsset(context, assetPath, true, false);
    }

    public static int loadTextureFromAsset(Context context, String assetPath, boolean linear, boolean repeat) {
        Bitmap bitmap;
        try {
            bitmap = AssetLoader.decodeBitmap(context, assetPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode asset " + assetPath, e);
            return 0;
        }

        if (bitmap == null) {
            Log.e(TAG, "Failed to decode texture " + assetPath + " - bitmap is null");
            return 0;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        if (textureId == 0) {
            bitmap.recycle();
            return 0;
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        int minFilter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        int magFilter = linear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        int wrap = repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        Log.d(TAG, "Loaded texture " + assetPath + " -> " + textureId);
        return textureId;
    }
}
