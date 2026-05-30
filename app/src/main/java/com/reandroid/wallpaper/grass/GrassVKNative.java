package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.gles.AssetLoader;

final class GrassVKNative {
    static {
        System.loadLibrary("grassvulkan");
    }

    private GrassVKNative() {
    }

    static native long nCreateRenderer(AssetManager assetManager);

    static native void nDestroyRenderer(long handle);

    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(long handle,
            float[] skyWeights,
            float[] grassMvp,
            float[] grassVerts,
            int grassVertCount,
            short[] grassIndices,
            int grassIndexCount,
            float[] sunVerts,
            int sunVertCount,
            float[] dandelionVerts,
            int dandelionVertCount,
            float[] fireflyVerts,
            int fireflyVertCount,
            float[] fireflyFlareVerts,
            int fireflyFlareVertCount,
            float[] moonVerts,
            int moonVertCount,
            float[] moonParams);

    static native void nSetSkyTexture(long handle, int slot, int[] argbPixels, int width, int height);

    static native void nSetAATexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetSpriteTexture(long handle, int slot, int[] argbPixels, int width, int height);

    static native boolean nIsVulkanSupported();

    static void uploadSkyTextures(Context context, long handle) {
        uploadSkyTexture(context, handle, 0, "grass/drawable/night.jpg");
        uploadSkyTexture(context, handle, 1, "grass/drawable/sunrise.jpg");
        uploadSkyTexture(context, handle, 2, "grass/drawable/sunset.jpg");
        uploadSkyTexture(context, handle, 3, "grass/drawable/sky.jpg");
        uploadSkyTexture(context, handle, 4, "grass/drawable/solar_eclipse.jpg");
    }

    static void uploadSpriteTextures(Context context, long handle) {
        uploadSpriteTexture(context, handle, 0, "grass/drawable/sun.png");
        uploadSpriteTexture(context, handle, 1, "grass/drawable/dandelion.png");
        uploadSpriteTexture(context, handle, 2, "grass/drawable/firefly1.png");
        uploadSpriteTexture(context, handle, 3, "grass/drawable/grass_moon.png");
        uploadSpriteTexture(context, handle, 4, "grass/drawable/firefly2.png");
    }

    static void uploadAATexture(long handle) {
        if (handle == 0L) {
            return;
        }
        int[] pixels = new int[] {
                0x00000000,
                0xFFFFFFFF,
                0xFFFFFFFF,
                0x00000000
        };
        nSetAATexture(handle, pixels, 4, 1);
    }

    private static void uploadSkyTexture(Context context, long handle, int slot, String assetPath) {
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
        nSetSkyTexture(handle, slot, pixels, width, height);

        if (argbBitmap != bitmap) {
            argbBitmap.recycle();
        }
        bitmap.recycle();
    }

    private static void uploadSpriteTexture(Context context, long handle, int slot, String assetPath) {
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
        nSetSpriteTexture(handle, slot, pixels, width, height);

        if (argbBitmap != bitmap) {
            argbBitmap.recycle();
        }
        bitmap.recycle();
    }
}
