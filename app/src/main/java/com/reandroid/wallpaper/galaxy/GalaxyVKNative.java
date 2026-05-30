package com.reandroid.wallpaper.galaxy;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;
import com.reandroid.settings.WallpaperSettings;

final class GalaxyVKNative {
    static {
        System.loadLibrary("galaxyvulkan");
    }

    private GalaxyVKNative() {
    }

    static native long nCreateRenderer(AssetManager assetManager);

    static native void nDestroyRenderer(long handle);

    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(long handle, float[] mvpMatrix, float[] particlePositions,
            float[] particleColors, int particleCount, float particleAlphaMultiplier);

    static native void nSetLightTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetBackgroundTexture(long handle, int[] argbPixels, int width, int height);

    static native boolean nIsVulkanSupported();

    static void uploadLightTexture(Context context, long handle) {
        boolean useLight2 = WallpaperSettings.isGalaxyLight2Enabled(false);
        String assetPath = useLight2 ? "galaxy/drawable/light2.png" : "galaxy/drawable/light1.jpg";
        uploadTexture(context, handle, assetPath, true);
    }

    static void uploadBackgroundTexture(Context context, long handle) {
        uploadTexture(context, handle, "galaxy/drawable/galaxy_space.jpg", false);
    }

    private static void uploadTexture(Context context, long handle, String assetPath, boolean isLight) {
        if (context == null || handle == 0L) {
            return;
        }
        Bitmap bitmap = AssetLoader.decodeBitmap(context, assetPath);
        if (bitmap == null) {
            return;
        }

        Bitmap argbBitmap = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (argbBitmap == null) {
            bitmap.recycle();
            return;
        }

        int width = argbBitmap.getWidth();
        int height = argbBitmap.getHeight();
        int[] pixels = new int[width * height];
        argbBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        if (isLight) {
            nSetLightTexture(handle, pixels, width, height);
        } else {
            nSetBackgroundTexture(handle, pixels, width, height);
        }

        if (argbBitmap != bitmap) {
            argbBitmap.recycle();
        }
        bitmap.recycle();
    }
}