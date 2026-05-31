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

import com.reandroid.utils.AssetLoader;

final class NexusBackgroundManager {
    private static final String TAG = "NexusBackgroundManager";

    private String lastBackgroundUri;
    private String lastBackgroundPreset;
    private float backgroundAspect = 1.0f;
    private SharedPreferences mPluginPrefs;

    void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
    }

    private String getCustomBackgroundUri(Context context) {
        if (mPluginPrefs != null) {
            String uri = mPluginPrefs.getString("pref_custom_background_uri", null);
            if (uri != null && !uri.isEmpty()) return uri;
        }
        return context.getSharedPreferences("wallpaper_prefs", 0)
                .getString("nexus_custom_background_uri", null);
    }

    int loadInitialTexture(Context context) {
        return loadBackgroundTexture(context);
    }

    int reloadIfChanged(Context context, int currentTexture) {
        if (context == null) {
            return currentTexture;
        }

        SharedPreferences prefs = mPluginPrefs != null ? mPluginPrefs
                : PreferenceManager.getDefaultSharedPreferences(context);
        String preset = prefs.getString("nexus_background_preset", "pyramid_background");
        String customUri = getCustomBackgroundUri(context);

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

        if (useCustom) {
            currentTexture = loadCustomBackgroundTexture(context, customUri);
        }
        if (currentTexture == 0) {
            currentTexture = loadBackgroundAssetTexture(context, resolvePresetAssetPath(preset));
        }

        lastBackgroundUri = customUri;
        lastBackgroundPreset = preset;
        return currentTexture;
    }

    float getBackgroundAspect() {
        return backgroundAspect;
    }

    private int loadBackgroundTexture(Context context) {
        if (context == null) {
            return 0;
        }

        String customUri = getCustomBackgroundUri(context);
        if (customUri != null) {
            int tex = loadCustomBackgroundTexture(context, customUri);
            if (tex != 0) {
                lastBackgroundUri = customUri;
                return tex;
            }
        }

        SharedPreferences prefs = mPluginPrefs != null ? mPluginPrefs
                : PreferenceManager.getDefaultSharedPreferences(context);
        String preset = prefs.getString("nexus_background_preset", "pyramid_background");
        lastBackgroundPreset = preset;
        return loadBackgroundAssetTexture(context, resolvePresetAssetPath(preset));
    }

    private String resolvePresetAssetPath(String preset) {
        if ("pyramid_background1".equals(preset)) return "nexus/drawable/pyramid_background1.png";
        if ("pyramid_background2".equals(preset)) return "nexus/drawable/pyramid_background2.png";
        if ("pyramid_background3".equals(preset)) return "nexus/drawable/pyramid_background3.png";
        if ("pyramid_background4".equals(preset)) return "nexus/drawable/pyramid_background4.png";
        return "nexus/drawable/pyramid_background.png";
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

    private int loadBackgroundAssetTexture(Context context, String assetPath) {
        Bitmap bitmap = AssetLoader.decodeBitmap(context, assetPath);
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
