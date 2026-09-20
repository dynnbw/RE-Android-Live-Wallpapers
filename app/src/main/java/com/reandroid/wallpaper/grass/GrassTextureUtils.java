package com.reandroid.wallpaper.grass;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.reandroid.utils.MathUtils;
import com.reandroid.utils.SkyField;

final class GrassTextureUtils {

    private GrassTextureUtils() {}

    /** Parse a named section from grass_sky_fields.txt format. */
    static int[][] parseSkyFieldSection(String allText, String sectionName) {
        return SkyField.parseSection(allText, sectionName);
    }

    /**
     * Build a 24x64 ARGB pixel array from sky field color data.
     * Matches createSkyFieldTexture() sampling; used by the Vulkan path
     * which uploads pixels to the GPU instead of creating a GL texture.
     */
    static int[] skyFieldToARGB(int[][] fieldColors) {
        return SkyField.toArgb(fieldColors);
    }

    /** Create a 24x64 RGBA texture from sky field color data. */
    static int createSkyFieldTexture(int[][] fieldColors, boolean repeatS) {
        return SkyField.createTexture(fieldColors, repeatS);
    }

    /** Create a circular alpha mask texture at the given size. */
    static int createMoonMaskTexture(int size) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
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
        // 单字节/像素：行对齐按 4 字节算的话，宽度不是 4 的倍数时行会错位
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, size, size,
                0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    /** Create a tiny 4x1 alpha texture for anti-aliased grass blades. */
    static int createAlphaTexture() {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        byte[] mip0 = new byte[]{0, (byte) 255, (byte) 255, 0};
        byte[] mip1 = new byte[]{64, 64};
        byte[] mip2 = new byte[]{0};
        ByteBuffer b0 = ByteBuffer.allocateDirect(mip0.length).order(ByteOrder.nativeOrder());
        b0.put(mip0).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 4, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, b0);
        ByteBuffer b1 = ByteBuffer.allocateDirect(mip1.length).order(ByteOrder.nativeOrder());
        b1.put(mip1).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8, 2, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, b1);
        ByteBuffer b2 = ByteBuffer.allocateDirect(mip2.length).order(ByteOrder.nativeOrder());
        b2.put(mip2).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 2, GLES30.GL_R8, 1, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, b2);
        return tex[0];
    }

    /** Create a 1x1 RGBA solid color texture. */
    static int createSolidColorTexture(byte r, byte g, byte b, byte a) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        byte[] rgba = new byte[]{r, g, b, a};
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }
}
