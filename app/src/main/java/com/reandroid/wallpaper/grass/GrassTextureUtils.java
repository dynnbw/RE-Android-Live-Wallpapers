package com.reandroid.wallpaper.grass;

import android.graphics.Color;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.reandroid.utils.MathUtils;

final class GrassTextureUtils {

    private GrassTextureUtils() {}

    /** Parse a named section from grass_sky_fields.txt format. */
    static int[][] parseSkyFieldSection(String allText, String sectionName) {
        if (allText == null) return null;
        String marker = "[" + sectionName + "]";
        int start = allText.indexOf(marker);
        if (start < 0) return null;
        int bodyStart = start + marker.length();
        int next = allText.indexOf("[", bodyStart);
        String body = (next > bodyStart) ? allText.substring(bodyStart, next) : allText.substring(bodyStart);

        List<int[]> cols = new ArrayList<>();
        int depth = 0;
        int rowStart = -1;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '{') {
                depth++;
                if (depth == 2) rowStart = i + 1;
            } else if (ch == '}') {
                if (depth == 2 && rowStart >= 0) {
                    String row = body.substring(rowStart, i).trim();
                    if (!row.isEmpty() && row.contains("0x")) {
                        String[] parts = row.split(",");
                        int[] values = new int[parts.length];
                        for (int p = 0; p < parts.length; p++)
                            values[p] = Long.decode(parts[p].trim()).intValue();
                        cols.add(values);
                    }
                    rowStart = -1;
                }
                depth--;
            }
        }
        if (cols.isEmpty()) return null;
        return cols.toArray(new int[0][]);
    }

    /**
     * Build a 24x64 ARGB pixel array from sky field color data.
     * Matches createSkyFieldTexture() sampling; used by the Vulkan path
     * which uploads pixels to the GPU instead of creating a GL texture.
     */
    static int[] skyFieldToARGB(int[][] fieldColors) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) return null;

        final int cols = fieldColors.length;
        final int rows = fieldColors[0].length;
        final int targetW = 24;
        final int targetH = 64;
        int[] argb = new int[targetW * targetH];

        for (int y = 0; y < targetH; y++) {
            float v = y / (float) (targetH - 1);
            float srcY = v * (rows - 1);
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(srcY)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float ty = srcY - y0;
            for (int x = 0; x < targetW; x++) {
                float u = x / (float) (targetW - 1);
                float srcX = u * (cols - 1);
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(srcX)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float tx = srcX - x0;

                int c00 = fieldColors[x0][y0], c10 = fieldColors[x1][y0];
                int c01 = fieldColors[x0][y1], c11 = fieldColors[x1][y1];

                int r = Math.round(MathUtils.lerp(MathUtils.lerp(Color.red(c00), Color.red(c10), tx),
                        MathUtils.lerp(Color.red(c01), Color.red(c11), tx), ty));
                int g = Math.round(MathUtils.lerp(MathUtils.lerp(Color.green(c00), Color.green(c10), tx),
                        MathUtils.lerp(Color.green(c01), Color.green(c11), tx), ty));
                int b = Math.round(MathUtils.lerp(MathUtils.lerp(Color.blue(c00), Color.blue(c10), tx),
                        MathUtils.lerp(Color.blue(c01), Color.blue(c11), tx), ty));
                int a = Math.round(MathUtils.lerp(MathUtils.lerp(Color.alpha(c00), Color.alpha(c10), tx),
                        MathUtils.lerp(Color.alpha(c01), Color.alpha(c11), tx), ty));

                argb[y * targetW + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return argb;
    }

    /** Create a 24x64 RGBA texture from sky field color data. */
    static int createSkyFieldTexture(int[][] fieldColors, boolean repeatS) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) return 0;

        final int cols = fieldColors.length;
        final int rows = fieldColors[0].length;
        final int targetW = 24;
        final int targetH = 64;
        byte[] rgba = new byte[targetW * targetH * 4];

        for (int y = 0; y < targetH; y++) {
            float v = y / (float) (targetH - 1);
            float srcY = v * (rows - 1);
            int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(srcY)));
            int y1 = Math.min(rows - 1, y0 + 1);
            float ty = srcY - y0;
            for (int x = 0; x < targetW; x++) {
                float u = x / (float) (targetW - 1);
                float srcX = u * (cols - 1);
                int x0 = Math.max(0, Math.min(cols - 1, (int) Math.floor(srcX)));
                int x1 = Math.min(cols - 1, x0 + 1);
                float tx = srcX - x0;

                int c00 = fieldColors[x0][y0], c10 = fieldColors[x1][y0];
                int c01 = fieldColors[x0][y1], c11 = fieldColors[x1][y1];

                int r = Math.round(MathUtils.lerp(MathUtils.lerp(Color.red(c00), Color.red(c10), tx),
                        MathUtils.lerp(Color.red(c01), Color.red(c11), tx), ty));
                int g = Math.round(MathUtils.lerp(MathUtils.lerp(Color.green(c00), Color.green(c10), tx),
                        MathUtils.lerp(Color.green(c01), Color.green(c11), tx), ty));
                int b = Math.round(MathUtils.lerp(MathUtils.lerp(Color.blue(c00), Color.blue(c10), tx),
                        MathUtils.lerp(Color.blue(c01), Color.blue(c11), tx), ty));
                int a = Math.round(MathUtils.lerp(MathUtils.lerp(Color.alpha(c00), Color.alpha(c10), tx),
                        MathUtils.lerp(Color.alpha(c01), Color.alpha(c11), tx), ty));

                int idx = (y * targetW + x) * 4;
                rgba[idx] = (byte) r; rgba[idx + 1] = (byte) g;
                rgba[idx + 2] = (byte) b; rgba[idx + 3] = (byte) a;
            }
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                repeatS ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, targetW, targetH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** Create a circular alpha mask texture at the given size. */
    static int createMoonMaskTexture(int size) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        byte[] alpha = new byte[size * size];
        float cx = (size - 1) * 0.5f, cy = (size - 1) * 0.5f;
        float radius = size * 0.5f - 1.0f;
        float edge = radius * 0.08f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - cx, dy = y - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float a = 1.0f - MathUtils.clamp((dist - radius + edge) / edge, 0.0f, 1.0f);
                alpha[y * size + x] = (byte) Math.round(a * 255.0f);
            }
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(alpha.length).order(ByteOrder.nativeOrder());
        buf.put(alpha).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, size, size,
                0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** Create a tiny 4x1 alpha texture for anti-aliased grass blades. */
    static int createAlphaTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        byte[] mip0 = new byte[]{0, (byte) 255, (byte) 255, 0};
        byte[] mip1 = new byte[]{64, 64};
        byte[] mip2 = new byte[]{0};
        ByteBuffer b0 = ByteBuffer.allocateDirect(mip0.length).order(ByteOrder.nativeOrder());
        b0.put(mip0).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, 4, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b0);
        ByteBuffer b1 = ByteBuffer.allocateDirect(mip1.length).order(ByteOrder.nativeOrder());
        b1.put(mip1).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 1, GLES20.GL_ALPHA, 2, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b1);
        ByteBuffer b2 = ByteBuffer.allocateDirect(mip2.length).order(ByteOrder.nativeOrder());
        b2.put(mip2).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 2, GLES20.GL_ALPHA, 1, 1, 0,
                GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, b2);
        return tex[0];
    }

    /** Create a 1x1 RGBA solid color texture. */
    static int createSolidColorTexture(byte r, byte g, byte b, byte a) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        byte[] rgba = new byte[]{r, g, b, a};
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }
}
