package com.reandroid.wallpaper.geeklog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * GeekLog 的顶点构建：把场景快照铺成字符图集 quad 批量，外加闪烁光标。
 *
 * <p>GLES 与 Vulkan 两个后端共用这一份逻辑和同一套缓冲布局，避免两边各写一遍
 * 而逐渐画得不一样。顶点布局为每顶点 7 个 float：位置 x、y（已经是 NDC）、
 * 图集坐标 u、v、颜色 r、g、b（已乘当前行的 alpha）。
 */
final class GeekLogVertexBuilder {

    // 预烘焙字符图集（assets/geeklog/drawable/glyph_00.png）：
    // 256x256 RGBA，16 列 x 16 行槽位，每槽 16x16 px，槽位索引 = 字符码
    // （左上角槽位为字符 0x00，char 码 0x00-0xFF 按行列顺序排列）
    static final int ATLAS_COLS = 16;
    static final int ATLAS_ROWS = 16;

    static final int FLOATS_PER_VERTEX = 7; // x, y, u, v, r, g, b
    static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** 终端固定 80 列。 */
    private static final int COLS = 80;

    /** 一个字符/光标 quad = 两个三角形 = 6 个顶点。 */
    private static final int QUAD_VERTICES = 6;

    /**
     * 场景最多保留的行数；layout.json 的 seekbar 上限就是 120。
     * 缓冲一次按最坏情况分配，Vulkan 侧的固定缓冲容量也取这个值。
     */
    static final int MAX_ROWS = 120;

    /** 缓冲能达到的最大顶点数（行数封顶 + 一个光标 quad）。 */
    static final int CAPACITY_VERTICES = (MAX_ROWS * COLS + 1) * QUAD_VERTICES;

    /** 主色主题（0=green 1=amber 2=cyan 3=white），与 layout.json 的 values 顺序一致。 */
    private static final float[][] THEME_COLORS = {
        {0.0f, 1.0f, 0.255f}, // #00FF41
        {1.0f, 0.69f, 0.0f}, // #FFB000
        {0.0f, 0.898f, 1.0f}, // #00E5FF
        {1.0f, 1.0f, 1.0f}, // #FFFFFF
    };

    private static final float[] WARN_COLOR = {1.0f, 0.69f, 0.0f}; // #FFB000 琥珀
    private static final float[] ERROR_COLOR = {1.0f, 0.2f, 0.2f}; // #FF3333 红

    /** 每行算一次的暗色（前缀字符用），避免每行都新建数组。 */
    private final float[] mDimColor = new float[3];

    private final ByteBuffer mByteBuffer = ByteBuffer.allocateDirect(
                    CAPACITY_VERTICES * STRIDE_BYTES)
            .order(ByteOrder.nativeOrder());
    private final FloatBuffer mFloatBuffer = mByteBuffer.asFloatBuffer();

    // 布局（NDC）
    private int mRows = 30; // 屏幕行数
    private float mRowHeight; // 2f / mRows
    private float mCharWidthNDC; // mRowHeight * 0.6f
    private int mCols = COLS; // 每行最多字符数

    private int mVertexCount;

    /** GLES 侧直接拿它做 glVertexAttribPointer。 */
    FloatBuffer floatBuffer() {
        return mFloatBuffer;
    }

    /** Vulkan 侧直接拿它上传（native 读的是直接缓冲的起始地址）。 */
    ByteBuffer byteBuffer() {
        return mByteBuffer;
    }

    /** 上一次 buildVertices 写出的顶点数。 */
    int vertexCount() {
        return mVertexCount;
    }

    /**
     * 字符宽按屏幕宽度分配，行高按像素宽高比 0.6 计算，
     * 并折算 NDC 纵横比——否则字形会被拉成高瘦条。
     */
    void layoutMetrics(int width, int height) {
        float aspect = (float) width / Math.max(1, height);
        mCols = COLS;
        mCharWidthNDC = 2f / mCols;
        mRowHeight = mCharWidthNDC * aspect / 0.6f;
        mRows = Math.max(10, (int) (2f / mRowHeight));
    }

