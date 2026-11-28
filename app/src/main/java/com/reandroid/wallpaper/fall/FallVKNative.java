package com.reandroid.wallpaper.fall;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;
import com.reandroid.utils.SkyField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * 每个渲染器上一次真正上传的权重（顺序 [夜,晨,昏,昼]）与时间，用来节流。
     *
     * <p>必须按 handle 分开：壁纸用真实时间、预览用压缩时间，两个渲染器可能同时在跑，
     * 共用一个“上次权重”会让一方误以为自己的天空已经是最新的。
     */
    private static final Map<Long, float[]> sLastSkyWeights = new HashMap<>();

    private static final Map<Long, Long> sLastSkyUploadMs = new HashMap<>();

    /** 河床的天空：从 pond_sky_fields.txt 生成 24x64 色带；初始用黄昏，之后按权重混。 */
    private static void uploadSkyTexture(Context context, long handle) {
        if (context == null || handle == 0L) {
            return;
        }
        int[][] fields = loadSkyFields(context);
        if (fields == null) return;
        nSetSkyTexture(handle, fields[2], SkyField.WIDTH, SkyField.HEIGHT);
        sLastSkyWeights.put(handle, new float[] {0f, 0f, 1f, 0f});
        sLastSkyUploadMs.put(handle, 0L);
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

    /**
     * 按权重把四条色带预混成一张 24x64 色带再上传。
     *
     * @param weights 顺序 [夜,晨,昏,昼]，与 {@code FallDayNightSystem} 一致
     */
    static void updateSkyTexture(long handle, float[] weights, long nowMs) {
        if (handle == 0L || weights == null || weights.length < 4) return;
        int[][] fields = sSkyFields;
        if (fields == null) return;
        if (!skyWeightsChanged(handle, weights)) return;
        Long lastMs = sLastSkyUploadMs.get(handle);
        if (lastMs != null && nowMs - lastMs < 250L) return;
        sLastSkyUploadMs.put(handle, nowMs);

        float night = weights[0];
        float morning = weights[1];
        float dusk = weights[2];
        float day = weights[3];
        int count = SkyField.WIDTH * SkyField.HEIGHT;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int a = blendChannel(fields, i, 24, night, morning, dusk, day);
            int r = blendChannel(fields, i, 16, night, morning, dusk, day);
            int g = blendChannel(fields, i, 8, night, morning, dusk, day);
            int b = blendChannel(fields, i, 0, night, morning, dusk, day);
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        nSetSkyTexture(handle, out, SkyField.WIDTH, SkyField.HEIGHT);
        sLastSkyWeights.put(handle, weights.clone());
    }

    private static boolean skyWeightsChanged(long handle, float[] weights) {
        float[] last = sLastSkyWeights.get(handle);
        if (last == null) return true;
        // 权重变化很小的时候不重传（色带是 24x64，4Hz 已经足够平滑）
        return Math.abs(last[0] - weights[0]) > 0.004f
                || Math.abs(last[1] - weights[1]) > 0.004f
                || Math.abs(last[2] - weights[2]) > 0.004f
                || Math.abs(last[3] - weights[3]) > 0.004f;
    }

    private static int blendChannel(
            int[][] fields,
            int index,
            int shift,
            float night,
            float morning,
            float dusk,
            float day) {
        float value = ((fields[3][index] >>> shift) & 0xFF) * night
                + ((fields[0][index] >>> shift) & 0xFF) * morning
                + ((fields[2][index] >>> shift) & 0xFF) * dusk
                + ((fields[1][index] >>> shift) & 0xFF) * day;
        int result = Math.round(value);
        return result < 0 ? 0 : Math.min(result, 255);
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
