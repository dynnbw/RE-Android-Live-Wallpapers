package com.reandroid.utils;

import android.graphics.Color;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 天空色场（{@code *_sky_fields.txt}）：把一片平滑的天空存成少量色值，
 * 载入时重采样成一张 {@value #WIDTH}×{@value #HEIGHT} 的小纹理，靠 GPU 双线性放大铺满屏幕。
 *
 * <p>文件格式（与 grass 同源）：
 * <pre>
 * [SKY_FIELD_NAME]
 * {
 *     {0xAARRGGBB,0xAARRGGBB,...},
 *     ...
 * }
 * </pre>
 * 外层 {@code {}} 内每个 {@code {}} 是一列色值。grass 用 24 列 × 64 行表达有横向变化的天空；
 * fall 的天空是纯竖直渐变，只写一行 64 个值即可 —— {@code cols == 1} 时重采样后会得到
 * 24 列完全相同的竖直渐变。
 */
public final class SkyField {

    /** 重采样后的小纹理尺寸。 */
    public static final int WIDTH = 24;
    public static final int HEIGHT = 64;

    private SkyField() {}

    /**
     * 从整份文件文本里取出一个具名段。
     *
     * @return 每行一个 int[]，行内是 0xAARRGGBB；找不到该段时返回 null
     */
    public static int[][] parseSection(String allText, String sectionName) {
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
     * 把色场重采样成 {@value #WIDTH}×{@value #HEIGHT} 的 0xAARRGGBB 像素数组。
     * 供 Vulkan 路径直接上传像素用。
     */
    public static int[] toArgb(int[][] fieldColors) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) return null;

        int[] argb = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            float v = y / (float) (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                float u = x / (float) (WIDTH - 1);
                argb[y * WIDTH + x] = sample(fieldColors, u, v);
            }
        }
        return argb;
    }

    /**
     * 把色场重采样成 {@value #WIDTH}×{@value #HEIGHT} 的 RGBA 纹理。
     *
     * @param repeatS 横向是否 GL_REPEAT（纵向恒为 CLAMP_TO_EDGE）
     * @return GL 纹理名；色场为空时返回 0
     */
    public static int createTexture(int[][] fieldColors, boolean repeatS) {
        if (fieldColors == null || fieldColors.length == 0 || fieldColors[0].length == 0) return 0;

        byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        for (int y = 0; y < HEIGHT; y++) {
            float v = y / (float) (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                float u = x / (float) (WIDTH - 1);
                int c = sample(fieldColors, u, v);
                int idx = (y * WIDTH + x) * 4;
                rgba[idx]     = (byte) Color.red(c);
                rgba[idx + 1] = (byte) Color.green(c);
                rgba[idx + 2] = (byte) Color.blue(c);
                rgba[idx + 3] = (byte) Color.alpha(c);
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
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** 在 u,v ∈ [0,1] 处对色场做双线性采样，返回 0xAARRGGBB。 */
    private static int sample(int[][] fieldColors, float u, float v) {
        final int cols = fieldColors.length;
        final int rows = fieldColors[0].length;

        float srcY = v * (rows - 1);
        int y0 = MathUtils.clamp((int) Math.floor(srcY), 0, rows - 1);
        int y1 = Math.min(rows - 1, y0 + 1);
        float ty = srcY - y0;

        float srcX = u * (cols - 1);
        int x0 = MathUtils.clamp((int) Math.floor(srcX), 0, cols - 1);
        int x1 = Math.min(cols - 1, x0 + 1);
        float tx = srcX - x0;

        int c00 = fieldColors[x0][y0], c10 = fieldColors[x1][y0];
        int c01 = fieldColors[x0][y1], c11 = fieldColors[x1][y1];

        int r = channel(c00, c10, c01, c11, tx, ty, 16);
        int g = channel(c00, c10, c01, c11, tx, ty, 8);
        int b = channel(c00, c10, c01, c11, tx, ty, 0);
        int a = channel(c00, c10, c01, c11, tx, ty, 24);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int c00, int c10, int c01, int c11, float tx, float ty, int shift) {
        float top = MathUtils.lerp((c00 >> shift) & 0xFF, (c10 >> shift) & 0xFF, tx);
        float bottom = MathUtils.lerp((c01 >> shift) & 0xFF, (c11 >> shift) & 0xFF, tx);
        return Math.round(MathUtils.lerp(top, bottom, ty));
    }
}
