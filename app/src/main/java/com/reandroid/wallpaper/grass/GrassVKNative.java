package com.reandroid.wallpaper.grass;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

import com.reandroid.utils.AssetLoader;

final class GrassVKNative {
    static {
        System.loadLibrary("rebornvk");
    }

    private GrassVKNative() {}

    static native long nCreateRenderer(AssetManager assetManager);

    static native void nDestroyRenderer(long handle);

    static native boolean nOnSurfaceCreated(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceChanged(long handle, Surface surface, int width, int height);

    static native void nOnSurfaceDestroyed(long handle);

    static native void nRenderFrame(
            long handle,
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
            float[] moonParams,
            float[] grassLightParams,
            float[] fireflyLights,
            float[] glowParams,
            boolean glowEnabled,
            float[] weatherTone,
            float[] weatherBack,
            float[] weatherFront,
            boolean proceduralSun,
            float[] sunParams);

    static native void nSetSkyTexture(
            long handle, int slot, int[] argbPixels, int width, int height);

    static native void nSetAATexture(long handle, int[] argbPixels, int width, int height);

    /** 程序化太阳的 ASTC 块数据：slot 0/1/2 = sun_ramp / annulus_ramp / rays。 */
    static native void nSetSunTexture(long handle, int slot, byte[] payload, int width, int height);

    static native void nSetSpriteTexture(
            long handle, int slot, int[] argbPixels, int width, int height);

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

        // 天气图层（槽位 9..22）与 GLES 的 GrassWeatherRenderer 同一批资源
        uploadSpriteTexture(context, handle, 9, "grass/drawable/grass_rain_streak.png");
        uploadSpriteTexture(context, handle, 10, "grass/drawable/grass_weather_snow_01.png");
        uploadSpriteTexture(context, handle, 11, "grass/drawable/grass_weather_snow_02.png");
        uploadSpriteTexture(context, handle, 12, "grass/drawable/grass_weather_snow_03.png");
        uploadSpriteTexture(context, handle, 13, "grass/drawable/grass_weather_snow_04.png");
        uploadSpriteTexture(context, handle, 14, "grass/drawable/grass_weather_fog_01.png");
        uploadSpriteTexture(context, handle, 15, "grass/drawable/grass_weather_fog_02.png");
        uploadSpriteTexture(context, handle, 16, "grass/drawable/grass_weather_cloud_01.png");
        uploadSpriteTexture(context, handle, 17, "grass/drawable/grass_weather_cloud_02.png");
        uploadSpriteTexture(context, handle, 18, "grass/drawable/grass_weather_cloud_03.png");
        uploadSpriteTexture(context, handle, 19, "grass/drawable/grass_weather_cloud_04.png");
        uploadSpriteTexture(context, handle, 20, "grass/drawable/grass_weather_lightning_01.png");
        uploadSpriteTexture(context, handle, 21, "grass/drawable/grass_weather_lightning_02.png");
        uploadSpriteTexture(context, handle, 22, "grass/drawable/grass_weather_lightning_03.png");
        // 23：1x64 的天气色调渐变（与 GLES 的 createWeatherToneTexture 同一份像素）
        nSetSpriteTexture(handle, 23, GrassWeatherRenderer.weatherToneArgb(), 1, 64);
    }

    /**
     * 程序化太阳的三张 LUT。与 GLES 那边是同一批 ASTC 资源，这里只拆掉 16 字节的
     * 文件头再把块数据原样交给 native —— 能不能用由设备对 ASTC 的支持决定，
     * 不支持时 native 会继续用贴图太阳。
     */
    static void uploadSunTextures(Context context, long handle) {
        uploadAstc(context, handle, 0, "grass/drawable/sun_ramp.astc");
        uploadAstc(context, handle, 1, "grass/drawable/sun_annulus_ramp.astc");
        uploadAstc(context, handle, 2, "grass/drawable/sun_rays.astc");
    }

    private static void uploadAstc(Context context, long handle, int slot, String assetPath) {
        if (context == null || handle == 0L) return;
        byte[] data = AssetLoader.readBytes(context, assetPath);
        if (data == null || data.length <= 16) return;
        int magic = (data[0] & 0xFF)
                | ((data[1] & 0xFF) << 8)
                | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
        if (magic != 0x5CA1AB13) return;
        int blockX = data[4] & 0xFF;
        int blockY = data[5] & 0xFF;
        if (blockX != 4 || blockY != 4) return;
        int width = (data[7] & 0xFF) | ((data[8] & 0xFF) << 8) | ((data[9] & 0xFF) << 16);
        int height = (data[10] & 0xFF) | ((data[11] & 0xFF) << 8) | ((data[12] & 0xFF) << 16);
        if (width <= 0 || height <= 0 || data.length <= 16) return;
        byte[] payload = new byte[data.length - 16];
        System.arraycopy(data, 16, payload, 0, payload.length);
        nSetSunTexture(handle, slot, payload, width, height);
    }

    static void uploadAATexture(long handle) {
        if (handle == 0L) {
            return;
        }
        int[] pixels = new int[] {0x00000000, 0xFFFFFFFF, 0xFFFFFFFF, 0x00000000};
        nSetAATexture(handle, pixels, 4, 1);
    }

    /** Upload a 24x64 sky texture generated from grass_sky_fields.txt section data. */
    private static void uploadSkyField(
            Context context, long handle, int slot, String allText, String sectionName) {
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
        nSetSpriteTexture(handle, slot, new int[] {argb}, 1, 1);
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

    private static void uploadSpriteTexture(
            Context context, long handle, int slot, String assetPath) {
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
