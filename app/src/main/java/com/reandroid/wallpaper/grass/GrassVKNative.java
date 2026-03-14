package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;

import com.reandroid.wallpaper.R;

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
            int grassIndexCount);

    static native void nSetSkyTexture(long handle, int slot, int[] argbPixels, int width, int height);

    static native void nSetAATexture(long handle, int[] argbPixels, int width, int height);

    static native boolean nIsVulkanSupported();

    static void uploadSkyTextures(Context context, long handle) {
        uploadSkyTexture(context, handle, 0, R.drawable.night);
        uploadSkyTexture(context, handle, 1, R.drawable.sunrise);
        uploadSkyTexture(context, handle, 2, R.drawable.sunset);
        uploadSkyTexture(context, handle, 3, R.drawable.sky);
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

    private static void uploadSkyTexture(Context context, long handle, int slot, int drawableRes) {
        if (context == null || handle == 0L) {
            return;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), drawableRes, options);
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
}
