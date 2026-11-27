package com.reandroid.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

public final class GLTextureUtils {
    private static final String TAG = "GLTextureUtils";

    private GLTextureUtils() {}

    public static int loadTextureFromAsset(Context context, String assetPath) {
        return loadTextureFromAsset(context, assetPath, true, false);
    }

    /** Load with GL_NEAREST min + premultiplied alpha. For windmill/weather wallpapers
     *  that use GL_ONE blend matching original GLES 1.x fixed-function behavior. */
    public static int loadTextureNearestPremult(Context context, String assetPath) {
        return loadTextureEx(context, assetPath, false, true, false, true, false);
    }

    /**
     * GL_LINEAR 双线性 + mipmap + 预乘 alpha。
     *
     * <p>给写实向的贴图用：素材分辨率远高于屏幕上的实际尺寸，NEAREST 缩小时会闪烁
     * （风车的叶片和立柱就是这种情况，原素材小是当年性能妥协，不是像素风）。
     */
    public static int loadTextureLinearMipmapPremult(Context context, String assetPath) {
        return loadTextureEx(context, assetPath, true, true, false, true, true);
    }

    private static int loadTextureEx(
            Context context,
            String assetPath,
            boolean minLinear,
            boolean magLinear,
            boolean repeat,
            boolean premultAlpha,
            boolean mipmap) {
        Bitmap bitmap;
        try {
            if (premultAlpha) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPremultiplied = true;
                bitmap = AssetLoader.decodeBitmapWithOptions(context, assetPath, opts);
            } else {
                bitmap = AssetLoader.decodeBitmap(context, assetPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode asset " + assetPath, e);
            return 0;
        }
        if (bitmap == null) return 0;

        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        if (textureId == 0) {
            bitmap.recycle();
            return 0;
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                minLinear
                        ? (mipmap ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR)
                        : GLES30.GL_NEAREST);
        GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                magLinear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST);
        int wrap = repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        if (mipmap) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        }
        bitmap.recycle();
        return textureId;
    }

    public static int loadTextureFromAsset(
            Context context, String assetPath, boolean linear, boolean repeat) {
        return loadTextureFromAsset(
                context, assetPath, linear, repeat ? GLES30.GL_REPEAT : GLES30.GL_CLAMP_TO_EDGE);
    }

    /**
     * 同上，采样方式直接给 GL 的 wrap 常量。
     *
     * <p>需要 {@code GL_MIRRORED_REPEAT} 的贴图走这个：素材两端对不齐、又必须平铺时，
     * 镜像铺法在接缝处两边是同一列像素，看不出分界。
     */
    public static int loadTextureFromAsset(
            Context context, String assetPath, boolean linear, int wrap) {
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
        GLES30.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        if (textureId == 0) {
            bitmap.recycle();
            return 0;
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        int minFilter = linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
        int magFilter = linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        Log.d(TAG, "Loaded texture " + assetPath + " -> " + textureId);
        return textureId;
    }
}
