package com.reandroid.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads text, float arrays, bitmaps, and raw bytes from assets/.
 * Mirrors RawResourceLoader but reads from the assets directory tree.
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
