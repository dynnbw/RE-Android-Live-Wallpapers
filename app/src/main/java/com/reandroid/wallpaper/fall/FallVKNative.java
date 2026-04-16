package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import com.reandroid.wallpaper.R;
import com.reandroid.settings.WallpaperSettings;

final class FallVKNative {
    static {
        System.loadLibrary("fallvulkan");
    }

    private FallVKNative() {
    }

    static native long nCreateRenderer(AssetManager assetManager);

    static native void nDestroyRenderer(long handle);

    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(long handle, float[] projectionMatrix, float[] viewMatrix,
            float[] leavesData, int leafCount, float xOffset,
            float[] waterVertices, float[] waterTexCoords, short[] waterIndices,
            int waterVertexCount, int waterIndexCount);

    static native void nSetBackgroundTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafAtlasFrameCount(long handle, int frameCount);

    static native boolean nIsVulkanSupported();

    static int uploadTextures(Context context, long handle) {
        uploadTexture(context, handle, R.drawable.pond, false);
        return uploadLeafAtlas(context, handle);
    }

    private static int uploadLeafAtlas(Context context, long handle) {
        boolean greenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
        int[] candidates;
        if (greenLeavesEnabled) {
            candidates = new int[] {
                R.drawable.leaves_0, R.drawable.leaves_1, R.drawable.leaves_2, R.drawable.leaves_3,
                R.drawable.leaves_4, R.drawable.leaves_5, R.drawable.leaves_6, R.drawable.leaves_7,
                R.drawable.leaves_8, R.drawable.leaves_9, R.drawable.leaves_10, R.drawable.leaves_11,
                R.drawable.leaves_12, R.drawable.leaves_13, R.drawable.leaves_14, R.drawable.leaves_15,
                R.drawable.leaves_16, R.drawable.leaves_17, R.drawable.leaves_18, R.drawable.leaves_19
            };
        } else {
            candidates = new int[] {
                R.drawable.leaves_0, R.drawable.leaves_1, R.drawable.leaves_2, R.drawable.leaves_3,
                R.drawable.leaves_4, R.drawable.leaves_5, R.drawable.leaves_6, R.drawable.leaves_7,
                R.drawable.leaves_8, R.drawable.leaves_9, R.drawable.leaves_10, R.drawable.leaves_11,
                R.drawable.leaves_12, R.drawable.leaves_13
            };
        }

        List<Bitmap> leafBitmaps = new ArrayList<>();
        int frameWidth = 0;
        int frameHeight = 0;

        for (int resId : candidates) {
            Bitmap bitmap = decodeArgbBitmap(context, resId);
            if (bitmap == null) {
                continue;
            }

            if (frameWidth == 0 || frameHeight == 0) {
                frameWidth = bitmap.getWidth();
                frameHeight = bitmap.getHeight();
            }

            if (bitmap.getWidth() != frameWidth || bitmap.getHeight() != frameHeight) {
                bitmap.recycle();
                continue;
            }

            leafBitmaps.add(bitmap);
        }

        if (leafBitmaps.isEmpty()) {
            uploadTexture(context, handle, R.drawable.leaves_5, true);
            nSetLeafAtlasFrameCount(handle, 1);
            return 1;
        }

        int frameCount = leafBitmaps.size();
        int atlasWidth = frameWidth * frameCount;
        int atlasHeight = frameHeight;
        int[] atlasPixels = new int[atlasWidth * atlasHeight];

        for (int i = 0; i < frameCount; i++) {
            Bitmap bitmap = leafBitmaps.get(i);
            int[] framePixels = new int[frameWidth * frameHeight];
            bitmap.getPixels(framePixels, 0, frameWidth, 0, 0, frameWidth, frameHeight);
            for (int y = 0; y < frameHeight; y++) {
                int srcOffset = y * frameWidth;
                int dstOffset = y * atlasWidth + i * frameWidth;
                System.arraycopy(framePixels, srcOffset, atlasPixels, dstOffset, frameWidth);
            }
            bitmap.recycle();
        }

        nSetLeafTexture(handle, atlasPixels, atlasWidth, atlasHeight);
        nSetLeafAtlasFrameCount(handle, frameCount);
        return frameCount;
    }

    private static Bitmap decodeArgbBitmap(Context context, int drawableRes) {
        if (context == null) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), drawableRes, options);
        if (bitmap == null) {
            return null;
        }

        if (bitmap.getConfig() == Bitmap.Config.ARGB_8888) {
            return bitmap;
        }

        Bitmap argb = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        bitmap.recycle();
        return argb;
    }

    private static int[] decodeArgbPixels(Context context, int drawableRes) {
        Bitmap argbBitmap = decodeArgbBitmap(context, drawableRes);
        if (argbBitmap == null) {
            return new int[0];
        }

        int width = argbBitmap.getWidth();
        int height = argbBitmap.getHeight();
        int[] pixels = new int[width * height];
        argbBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int visibleSamples = 0;
        int stepX = Math.max(1, width / 8);
        int stepY = Math.max(1, height / 8);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int alpha = (pixels[y * width + x] >>> 24) & 0xFF;
                if (alpha > 20) {
                    visibleSamples++;
                }
            }
        }

        argbBitmap.recycle();

        int[] packed = new int[pixels.length + 3];
        packed[0] = width;
        packed[1] = height;
        packed[2] = visibleSamples;
        System.arraycopy(pixels, 0, packed, 3, pixels.length);
        return packed;
    }

    private static void uploadTexture(Context context, long handle, int drawableRes, boolean leaf) {
        if (context == null || handle == 0L) {
            return;
        }

        int[] pixelData = decodeArgbPixels(context, drawableRes);
        if (pixelData.length < 3) {
            return;
        }

        int width = pixelData[0];
        int height = pixelData[1];
        int[] pixels = new int[width * height];
        System.arraycopy(pixelData, 3, pixels, 0, pixels.length);

        if (leaf) {
            nSetLeafTexture(handle, pixels, width, height);
        } else {
            nSetBackgroundTexture(handle, pixels, width, height);
        }
    }
}
