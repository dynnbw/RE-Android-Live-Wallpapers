package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.SkyField;
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
            int waterVertexCount, int waterIndexCount,
            float[] dropData, int dropCount,
            float glHeight, float bgScale, float meshScaleX, float meshScaleY,
            float dxMul, int rotate);

    /** 河床遮罩：单通道字节（白=天空 黑=树）。 */
    static native void nSetMaskTexture(long handle, byte[] mask, int width, int height);

    /** 河床天空：24x64 的色带像素。 */
    static native void nSetSkyTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafAtlasFrameCount(long handle, int frameCount);

    static native boolean nIsVulkanSupported();

    static int uploadTextures(Context context, long handle) {
        uploadMaskTexture(context, handle);
        uploadSkyTexture(context, handle);
        return uploadLeafAtlas(context, handle);
    }

    /** 河床的树：白底黑图 -> 单通道遮罩。 */
    private static void uploadMaskTexture(Context context, long handle) {
        if (context == null || handle == 0L) {
            return;
        }
        AssetLoader.Mask mask = AssetLoader.decodeMask(context, "fall/drawable/pond_mask.png");
        if (mask == null || mask.pixels.length == 0) {
            return;
        }
        nSetMaskTexture(handle, mask.pixels, mask.width, mask.height);
    }

    /** 河床的天空：从 pond_sky_fields.txt 生成 24x64 色带。 */
    private static void uploadSkyTexture(Context context, long handle) {
        if (context == null || handle == 0L) {
            return;
        }
        String text = AssetLoader.readText(context, "fall/data/pond_sky_fields.txt");
        int[] argb = SkyField.toArgb(SkyField.parseSection(text, "SKY_FIELD_DUSK"));
        if (argb == null || argb.length == 0) {
            return;
        }
        nSetSkyTexture(handle, argb, SkyField.WIDTH, SkyField.HEIGHT);
    }

    private static int uploadLeafAtlas(Context context, long handle) {
        boolean greenLeavesEnabled = WallpaperSettings.isGreenLeavesEnabled(false);
        String[] candidates;
        if (greenLeavesEnabled) {
            candidates = new String[] {
                "fall/drawable/leaves_0.png", "fall/drawable/leaves_1.png", "fall/drawable/leaves_2.png", "fall/drawable/leaves_3.png",
                "fall/drawable/leaves_4.png", "fall/drawable/leaves_5.png", "fall/drawable/leaves_6.png", "fall/drawable/leaves_7.png",
                "fall/drawable/leaves_8.png", "fall/drawable/leaves_9.png", "fall/drawable/leaves_10.png", "fall/drawable/leaves_11.png",
                "fall/drawable/leaves_12.png", "fall/drawable/leaves_13.png", "fall/drawable/leaves_14.png", "fall/drawable/leaves_15.png",
                "fall/drawable/leaves_16.png", "fall/drawable/leaves_17.png", "fall/drawable/leaves_18.png", "fall/drawable/leaves_19.png"
            };
        } else {
            candidates = new String[] {
                "fall/drawable/leaves_0.png", "fall/drawable/leaves_1.png", "fall/drawable/leaves_2.png", "fall/drawable/leaves_3.png",
                "fall/drawable/leaves_4.png", "fall/drawable/leaves_5.png", "fall/drawable/leaves_6.png", "fall/drawable/leaves_7.png",
                "fall/drawable/leaves_8.png", "fall/drawable/leaves_9.png", "fall/drawable/leaves_10.png", "fall/drawable/leaves_11.png",
                "fall/drawable/leaves_12.png", "fall/drawable/leaves_13.png"
            };
        }

        List<Bitmap> leafBitmaps = new ArrayList<>();
        int frameWidth = 0;
        int frameHeight = 0;

        for (String assetPath : candidates) {
            Bitmap bitmap = decodeArgbBitmap(context, assetPath);
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
            uploadLeafTexture(context, handle, "fall/drawable/leaves_5.png");
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

    private static Bitmap decodeArgbBitmap(Context context, String assetPath) {
        if (context == null) {
            return null;
        }

        Bitmap bitmap = AssetLoader.decodeBitmap(context, assetPath);
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

    private static int[] decodeArgbPixels(Context context, String assetPath) {
        Bitmap argbBitmap = decodeArgbBitmap(context, assetPath);
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

    private static void uploadLeafTexture(Context context, long handle, String assetPath) {
        if (context == null || handle == 0L) {
            return;
        }

        int[] pixelData = decodeArgbPixels(context, assetPath);
        if (pixelData.length < 3) {
            return;
        }

        int width = pixelData[0];
        int height = pixelData[1];
        int[] pixels = new int[width * height];
        System.arraycopy(pixelData, 3, pixels, 0, pixels.length);

        nSetLeafTexture(handle, pixels, width, height);
    }
}
