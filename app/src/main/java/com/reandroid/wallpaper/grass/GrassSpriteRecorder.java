package com.reandroid.wallpaper.grass;

import java.util.Arrays;

/**
 * 把天气图层录成顶点数组，交给 VK 渲染器画。
 *
 * <p>每批 8 个 float 的头部：
 * {@code (槽位, 起始顶点, 顶点数, 染色 r, g, b, 整批透明度, 备用)}；
 * 顶点是 {@code (x, y, u, v, a)}，与 native 的 {@code SpriteVertex} 同布局。
 *
 * <p>几何必须与 {@link GrassSpriteRenderer} 逐字一致 —— 两边的天气图层才会画在同一个位置。
 */
final class GrassSpriteRecorder implements GrassSpriteSink {

    static final int FLOATS_PER_VERTEX = 5;
    static final int HEADER_FLOATS = 8;

    private float[] mVertices = new float[1024];
    private int mVertexFloats;
    private float[] mHeaders = new float[HEADER_FLOATS * 32];
    private int mBatchCount;

    private float mTintR = 1f, mTintG = 1f, mTintB = 1f;

    void begin() {
        mVertexFloats = 0;
        mBatchCount = 0;
    }

    float[] vertices() {
        return mVertices;
    }

    int vertexFloats() {
        return mVertexFloats;
    }

    float[] headers() {
        return mHeaders;
    }

    int batchCount() {
        return mBatchCount;
    }

    @Override
    public void setTint(float r, float g, float b) {
        mTintR = r;
        mTintG = g;
        mTintB = b;
    }

    @Override
    public void setTintWhite() {
        setTint(1f, 1f, 1f);
    }

    @Override
    public void drawSprite(
            int texture,
            float cx,
            float cy,
            float size,
            float alpha,
            boolean flipV,
            float rotationDeg) {
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
        appendQuad(x0, y0, x1, y1, x2, y2, x3, y3, 0f, v0, 0f, v1, 1f, v1, 1f, v0, texture, alpha);
    }

    @Override
    public void drawRect(
            int texture, float left, float top, float width, float height, float alpha) {
        float right = left + width;
        float bottom = top + height;
        appendQuad(
                left, top, left, bottom, right, bottom, right, top, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f,
                texture, alpha);
    }

    @Override
    public void drawRectUv(
            int texture,
            float left,
            float top,
            float right,
            float bottom,
            float uLeft,
            float vTop,
            float uRight,
            float vBottom,
            float alpha) {
        appendQuad(
                left, top, left, bottom, right, bottom, right, top, uLeft, vTop, uLeft, vBottom,
                uRight, vBottom, uRight, vTop, texture, alpha);
    }

    @Override
    public void drawBatch(int texture, float[] vertices, int floatCount, float alpha) {
        if (vertices == null || floatCount <= 0 || (floatCount % FLOATS_PER_VERTEX) != 0) {
            return;
        }
        ensureVertices(floatCount);
        System.arraycopy(vertices, 0, mVertices, mVertexFloats, floatCount);
        appendBatchHeader(texture, mVertexFloats, floatCount / FLOATS_PER_VERTEX, alpha);
        mVertexFloats += floatCount;
    }

    private void appendQuad(
            float x0,
            float y0,
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            float u3,
            float v3,
            int texture,
            float alpha) {
        /*
         * **必须是 6 个顶点。** VK 的精灵管线是 TRIANGLE_LIST，GLES 那边是
         * TRIANGLE_FAN/4 顶点 —— 只写 4 个的话相邻两个四边形会拼成横跨屏幕的大三角形。
         * 顶点顺序与 GrassRenderDataBuilder 的 (0,1,2)+(0,2,3) 一致。
         */
        ensureVertices(6 * FLOATS_PER_VERTEX);
        putVertex(x0, y0, u0, v0);
        putVertex(x1, y1, u1, v1);
        putVertex(x2, y2, u2, v2);
        putVertex(x0, y0, u0, v0);
        putVertex(x2, y2, u2, v2);
        putVertex(x3, y3, u3, v3);
        appendBatchHeader(texture, mVertexFloats - 6 * FLOATS_PER_VERTEX, 6, alpha);
    }

    private void appendBatchHeader(int texture, int firstFloat, int vertexCount, float alpha) {
        if (mBatchCount * HEADER_FLOATS + HEADER_FLOATS > mHeaders.length) {
            mHeaders = Arrays.copyOf(mHeaders, mHeaders.length * 2);
        }
        int base = mBatchCount * HEADER_FLOATS;
        mHeaders[base] = texture;
        mHeaders[base + 1] = firstFloat / FLOATS_PER_VERTEX;
        mHeaders[base + 2] = vertexCount;
        mHeaders[base + 3] = mTintR;
        mHeaders[base + 4] = mTintG;
        mHeaders[base + 5] = mTintB;
        mHeaders[base + 6] = alpha;
        mHeaders[base + 7] = 0f;
        mBatchCount++;
    }

    private void putVertex(float x, float y, float u, float v) {
        int i = mVertexFloats;
        mVertices[i] = x;
        mVertices[i + 1] = y;
        mVertices[i + 2] = u;
        mVertices[i + 3] = v;
        mVertices[i + 4] = 1f;
        mVertexFloats += FLOATS_PER_VERTEX;
    }

    private void ensureVertices(int extraFloats) {
        if (mVertexFloats + extraFloats <= mVertices.length) {
            return;
        }
        int size = mVertices.length;
        while (size < mVertexFloats + extraFloats) {
            size *= 2;
        }
        mVertices = Arrays.copyOf(mVertices, size);
    }
}
