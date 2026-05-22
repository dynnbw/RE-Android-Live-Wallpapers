package com.reandroid.wallpaper.musicvis;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

public final class GLTextureUtils {
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
}
