package com.reandroid.wallpaper.galaxy4;

import android.content.Context;
import android.content.res.AssetManager;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;
import com.reandroid.settings.WallpaperSettings;

final class Galaxy4VKNative {
    static { System.loadLibrary("galaxy4vulkan"); }

    private Galaxy4VKNative() {}

    static native long nCreateRenderer(AssetManager assetManager);
    static native void nDestroyRenderer(long handle);
    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);
    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);
    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(long handle, float[] projectionMatrix,
            float[] spaceClouds, float[] bgStars, float[] staticStars,
            int spaceCloudCount, int bgStarCount, float timeSeconds,
            float particleSize, float particleOpacity);

    static native void nSetBackgroundTexture(long handle, int[] argbPixels, int width, int height);
    static native void nSetCloudTexture(long handle, int[] argbPixels, int width, int height);
    static native void nSetStaticStarTextures(long handle,
            int[] tex1Pixels, int w1, int h1,
            int[] tex2Pixels, int w2, int h2);

    static native boolean nIsVulkanSupported();

    static int uploadTextures(Context context, long handle) {
        uploadPackedTexture(context, handle, "galaxy4/drawable/galaxy4_bg.jpg", false);
        uploadPackedTexture(context, handle, "galaxy4/drawable/galaxy4_cloud.png", true);
        uploadStaticStarTextures(context, handle);
        return 0;
    }

    private static void uploadStaticStarTextures(Context context, long handle) {
        int[] pix1 = decodePixels(context, "galaxy4/drawable/galaxy4_staticstar.png");
        int[] pix2 = decodePixels(context, "galaxy4/drawable/galaxy4_staticstar2.png");
        if (pix1.length >= 3 && pix2.length >= 3) {
            int w1 = pix1[0], h1 = pix1[1], w2 = pix2[0], h2 = pix2[1];
            int[] d1 = new int[w1 * h1], d2 = new int[w2 * h2];
            System.arraycopy(pix1, 3, d1, 0, d1.length);
            System.arraycopy(pix2, 3, d2, 0, d2.length);
            nSetStaticStarTextures(handle, d1, w1, h1, d2, w2, h2);
        }
    }

    private static void uploadPackedTexture(Context context, long handle, String path, boolean cloud) {
        int[] packed = decodePixels(context, path);
        if (packed.length < 3) return;
        int w = packed[0], h = packed[1];
        int[] pixels = new int[w * h];
        System.arraycopy(packed, 3, pixels, 0, pixels.length);
        if (cloud) nSetCloudTexture(handle, pixels, w, h);
        else nSetBackgroundTexture(handle, pixels, w, h);
    }

    private static int[] decodePixels(Context context, String assetPath) {
        android.graphics.Bitmap bitmap = AssetLoader.decodeBitmap(context, assetPath);
        if (bitmap == null) return new int[0];
        android.graphics.Bitmap argb = bitmap.getConfig() == android.graphics.Bitmap.Config.ARGB_8888
                ? bitmap : bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
        if (argb != bitmap) bitmap.recycle();
        int w = argb.getWidth(), h = argb.getHeight();
        int[] pixels = new int[w * h + 3];
        pixels[0] = w; pixels[1] = h; pixels[2] = 0;
        argb.getPixels(pixels, 3, w, 0, 0, w, h);
        argb.recycle();
        return pixels;
    }
}
