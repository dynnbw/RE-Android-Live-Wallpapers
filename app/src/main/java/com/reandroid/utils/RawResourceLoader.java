package com.reandroid.utils;

import android.content.res.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RawResourceLoader {
    private RawResourceLoader() {
    }

    public static String readRawText(Resources resources, int resId) {
        if (resources == null) {
            throw new IllegalArgumentException("resources == null");
        }
        try (InputStream input = resources.openRawResource(resId)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw resource: " + resId, e);
        }
    }

    public static float[] readRawFloatArray(Resources resources, int resId) {
        String text = readRawText(resources, resId).trim();
        if (text.isEmpty()) {
            return new float[0];
        }
        String[] parts = text.split("[,\\s]+");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i]);
        }
        return values;
    }
}
