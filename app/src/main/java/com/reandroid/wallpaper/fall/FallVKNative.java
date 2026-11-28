package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.SkyField;

import java.util.ArrayList;
import java.util.List;

final class FallVKNative {
    static {
        System.loadLibrary("rebornvk");
    }

    private FallVKNative() {}

    static native long nCreateRenderer(AssetManager assetManager);

    static native void nDestroyRenderer(long handle);

    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(
            long handle,
            float[] lighting,
            float[] projectionMatrix,
            float[] viewMatrix,
            float[] leavesData,
            int leafCount,
            float xOffset,
            float[] waterVertices,
            float[] waterTexCoords,
            short[] waterIndices,
            int waterVertexCount,
            int waterIndexCount,
            float[] dropData,
            int dropCount,
            float glHeight,
            float bgScale,
            float meshScaleX,
            float meshScaleY,
            float dxMul,
            int rotate);

    /** 河床遮罩：单通道字节（白=天空 黑=树）。 */
    static native void nSetMaskTexture(long handle, byte[] mask, int width, int height);

    /** 河床天空：24x64 的色带像素。 */
    static native void nSetSkyTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafTexture(long handle, int[] argbPixels, int width, int height);

    static native void nSetLeafAtlasFrameCount(long handle, int frameCount);

    static native boolean nIsVulkanSupported();

    static int uploadTextures(Context context, long handle, boolean greenLeaves) {
        uploadMaskTexture(context, handle);
        uploadSkyTexture(context, handle);
        return uploadLeafAtlas(context, handle, greenLeaves);
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

    /** 四条天空色带：晨、昼、昏、夜。首次用到时解析并缓存。 */
    private static int[][] sSkyFields;

    /**
     * 河床的天空：四条 24x64 色带按 [夜,晨,昏,昼] 竖排成 24x256，只上传这一次。
     *
     * <p>混色放在水面着色器里做（权重每帧随 drop UBO 传入），所以日夜过渡是逐帧连续的，
     * 也不会每一帧都重建一次贴图。
     */
    private static void uploadSkyTexture(Context context, long handle) {
        if (context == null || handle == 0L) {
            return;
        }
        int[][] fields = loadSkyFields(context);
        if (fields == null) return;
        int width = SkyField.WIDTH;
        int height = SkyField.HEIGHT;
        int size = width * height;
        // 带的顺序必须与 FallDayNightSystem 的权重顺序 [夜,晨,昏,昼] 一致
        int[] stacked = new int[size * 4];
        System.arraycopy(fields[3], 0, stacked, 0, size);
        System.arraycopy(fields[0], 0, stacked, size, size);
        System.arraycopy(fields[2], 0, stacked, size * 2, size);
        System.arraycopy(fields[1], 0, stacked, size * 3, size);
        nSetSkyTexture(handle, stacked, width, height * 4);
    }

    private static int[][] loadSkyFields(Context context) {
        if (sSkyFields != null) return sSkyFields;
        String text = AssetLoader.readText(context, "fall/data/pond_sky_fields.txt");
        int[][] fields = new int[4][];
        fields[0] = SkyField.toArgb(SkyField.parseSection(text, "SKY_FIELD_MORNING"));
        fields[1] = SkyField.toArgb(SkyField.parseSection(text, "SKY_FIELD_DAY"));
        fields[2] = SkyField.toArgb(SkyField.parseSection(text, "SKY_FIELD_DUSK"));
        fields[3] = SkyField.toArgb(SkyField.parseSection(text, "SKY_FIELD_NIGHT"));
        for (int[] field : fields) {
            if (field == null || field.length == 0) return null;
        }
        sSkyFields = fields;
        return fields;
    }

    private static int uploadLeafAtlas(Context context, long handle, boolean greenLeavesEnabled) {
        String[] candidates;
        if (greenLeavesEnabled) {
            candidates = new String[] {
                "fall/drawable/leaves_0.png", "fall/drawable/leaves_1.png",
                        "fall/drawable/leaves_2.png", "fall/drawable/leaves_3.png",
                "fall/drawable/leaves_4.png", "fall/drawable/leaves_5.png",
                        "fall/drawable/leaves_6.png", "fall/drawable/leaves_7.png",
                "fall/drawable/leaves_8.png", "fall/drawable/leaves_9.png",
                        "fall/drawable/leaves_10.png", "fall/drawable/leaves_11.png",
                "fall/drawable/leaves_12.png", "fall/drawable/leaves_13.png",
                        "fall/drawable/leaves_14.png", "fall/drawable/leaves_15.png",
                "fall/drawable/leaves_16.png", "fall/drawable/leaves_17.png",
                        "fall/drawable/leaves_18.png", "fall/drawable/leaves_19.png"
            };
        } else {
            candidates = new String[] {
                "fall/drawable/leaves_0.png", "fall/drawable/leaves_1.png",
                        "fall/drawable/leaves_2.png", "fall/drawable/leaves_3.png",
                "fall/drawable/leaves_4.png", "fall/drawable/leaves_5.png",
                        "fall/drawable/leaves_6.png", "fall/drawable/leaves_7.png",
                "fall/drawable/leaves_8.png", "fall/drawable/leaves_9.png",
                        "fall/drawable/leaves_10.png", "fall/drawable/leaves_11.png",
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
