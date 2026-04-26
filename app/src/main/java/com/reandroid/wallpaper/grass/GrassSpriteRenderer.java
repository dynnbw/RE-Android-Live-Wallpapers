package com.reandroid.wallpaper.grass;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class GrassSpriteRenderer {
    private int positionHandle = -1;
    private int texHandle = -1;
    private int samplerHandle = -1;
    private int alphaHandle = -1;

    private FloatBuffer spriteBuffer;
    private FloatBuffer batchBuffer;
    private final float[] quadVerts = new float[16];

    void setProgramHandles(int positionHandle, int texHandle, int samplerHandle, int alphaHandle) {
        this.positionHandle = positionHandle;
        this.texHandle = texHandle;
        this.samplerHandle = samplerHandle;
        this.alphaHandle = alphaHandle;
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
        if (vertices == null || floatCount <= 0 || (floatCount % 4) != 0) {
            return;
        }
        if (positionHandle < 0 || texHandle < 0 || samplerHandle < 0 || alphaHandle < 0) {
            return;
        }

        ensureBatchBuffer(floatCount);
        batchBuffer.clear();
        batchBuffer.put(vertices, 0, floatCount).position(0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, batchBuffer);
        batchBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, batchBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glUniform1f(alphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, floatCount / 4);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private void appendSpriteQuadVertices(
            float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3,
            float u0, float v0, float u1, float v1,
            float u2, float v2, float u3, float v3) {
        ensureBuffer();
        int cursor = 0;
        cursor = putSpriteVertex(cursor, x0, y0, u0, v0);
        cursor = putSpriteVertex(cursor, x1, y1, u1, v1);
        cursor = putSpriteVertex(cursor, x2, y2, u2, v2);
        putSpriteVertex(cursor, x3, y3, u3, v3);

        spriteBuffer.clear();
        spriteBuffer.put(quadVerts).position(0);
    }

    private int putSpriteVertex(int cursor, float x, float y, float u, float v) {
        quadVerts[cursor++] = x;
        quadVerts[cursor++] = y;
        quadVerts[cursor++] = u;
        quadVerts[cursor++] = v;
        return cursor;
    }

    private void flush(int texture, float alpha) {
        if (positionHandle < 0 || texHandle < 0 || samplerHandle < 0 || alphaHandle < 0) {
            return;
        }

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer);
        spriteBuffer.position(2);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, spriteBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glUniform1f(alphaHandle, alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private void ensureBuffer() {
        if (spriteBuffer == null) {
            spriteBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
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
