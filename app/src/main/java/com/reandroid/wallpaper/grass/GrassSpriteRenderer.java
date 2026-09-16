package com.reandroid.wallpaper.grass;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class GrassSpriteRenderer {
    /**
     * 精灵顶点格式：x, y, u, v, a —— 每顶点一个 alpha。
     *
     * <p>逐顶点 alpha 是给"每个顶点透明度不同"的几何用的（粒子、星空、天气精灵都由
     * GrassRenderDataBuilder 产出这种格式，GLES 与 Vulkan 共用）。需要整批统一透明度的
     * 调用（{@link #drawSprite}/{@link #drawRect} 等）会把 a 写成 1，
     * 透明度仍走 uAlpha uniform —— 两者在片元里相乘。
     */
    private static final int FLOATS_PER_VERTEX = 5;
    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    private int positionHandle = -1;
    private int texHandle = -1;
    private int samplerHandle = -1;
    private int alphaHandle = -1;
    private int tintHandle = -1;
    private int vertexAlphaHandle = -1;

    /**
     * 染色（uTint）。默认白色即原样输出，用来把白色的云在夜里压暗。
     *
     * <p>{@code uTint} 是 **program 级**状态，不是每次绘制自带的，所以：
     * <ul>
     *   <li>这里每次绘制都会上传当前值 —— 改完必须显式 {@link #setTintWhite} 改回来，
     *       否则后续绘制（雨、雪、雾、粒子）会一起被染黑；</li>
     *   <li>{@link GrassBackgroundRenderer} 是唯一不走本类、直接画背景的，
     *       它自己会把 uTint 设回白色。</li>
     * </ul>
     */
    private float tintR = 1.0f;
    private float tintG = 1.0f;
    private float tintB = 1.0f;

    private FloatBuffer spriteBuffer;
    private FloatBuffer batchBuffer;
    private final float[] quadVerts = new float[4 * FLOATS_PER_VERTEX];

    void setProgramHandles(int positionHandle, int texHandle, int samplerHandle, int alphaHandle) {
        this.positionHandle = positionHandle;
        this.texHandle = texHandle;
        this.samplerHandle = samplerHandle;
        this.alphaHandle = alphaHandle;
    }

    void setTintHandle(int tintHandle) {
        this.tintHandle = tintHandle;
    }

    void setVertexAlphaHandle(int vertexAlphaHandle) {
        this.vertexAlphaHandle = vertexAlphaHandle;
    }

    void setTint(float r, float g, float b) {
        tintR = r;
        tintG = g;
        tintB = b;
    }

    void setTintWhite() {
        setTint(1.0f, 1.0f, 1.0f);
    }

    void drawSprite(int texture, float cx, float cy, float size, float alpha, boolean flipV, float rotationDeg) {
        ensureBuffer();
        float half = size * 0.5f;
        float rad = (float) Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x0 = (-half * cos) - (-half * sin) + cx;
        float y0 = (-half * sin) + (-half * cos) + cy;
        float x1 = (-half * cos) - (half * sin) + cx;
        float y1 = (-half * sin) + (half * cos) + cy;
        float x2 = (half * cos) - (half * sin) + cx;
        float y2 = (half * sin) + (half * cos) + cy;
        float x3 = (half * cos) - (-half * sin) + cx;
        float y3 = (half * sin) + (-half * cos) + cy;

        float v0 = flipV ? 0.0f : 1.0f;
        float v1 = flipV ? 1.0f : 0.0f;
        appendSpriteQuadVertices(x0, y0, x1, y1, x2, y2, x3, y3, 0.0f, v0, 0.0f, v1, 1.0f, v1, 1.0f, v0);
        flush(texture, alpha);
    }

    void drawRect(int texture, float left, float top, float width, float height, float alpha) {
        float right = left + width;
        float bottom = top + height;
        appendSpriteQuadVertices(left, top, left, bottom, right, bottom, right, top,
                0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f);
        flush(texture, alpha);
    }

    void drawRectUv(int texture,
            float left, float top, float right, float bottom,
            float uLeft, float vTop, float uRight, float vBottom,
            float alpha) {
        appendSpriteQuadVertices(left, top, left, bottom, right, bottom, right, top,
                uLeft, vTop, uLeft, vBottom, uRight, vBottom, uRight, vTop);
        flush(texture, alpha);
    }

    void drawBatch(int texture, float[] vertices, int floatCount, float alpha) {
        if (vertices == null || floatCount <= 0 || (floatCount % FLOATS_PER_VERTEX) != 0) {
            return;
        }
        // 注意不要在这里把 vertexAlphaHandle 也算进"句柄缺失"：万一某个驱动把 aAlpha 优化掉
        // （location = -1），那样会导致整批不画。通用顶点属性的默认值是 (0,0,0,1)，
        // 不启用该数组时 aAlpha 就是 1，对"整批统一透明度"的调用方恰好是正确的。
        if (positionHandle < 0 || texHandle < 0 || samplerHandle < 0 || alphaHandle < 0) {
            return;
        }

        ensureBatchBuffer(floatCount);
        batchBuffer.clear();
        batchBuffer.put(vertices, 0, floatCount).position(0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, batchBuffer);
        batchBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, batchBuffer);
        batchBuffer.position(4);
        GLES20.glEnableVertexAttribArray(vertexAlphaHandle);
        GLES20.glVertexAttribPointer(vertexAlphaHandle, 1, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, batchBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glUniform1f(alphaHandle, alpha);
        uploadTint();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, floatCount / FLOATS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
        GLES20.glDisableVertexAttribArray(vertexAlphaHandle);
    }

    private void appendSpriteQuadVertices(
            float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3,
            float u0, float v0, float u1, float v1,
            float u2, float v2, float u3, float v3) {
        ensureBuffer();
        int cursor = 0;
        cursor = putSpriteVertex(cursor, x0, y0, u0, v0, 1.0f);
        cursor = putSpriteVertex(cursor, x1, y1, u1, v1, 1.0f);
        cursor = putSpriteVertex(cursor, x2, y2, u2, v2, 1.0f);
        putSpriteVertex(cursor, x3, y3, u3, v3, 1.0f);

        spriteBuffer.clear();
        spriteBuffer.put(quadVerts).position(0);
    }

    private int putSpriteVertex(int cursor, float x, float y, float u, float v, float a) {
        quadVerts[cursor++] = x;
        quadVerts[cursor++] = y;
        quadVerts[cursor++] = u;
        quadVerts[cursor++] = v;
        quadVerts[cursor++] = a;
        return cursor;
    }

    private void flush(int texture, float alpha) {
        // 注意不要在这里把 vertexAlphaHandle 也算进"句柄缺失"：万一某个驱动把 aAlpha 优化掉
        // （location = -1），那样会导致整批不画。通用顶点属性的默认值是 (0,0,0,1)，
        // 不启用该数组时 aAlpha 就是 1，对"整批统一透明度"的调用方恰好是正确的。
        if (positionHandle < 0 || texHandle < 0 || samplerHandle < 0 || alphaHandle < 0) {
            return;
        }

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, spriteBuffer);
        spriteBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, spriteBuffer);
        spriteBuffer.position(4);
        GLES20.glEnableVertexAttribArray(vertexAlphaHandle);
        GLES20.glVertexAttribPointer(vertexAlphaHandle, 1, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, spriteBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glUniform1f(alphaHandle, alpha);
        uploadTint();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
        GLES20.glDisableVertexAttribArray(vertexAlphaHandle);
    }

    private void uploadTint() {
        if (tintHandle >= 0) {
            GLES20.glUniform3f(tintHandle, tintR, tintG, tintB);
        }
    }

    private void ensureBuffer() {
        if (spriteBuffer == null) {
            spriteBuffer = ByteBuffer.allocateDirect(4 * VERTEX_STRIDE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
    }

    private void ensureBatchBuffer(int floatCount) {
        if (batchBuffer == null || batchBuffer.capacity() < floatCount) {
            batchBuffer = ByteBuffer.allocateDirect(floatCount * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
    }
}
