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
    /**
     * 一颗水珠的贴图。
     *
     * <p>**为什么是程序化的**：参考实现（天气应用）那层水珠根本不用贴图 ——
     * 它的观感来自"折射底图 + 镜面高光"，是算出来的。我们这边水珠是精灵，
     * 折射做不了，所以照那个**观感**反推一张静态图：珠体几乎透明（让背后的草透出来）、
     * 上缘一圈暗边（水珠的折射边缘）、顶上一点高光、下缘一点焦散（光透过水珠聚在底下）。
     *
     * <p><b>高光固定画在正上方</b>，渲染时再把整个四边形旋转到"朝向太阳"的角度 ——
     * 参考实现里那半句 {@code spec = max(dot(reflect(sunDir, texNorm), vec3(0,0,1)), 0)}
     * 表达的就是这件事（在原件里它是死代码，但意图是清楚的）。
     * 所以贴图不烘焙太阳方向，旋转交给渲染。
     */
    static int createWaterBeadTexture(int size) {
        int n = size * size;
        byte[] rgba = new byte[n * 4];
        float r = size * 0.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - r) / r;
                float dy = (y + 0.5f - r) / r;
                float d = (float) Math.sqrt(dx * dx + dy * dy);

                // 覆盖：主体几乎透明，只有边缘、高光、焦散是实的
                float cover = smoothstep(1.0f, 0.90f, d);
                if (cover <= 0.0f) {
                    continue;   // 全 0 已经初始化过
                }

                float rim = smoothstep(0.58f, 0.98f, d) * 0.55f;

                float hx = dx / 0.42f;
                float hy = (dy + 0.46f) / 0.30f;
                float highlight = smoothstep(1.0f, 0.0f, (float) Math.sqrt(hx * hx + hy * hy));

                float cx = dx / 0.52f;
                float cy = (dy - 0.52f) / 0.24f;
                float caustic = smoothstep(1.0f, 0.0f, (float) Math.sqrt(cx * cx + cy * cy)) * 0.35f;

                // 珠体：暗而偏冷（折射后的草色）；渐变到边缘更暗
                float bodyR = 0.24f, bodyG = 0.30f, bodyB = 0.28f;
                float cr = bodyR - rim * 0.20f + highlight * 0.76f + caustic * 0.66f;
                float cg = bodyG - rim * 0.20f + highlight * 0.70f + caustic * 0.65f;
                float cb = bodyB - rim * 0.18f + highlight * 0.70f + caustic * 0.62f;

                float alpha = (0.10f + rim + highlight * 0.85f + caustic) * cover;
                if (alpha > 1.0f) alpha = 1.0f;

                int o = (y * size + x) * 4;
                rgba[o] = toByte(clamp01(cr));
                rgba[o + 1] = toByte(clamp01(cg));
                rgba[o + 2] = toByte(clamp01(cb));
                rgba[o + 3] = toByte(alpha);
            }
        }

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        ByteBuffer buf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buf.put(rgba).position(0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        return tex[0];
    }

    private static byte toByte(float v) {
        return (byte) (int) (v * 255.0f + 0.5f);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /** {@code edge0 >= edge1} 时是反向的：x 小则返回 1。 */
    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge0 == edge1) {
            return x < edge0 ? 0.0f : 1.0f;
        }
        float t = (x - edge0) / (edge1 - edge0);
        t = clamp01(t);
        return t * t * (3.0f - 2.0f * t);
    }

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
