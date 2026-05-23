package com.reandroid.wallpaper.nexus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.reandroid.wallpaper.R;

final class NexusBackgroundManager {
    private static final String TAG = "NexusBackgroundManager";

    private String lastBackgroundUri;
    private String lastBackgroundPreset;
    private float backgroundAspect = 1.0f;

    int loadInitialTexture(android.content.res.Resources resources) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        return loadBackgroundTexture(resources, options);
    }

    int reloadIfChanged(android.content.res.Resources resources, int currentTexture) {
        Context ctx = com.reandroid.gles.GLESWallpaper.getAppContext();
        if (ctx == null || resources == null) {
            return currentTexture;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String preset = prefs.getString("nexus_background_preset", "pyramid_background");
        String customUri = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("nexus_custom_background_uri", null);

        boolean useCustom = customUri != null;
        boolean changed;
        if (useCustom) {
            changed = lastBackgroundUri == null || !customUri.equals(lastBackgroundUri);
        } else {
            changed = lastBackgroundUri != null || lastBackgroundPreset == null
                    || !preset.equals(lastBackgroundPreset);
        }

        if (!changed) {
            return currentTexture;
        }

        if (currentTexture != 0) {
            int[] tex = new int[] { currentTexture };
            GLES20.glDeleteTextures(1, tex, 0);
            currentTexture = 0;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;

        if (useCustom) {
            currentTexture = loadCustomBackgroundTexture(ctx, customUri);
        }
        if (currentTexture == 0) {
            currentTexture = loadBackgroundResourceTexture(resources, resolvePresetDrawable(preset), options);
        }

        lastBackgroundUri = customUri;
        lastBackgroundPreset = preset;
        return currentTexture;
    }

    float getBackgroundAspect() {
        return backgroundAspect;
    }

    private int loadBackgroundTexture(android.content.res.Resources resources, BitmapFactory.Options options) {
        Context ctx = com.reandroid.gles.GLESWallpaper.getAppContext();
        if (ctx == null) {
            return loadBackgroundResourceTexture(resources, R.drawable.pyramid_background, options);
        }

        String customUri = ctx.getSharedPreferences("wallpaper_prefs", 0)
                .getString("nexus_custom_background_uri", null);
        if (customUri != null) {
            int tex = loadCustomBackgroundTexture(ctx, customUri);
            if (tex != 0) {
                lastBackgroundUri = customUri;
                return tex;
            }
        }

        String preset = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("nexus_background_preset", "pyramid_background");
        lastBackgroundPreset = preset;
        return loadBackgroundResourceTexture(resources, resolvePresetDrawable(preset), options);
    }

    private int resolvePresetDrawable(String preset) {
        if ("pyramid_background1".equals(preset)) return R.drawable.pyramid_background1;
        if ("pyramid_background2".equals(preset)) return R.drawable.pyramid_background2;
        if ("pyramid_background3".equals(preset)) return R.drawable.pyramid_background3;
        if ("pyramid_background4".equals(preset)) return R.drawable.pyramid_background4;
        return R.drawable.pyramid_background;
    }

    private int loadCustomBackgroundTexture(Context ctx, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            if (uri == null) return 0;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            java.io.InputStream stream = ctx.getContentResolver().openInputStream(uri);
            if (stream == null) return 0;
            Bitmap bmp = BitmapFactory.decodeStream(stream, null, options);
            stream.close();
            if (bmp == null) return 0;

            if (bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                backgroundAspect = bmp.getWidth() / (float) bmp.getHeight();
            }

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom background", e);
            return 0;
        }
    }

    private int loadBackgroundResourceTexture(android.content.res.Resources resources,
                                              int resourceId,
                                              BitmapFactory.Options options) {
        Bitmap bitmap = BitmapFactory.decodeResource(resources, resourceId, options);
        if (bitmap == null) return 0;
        if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            backgroundAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        }

        int[] textureHandle = new int[1];
        GLES20.glGenTextures(1, textureHandle, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureHandle[0];
    }
}
