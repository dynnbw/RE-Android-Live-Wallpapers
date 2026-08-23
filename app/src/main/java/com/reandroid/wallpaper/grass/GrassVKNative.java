package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;

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
            float[] starWhiteVerts,
            int starWhiteVertCount,
            float[] starWarmVerts,
            int starWarmVertCount,
            float[] starCoolVerts,
            int starCoolVertCount,
            float[] starYellowVerts,
            int starYellowVertCount,
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
        // 天空渐变与 GL 版同源：grass_sky_fields.txt 程序数据生成（无静态星星），
        // 日食背景保留 jpg 贴图（GL 版同样使用该文件）。
        String text = AssetLoader.readText(context, "grass/data/grass_sky_fields.txt");
        if (text != null) {
            uploadSkyField(context, handle, 0, text, "SKY_FIELD_NIGHT");
            uploadSkyField(context, handle, 1, text, "SKY_FIELD_SUNRISE");
            uploadSkyField(context, handle, 2, text, "SKY_FIELD_SUNSET");
            uploadSkyField(context, handle, 3, text, "SKY_FIELD_DAY");
        }
        uploadSkyTexture(context, handle, 4, "grass/drawable/solar_eclipse.jpg");
    }

    static void uploadSpriteTextures(Context context, long handle) {
        uploadSpriteTexture(context, handle, 0, "grass/drawable/sun.png");
        uploadSpriteTexture(context, handle, 1, "grass/drawable/dandelion.png");
        uploadSpriteTexture(context, handle, 2, "grass/drawable/firefly1.png");
        uploadSpriteTexture(context, handle, 3, "grass/drawable/grass_moon.png");
        uploadSpriteTexture(context, handle, 4, "grass/drawable/firefly2.png");
        // 动态星星：4 种纯色 1x1 纹理（slot 5-8），颜色与 GL 版 GrassStarRenderer 一致
        uploadSolidTexture(handle, 5, (byte) 255, (byte) 255, (byte) 255, (byte) 255); // white
        uploadSolidTexture(handle, 6, (byte) 255, (byte) 168, (byte) 152, (byte) 255); // warm
        uploadSolidTexture(handle, 7, (byte) 158, (byte) 202, (byte) 255, (byte) 255); // cool
        uploadSolidTexture(handle, 8, (byte) 255, (byte) 238, (byte) 170, (byte) 255); // yellow
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

    /** Upload a 24x64 sky texture generated from grass_sky_fields.txt section data. */
    private static void uploadSkyField(Context context, long handle, int slot, String allText, String sectionName) {
        if (context == null || handle == 0L) {
            return;
        }
        int[][] fieldColors = GrassTextureUtils.parseSkyFieldSection(allText, sectionName);
        if (fieldColors == null) {
            return;
        }
        int[] pixels = GrassTextureUtils.skyFieldToARGB(fieldColors);
        if (pixels == null) {
            return;
        }
        nSetSkyTexture(handle, slot, pixels, 24, 64);
    }

    /** Upload a 1x1 solid color sprite texture (used for dynamic stars). */
    private static void uploadSolidTexture(long handle, int slot, byte r, byte g, byte b, byte a) {
        if (handle == 0L) {
            return;
        }
        int argb = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        nSetSpriteTexture(handle, slot, new int[]{argb}, 1, 1);
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

    // ---- dynamic night stars (Vulkan path) ----

    /** SpriteVertex format: {x, y, u, v, a} — 5 floats per vertex, 6 vertices per star quad. */
    private static final int STAR_FLOATS_PER_VERTEX = 5;
    private static final int STAR_FLOATS_PER_QUAD = 6 * STAR_FLOATS_PER_VERTEX;

    /** 4 tint groups; counts are in VERTICES (matches getXxxVertexCount() semantics). */
    static final class StarBatches {
        float[] white = new float[0];
        int whiteCount = 0;
        float[] warm = new float[0];
        int warmCount = 0;
        float[] cool = new float[0];
        int coolCount = 0;
        float[] yellow = new float[0];
        int yellowCount = 0;
    }

    /**
     * Build per-tint star quad batches for the Vulkan path.
     * Matches the GL path: same NightStarsLayer logic, same tint grouping,
     * same starVisibility scaling — each vertex carries its own alpha.
     * The given StarBatches is reused across frames to avoid per-frame allocation.
     */
    static StarBatches buildStarBatches(NightStarsLayer layer, SceneData sd, int width, int height,
            StarBatches batches) {
        final StarBatches b = batches != null ? batches : new StarBatches();
        b.whiteCount = 0;
        b.warmCount = 0;
        b.coolCount = 0;
        b.yellowCount = 0;
        if (layer == null || sd == null || sd.starVisibility <= 0.001f) {
            return b;
        }
        layer.draw(sd.animNowMs, width, height, new NightStarsLayer.SpriteDrawer() {
            @Override
            public void draw(int tintType, float cx, float cy, float size, float alpha, float shift) {
                float finalAlpha = alpha * sd.starVisibility;
                if (finalAlpha <= 0.001f) {
                    return;
                }
                int group = starTextureGroupForTint(tintType, shift);
                float[] arr;
                int floatCursor;
                if (group == 1) {
                    arr = b.warm; floatCursor = b.warmCount * STAR_FLOATS_PER_VERTEX;
                } else if (group == 2) {
                    arr = b.cool; floatCursor = b.coolCount * STAR_FLOATS_PER_VERTEX;
                } else if (group == 3) {
                    arr = b.yellow; floatCursor = b.yellowCount * STAR_FLOATS_PER_VERTEX;
                } else {
                    arr = b.white; floatCursor = b.whiteCount * STAR_FLOATS_PER_VERTEX;
                }
                // appendStarQuad may grow the array; keep the returned reference
                arr = appendStarQuad(arr, floatCursor, cx, cy, size, finalAlpha);
                int newCount = floatCursor / STAR_FLOATS_PER_VERTEX + 6;
                if (group == 1) {
                    b.warm = arr; b.warmCount = newCount;
                } else if (group == 2) {
                    b.cool = arr; b.coolCount = newCount;
                } else if (group == 3) {
                    b.yellow = arr; b.yellowCount = newCount;
                } else {
                    b.white = arr; b.whiteCount = newCount;
                }
            }
        });
        return b;
    }

    /** Same tint-grouping as GrassStarRenderer.textureGroupForTint (0=white 1=warm 2=cool 3=yellow). */
    private static int starTextureGroupForTint(int tintType, float shift) {
        switch (tintType) {
            case NightStarsLayer.STAR_TINT_RED:
                return shift > 0.6f ? 1 : 3;
            case NightStarsLayer.STAR_TINT_BLUE:
                return shift > 0.45f ? 2 : 0;
            case NightStarsLayer.STAR_TINT_YELLOW:
                return shift > 0.35f ? 3 : 0;
            case NightStarsLayer.STAR_TINT_WHITE:
            default:
                return 0;
        }
    }

    /** Appends one star quad; returns the (possibly grown) array. */
    private static float[] appendStarQuad(float[] arr, int cursor, float cx, float cy, float size, float alpha) {
        int required = cursor + STAR_FLOATS_PER_QUAD;
        if (arr.length < required) {
            int newSize = arr.length == 0 ? 4096 : arr.length;
            while (newSize < required) {
                newSize *= 2;
            }
            float[] expanded = new float[newSize];
            if (cursor > 0) {
                System.arraycopy(arr, 0, expanded, 0, cursor);
            }
            arr = expanded;
        }

        float half = size * 0.5f;
        float x0 = cx - half, y0 = cy - half;
        float x1 = cx - half, y1 = cy + half;
        float x2 = cx + half, y2 = cy + half;
        float x3 = cx + half, y3 = cy - half;

        cursor = putStarVertex(arr, cursor, x0, y0, 0.0f, 1.0f, alpha);
        cursor = putStarVertex(arr, cursor, x1, y1, 0.0f, 0.0f, alpha);
        cursor = putStarVertex(arr, cursor, x2, y2, 1.0f, 0.0f, alpha);
        cursor = putStarVertex(arr, cursor, x0, y0, 0.0f, 1.0f, alpha);
        cursor = putStarVertex(arr, cursor, x2, y2, 1.0f, 0.0f, alpha);
        cursor = putStarVertex(arr, cursor, x3, y3, 1.0f, 1.0f, alpha);
        return arr;
    }

    private static int putStarVertex(float[] out, int cursor, float x, float y, float u, float v, float a) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = u;
        out[cursor++] = v;
        out[cursor++] = a;
        return cursor;
    }
}
