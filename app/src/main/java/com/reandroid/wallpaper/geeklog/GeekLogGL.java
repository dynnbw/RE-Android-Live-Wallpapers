package com.reandroid.wallpaper.geeklog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.reandroid.utils.AssetLoader;
import com.reandroid.gles.GLESScene;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * GeekLog GLES2 渲染层。
 * 字符图集（Bitmap+Paint 生成一次）+ 每帧 quad 批量绘制 + 行级着色 + 顶部渐隐 + 闪烁光标。
 */
public class GeekLogGL extends GLESScene implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "GeekLogGL";

    // 字符图集：16 列 x 8 行槽位，每槽 64x64 px，纹理 1024x512（2 的幂）
    private static final int ATLAS_COLS = 16;
    private static final int ATLAS_ROWS = 8;
    private static final int SLOT_PX = 64;
    private static final int ATLAS_W = ATLAS_COLS * SLOT_PX;
    private static final int ATLAS_H = ATLAS_ROWS * SLOT_PX;

    /** 主色主题（0=green 1=amber 2=cyan 3=white），与 layout.json 的 values 顺序一致 */
    static final float[][] THEME_COLORS = {
        { 0.0f, 1.0f, 0.255f },   // #00FF41
        { 1.0f, 0.69f, 0.0f },    // #FFB000
        { 0.0f, 0.898f, 1.0f },   // #00E5FF
        { 1.0f, 1.0f, 1.0f },     // #FFFFFF
    };
    static final float[] WARN_COLOR = { 1.0f, 0.69f, 0.0f };    // #FFB000 琥珀
    static final float[] ERROR_COLOR = { 1.0f, 0.2f, 0.2f };    // #FF3333 红

    private static final int FLOATS_PER_VERTEX = 7; // x, y, u, v, r, g, b

    private final Context mContext;
    private final GeekLogScene mScene = new GeekLogScene();
    private SharedPreferences mPluginPrefs;

    private int mProgram;
    private int mPosLoc, mUVLoc, mColorLoc, mTexLoc;
    private int mGlyphTexture;

    private FloatBuffer mVertexBuffer;   // 复用缓冲
    private int mVertexCount;

    // 布局（NDC）
    private int mRows = 30;              // 屏幕行数
    private float mRowHeight;            // 2f / mRows
    private float mCharWidthNDC;         // mRowHeight * 0.6f
    private int mCols;                   // 每行最多字符数

    private boolean mInitialized;
    private boolean mLoggedShaderFail;   // mProgram==0 持久失败态只记一次 logcat

    // 帧率统计（10s 窗口）
    private long mFpsWindowStartMs;
    private int mFpsFrameCount;

    public GeekLogGL(int width, int height, Context context) {
        super(width, height);
        mContext = context.getApplicationContext();
    }

    /** 反射注入（BasePluginEngine.tryInjectPrefs），在 onCreate 之前调用。 */
    public void setPluginPrefs(SharedPreferences prefs) {
        mPluginPrefs = prefs;
    }

    /** 记录 engine 生命周期事件（createScene 时调用，无需 GL）。 */
    public void logInfo(String msg) {
        mScene.log(GeekLogScene.LEVEL_INFO, msg);
    }

    // ---- 事件转发（Engine 调用）----

    public void onVisibility(boolean visible) { mScene.onVisibility(visible); }
    public void logOffsetsChanged() { mScene.onOffsetsChanged(); }
    public void logTap(int x, int y) { mScene.onTap(x, y); }
    public void logDrag(int x, int y) { mScene.onDrag(x, y); }

    // ---- GL 生命周期 ----

    @Override
    protected void onCreate() {
        if (mInitialized) return;
        mInitialized = true;
        mScene.log(GeekLogScene.LEVEL_INFO, "render: initialized (" + mWidth + "x" + mHeight + ")");
        try {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            readPrefs();
            createProgram();
            createGlyphTexture();
            layoutMetrics();
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            mScene.log(GeekLogScene.LEVEL_ERROR, "render: init failed - " + e.getClass().getSimpleName());
            mInitialized = false;
        }
    }

    @Override
    public void start() {
        SharedPreferences p = prefs();
        if (p != null) p.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void stop() {
        SharedPreferences p = prefs();
        if (p != null) p.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void release() {
        if (mProgram != 0) { GLES20.glDeleteProgram(mProgram); mProgram = 0; }
        if (mGlyphTexture != 0) {
            int[] tex = { mGlyphTexture };
            GLES20.glDeleteTextures(1, tex, 0);
            mGlyphTexture = 0;
        }
        mInitialized = false;
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        layoutMetrics();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        // 设置界面线程回调；只更新 scene 状态（volatile 原子），渲染线程安全读取
        readPrefs();
        mScene.onPrefsChanged(key);
    }

    // ---- 帧渲染 ----

    @Override
    public void drawFrame(long timeMs) {
        if (!mInitialized) {
            return;
        }
        if (mProgram == 0) {
            if (!mLoggedShaderFail) {
                mLoggedShaderFail = true;
                Log.w(TAG, "drawFrame skipped: shader program not ready");
            }
            return;
        }

        // 帧率统计（10s 窗口，低于 20fps 记 WARN）
        mFpsFrameCount++;
        long now = System.currentTimeMillis();
        if (mFpsWindowStartMs == 0) mFpsWindowStartMs = now;
        long elapsed = now - mFpsWindowStartMs;
        if (elapsed >= 10000) {
            float fps = mFpsFrameCount * 1000f / elapsed;
            if (fps < 20f) mScene.onFpsDrop((int) fps);
            mFpsFrameCount = 0;
            mFpsWindowStartMs = now;
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        buildVertices(timeMs);

        if (mVertexCount == 0) return;
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlyphTexture);
        GLES20.glUniform1i(mTexLoc, 0);

        GLES20.glEnableVertexAttribArray(mPosLoc);
        GLES20.glEnableVertexAttribArray(mUVLoc);
        GLES20.glEnableVertexAttribArray(mColorLoc);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, mVertexBuffer);
        mVertexBuffer.position(2);
        GLES20.glVertexAttribPointer(mUVLoc, 2, GLES20.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, mVertexBuffer);
        mVertexBuffer.position(4);
        GLES20.glVertexAttribPointer(mColorLoc, 3, GLES20.GL_FLOAT, false,
                FLOATS_PER_VERTEX * 4, mVertexBuffer);
        mVertexBuffer.position(0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mVertexCount);

        GLES20.glDisableVertexAttribArray(mPosLoc);
        GLES20.glDisableVertexAttribArray(mUVLoc);
        GLES20.glDisableVertexAttribArray(mColorLoc);
    }

    // ---- 顶点构建 ----

    private void buildVertices(long timeMs) {
        List<GeekLogScene.Entry> entries = mScene.snapshot();
        int shown = Math.min(entries.size(), mRows);
        if (shown == 0) { mVertexCount = 0; return; }

        int capacity = (shown * mCols + 1) * 6 * FLOATS_PER_VERTEX;
        if (mVertexBuffer == null || mVertexBuffer.capacity() < capacity) {
            mVertexBuffer = createFloatBuffer(new float[capacity]);
        }
        mVertexBuffer.position(0);

        int vCount = 0;

        int start = entries.size() - 1;
        for (int r = 0; r < shown; r++) {
            GeekLogScene.Entry e = entries.get(start - r);
            float y0 = -1f + r * mRowHeight;
            float alpha = 1f - r / (float) Math.max(1, shown - 1) * 0.6f;

            float[] lineColor = colorFor(e.level);
            float[] dimColor = {
                lineColor[0] * 0.6f, lineColor[1] * 0.6f, lineColor[2] * 0.6f
            };

            String text = e.text;
            int len = Math.min(text.length(), mCols);
            for (int c = 0; c < len; c++) {
                char ch = text.charAt(c);
                if (ch < 0x20 || ch > 0x7E) continue;
                int glyph = ch - 0x20;
                float x0 = -1f + c * mCharWidthNDC;
                float[] col = (c < GeekLogScene.PREFIX_LEN) ? dimColor : lineColor;
                vCount = addQuad(vCount, x0, y0, glyph, col, alpha);
            }
        }

        // 光标：最新一行末尾闪烁块
        if (timeMs % 1000 < 500) {
            GeekLogScene.Entry last = entries.get(entries.size() - 1);
            int cursorCol = Math.min(last.text.length(), mCols - 1);
            float x0 = -1f + cursorCol * mCharWidthNDC;
            vCount = addCursorQuad(vCount, x0, -1f, THEME_COLORS[mScene.mColorIndex]);
        }

        mVertexCount = vCount;
    }

    /** 一个字符 quad = 6 顶点（triangles 展开） */
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
        return vCount + 6;
    }

    /** 光标块：0.4 字符宽 x 0.8 行高，用图集 '#' 槽位保证可见 */
    private int addCursorQuad(int vCount, float x0, float y0, float[] col) {
        float w = mCharWidthNDC * 0.4f;
        float h = mRowHeight * 0.8f;
        float x1 = x0 + w;
        float y1 = y0 + h;
        int glyphHash = 0x23 - 0x20;   // '#'
        int colIdx = glyphHash % ATLAS_COLS;
        int rowIdx = glyphHash / ATLAS_COLS;
        float u0 = colIdx / (float) ATLAS_COLS, u1 = (colIdx + 1) / (float) ATLAS_COLS;
        float v0 = rowIdx / (float) ATLAS_ROWS, v1 = (rowIdx + 1) / (float) ATLAS_ROWS;
        addVertex(vCount, x0, y0, u0, v1, col, 1f);
        addVertex(vCount + 1, x1, y0, u1, v1, col, 1f);
        addVertex(vCount + 2, x0, y1, u0, v0, col, 1f);
        addVertex(vCount + 3, x1, y1, u1, v0, col, 1f);
        addVertex(vCount + 4, x0, y1, u0, v0, col, 1f);
        addVertex(vCount + 5, x1, y0, u1, v1, col, 1f);
        return vCount + 6;
    }

    private void addVertex(int vCount, float x, float y, float u, float v, float[] col, float alpha) {
        int o = vCount * FLOATS_PER_VERTEX;
        mVertexBuffer.put(o, x); mVertexBuffer.put(o + 1, y);
        mVertexBuffer.put(o + 2, u); mVertexBuffer.put(o + 3, v);
        mVertexBuffer.put(o + 4, col[0] * alpha);
        mVertexBuffer.put(o + 5, col[1] * alpha);
        mVertexBuffer.put(o + 6, col[2] * alpha);
    }

    // ---- 初始化辅助 ----

    private SharedPreferences prefs() {
        return mPluginPrefs != null ? mPluginPrefs
                : mContext.getSharedPreferences("plugin_geeklog", Context.MODE_PRIVATE);
    }

    private void readPrefs() {
        SharedPreferences p = prefs();
        if (p == null) return;
        String color = p.getString("geeklog_color", "green");
        int idx = 0;
        if ("amber".equals(color)) idx = 1;
        else if ("cyan".equals(color)) idx = 2;
        else if ("white".equals(color)) idx = 3;
        mScene.setColorIndex(idx);
        mScene.setHighlightErrors(p.getBoolean("geeklog_highlight_errors", false));
        mScene.setMaxLines(p.getInt("geeklog_max_lines", 80));
    }

    private float[] colorFor(int level) {
        if (mScene.mHighlightErrors) {
            if (level == GeekLogScene.LEVEL_WARN) return WARN_COLOR;
            if (level == GeekLogScene.LEVEL_ERROR) return ERROR_COLOR;
        }
        return THEME_COLORS[mScene.mColorIndex];
    }

    private void layoutMetrics() {
        // 每行 60 字符（终端风格列宽）。字符宽按屏幕宽度分配，
        // 行高按像素宽高比 0.6 计算，并折算 NDC 纵横比——否则字形会被拉成高瘦条。
        float aspect = (float) mWidth / Math.max(1, mHeight);
        mCols = 60;
        mCharWidthNDC = 2f / mCols;
        mRowHeight = mCharWidthNDC * aspect / 0.6f;
        mRows = Math.max(10, (int) (2f / mRowHeight));
    }

    private void createProgram() {
        String vs = AssetLoader.readText(mContext, "geeklog/shaders/GLES/terminal_vs.glsl");
        String fs = AssetLoader.readText(mContext, "geeklog/shaders/GLES/terminal_fs.glsl");
        mProgram = createProgram(vs, fs);
        if (mProgram == 0) {
            Log.e(TAG, "Shader program creation failed");
            mScene.log(GeekLogScene.LEVEL_ERROR, "render: shader compile failed");
            return;
        }
        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mUVLoc = GLES20.glGetAttribLocation(mProgram, "aUV");
        mColorLoc = GLES20.glGetAttribLocation(mProgram, "aColor");
        mTexLoc = GLES20.glGetUniformLocation(mProgram, "uTexture");
    }

    /** 生成 95 个 ASCII 可打印字符图集（Bitmap+Paint，白色文字 → alpha 通道）。 */
    private void createGlyphTexture() {
        Bitmap bmp = Bitmap.createBitmap(ATLAS_W, ATLAS_H, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.TRANSPARENT);   // createBitmap 内容未定义，必须清底
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(SLOT_PX * 0.75f);
        paint.setColor(Color.WHITE);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = SLOT_PX / 2f - (fm.ascent + fm.descent) / 2f;

        for (int i = 0; i < 95; i++) {
            char c = (char) (0x20 + i);
            int col = i % ATLAS_COLS;
            int row = i / ATLAS_COLS;
            canvas.drawText(String.valueOf(c),
                    col * SLOT_PX + SLOT_PX * 0.1f,
                    row * SLOT_PX + baseline, paint);
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        mGlyphTexture = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGlyphTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }
}
