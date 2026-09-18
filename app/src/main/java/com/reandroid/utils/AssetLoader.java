package com.reandroid.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads text, float arrays, bitmaps, and raw bytes from assets/.
 */
public final class AssetLoader {
    private AssetLoader() {}

    public static String readText(Context context, String assetPath) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read asset: " + assetPath, e);
        }
    }

    public static float[] readFloatArray(Context context, String assetPath) {
        String text = readText(context, assetPath).trim();
        if (text.isEmpty()) return new float[0];
        String[] parts = text.split("[,\\s]+");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i]);
        }
        return values;
    }

    public static Bitmap decodeBitmap(Context context, String assetPath) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPremultiplied = false;
            return BitmapFactory.decodeStream(input, null, opts);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode asset bitmap: " + assetPath, e);
        }
    }

    public static Bitmap decodeBitmapWithOptions(Context context, String assetPath,
                                                  BitmapFactory.Options opts) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(input, null, opts);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode asset bitmap: " + assetPath, e);
        }
    }

    /** 一张单通道遮罩：白底黑图取红通道后的逐像素字节。 */
    public static final class Mask {
        public final byte[] pixels;
        public final int width;
        public final int height;

        Mask(byte[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 把一张白底黑图的遮罩解码成逐像素的单通道字节（取红通道）。
     *
     * <p>逐条带取像素而不是整张 {@code getPixels}：4096² 的 ARGB 数组是 64 MB，
     * 分条带的话峰值只有一份带子。解码用 RGB_565 把位图本身也砍一半 ——
     * 遮罩是灰阶的，5 位红通道（步长 8）足够表达 16 级以上的灰阶。
     *
     * @return 解码失败返回 null
     */
    public static Mask decodeMask(Context context, String assetPath) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = decodeBitmapWithOptions(context, assetPath, opts);
        if (bitmap == null) {
            return null;
        }

        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            bitmap.recycle();
            return null;
        }

        final int strip = Math.max(1, Math.min(height, 256));
        final int[] row = new int[width * strip];
        final byte[] mask = new byte[width * height];
        for (int y = 0; y < height; y += strip) {
            final int rows = Math.min(strip, height - y);
            bitmap.getPixels(row, 0, width, 0, y, width, rows);
            final int base = y * width;
            for (int i = 0; i < width * rows; i++) {
                mask[base + i] = (byte) ((row[i] >> 16) & 0xFF);
            }
        }
        bitmap.recycle();
        return new Mask(mask, width, height);
    }

    public static byte[] readBytes(Context context, String assetPath) {
        try (InputStream input = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read asset bytes: " + assetPath, e);
        }
    }
}