    /** 用当前场景快照重建这一帧的顶点，结果留在 {@link #floatBuffer()}。 */
    void buildVertices(GeekLogScene scene, long timeMs) {
        List<GeekLogScene.Entry> entries = scene.snapshot();
        int shown = Math.min(Math.min(entries.size(), mRows), MAX_ROWS);
        if (shown == 0) {
            mVertexCount = 0;
            return;
        }

        int vCount = 0;

        int start = entries.size() - 1;
        for (int r = 0; r < shown; r++) {
            GeekLogScene.Entry e = entries.get(start - r);
            float y0 = -1f + r * mRowHeight;
            float alpha = 1f - r / (float) Math.max(1, shown - 1) * 0.6f;

            float[] lineColor = colorFor(scene, e.level);
            float[] dimColor = mDimColor;
            dimColor[0] = lineColor[0] * 0.6f;
            dimColor[1] = lineColor[1] * 0.6f;
            dimColor[2] = lineColor[2] * 0.6f;

            String text = e.text;
            int len = Math.min(text.length(), mCols);
            for (int c = 0; c < len; c++) {
                char ch = text.charAt(c);
                if (ch < 0x20 || ch > 0x7E) continue;
                float x0 = -1f + c * mCharWidthNDC;
                float[] col = (c < GeekLogScene.PREFIX_LEN) ? dimColor : lineColor;
                vCount = addQuad(vCount, x0, y0, ch, col, alpha);
            }
        }

        // 光标：最新一行末尾闪烁块（'#' 字符槽）
        if (timeMs % 1000 < 500) {
            GeekLogScene.Entry last = entries.get(entries.size() - 1);
            int cursorCol = Math.min(last.text.length(), mCols - 1);
            float x0 = -1f + cursorCol * mCharWidthNDC;
            vCount = addCursorQuad(vCount, x0, -1f, 0x23, THEME_COLORS[scene.mColorIndex]);
        }

        mVertexCount = vCount;
    }

    /** 一个字符 quad = 6 顶点（triangles 展开）。 */
    private int addQuad(int vCount, float x0, float y0, int glyph, float[] col, float alpha) {
        int colIdx = glyph % ATLAS_COLS;
        int rowIdx = glyph / ATLAS_COLS;
        float u0 = colIdx / (float) ATLAS_COLS;
        float u1 = (colIdx + 1) / (float) ATLAS_COLS;
        float v0 = rowIdx / (float) ATLAS_ROWS;
        float v1 = (rowIdx + 1) / (float) ATLAS_ROWS;
        float x1 = x0 + mCharWidthNDC;
        float y1 = y0 + mRowHeight;
        addVertex(vCount, x0, y0, u0, v1, col, alpha);
        addVertex(vCount + 1, x1, y0, u1, v1, col, alpha);
        addVertex(vCount + 2, x0, y1, u0, v0, col, alpha);
        addVertex(vCount + 3, x1, y1, u1, v0, col, alpha);
        addVertex(vCount + 4, x0, y1, u0, v0, col, alpha);
        addVertex(vCount + 5, x1, y0, u1, v1, col, alpha);
        return vCount + QUAD_VERTICES;
    }

    /** 光标块：0.4 字符宽 x 0.8 行高，用图集 '#' 槽位保证可见。 */
    private int addCursorQuad(int vCount, float x0, float y0, int glyph, float[] col) {
        float w = mCharWidthNDC * 0.4f;
        float h = mRowHeight * 0.8f;
        float x1 = x0 + w;
        float y1 = y0 + h;
        int colIdx = glyph % ATLAS_COLS;
        int rowIdx = glyph / ATLAS_COLS;
        float u0 = colIdx / (float) ATLAS_COLS, u1 = (colIdx + 1) / (float) ATLAS_COLS;
        float v0 = rowIdx / (float) ATLAS_ROWS, v1 = (rowIdx + 1) / (float) ATLAS_ROWS;
        addVertex(vCount, x0, y0, u0, v1, col, 1f);
        addVertex(vCount + 1, x1, y0, u1, v1, col, 1f);
        addVertex(vCount + 2, x0, y1, u0, v0, col, 1f);
        addVertex(vCount + 3, x1, y1, u1, v0, col, 1f);
        addVertex(vCount + 4, x0, y1, u0, v0, col, 1f);
        addVertex(vCount + 5, x1, y0, u1, v1, col, 1f);
        return vCount + QUAD_VERTICES;
    }

    private void addVertex(
            int vCount, float x, float y, float u, float v, float[] col, float alpha) {
        int o = vCount * FLOATS_PER_VERTEX;
        mFloatBuffer.put(o, x);
        mFloatBuffer.put(o + 1, y);
        mFloatBuffer.put(o + 2, u);
        mFloatBuffer.put(o + 3, v);
        mFloatBuffer.put(o + 4, col[0] * alpha);
        mFloatBuffer.put(o + 5, col[1] * alpha);
        mFloatBuffer.put(o + 6, col[2] * alpha);
    }

    private static float[] colorFor(GeekLogScene scene, int level) {
        if (scene.mHighlightErrors) {
            if (level == GeekLogScene.LEVEL_WARN) return WARN_COLOR;
            if (level == GeekLogScene.LEVEL_ERROR) return ERROR_COLOR;
        }
        return THEME_COLORS[scene.mColorIndex];
    }
}
