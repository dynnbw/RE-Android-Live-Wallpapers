package com.reandroid.wallpaper.musicvis.vis1;

import android.content.Context;

import com.reandroid.wallpaper.musicvis.AudioCapture;
import com.reandroid.wallpaper.musicvis.AudioVisBase;

/**
 * vis1（Visualizer）的场景逻辑。
 *
 * <p>被移植的原版是 {@code Visualization1.java} —— 音乐可视化五个里唯一不用 RenderScript 的，
 * 纯 Canvas：黑底，然后
 * <pre>
 * for (int i = 0; i &lt; mWidth; i++) c.drawPoint(i, mCenterY + mVizData[i], mPaint);
 * </pre>
 * 一条横贯屏幕的白色波形线。
 *
 * <p><b>不照抄像素。</b> 原版把采样值直接当像素用，那是给约 800 高的屏写的：在今天的屏上
 * 只剩中线上很细的一抖。这里改成**按屏等比** —— 横向把 {@code min(屏宽, 采样数)} 个采样
 * 铺满整宽，纵向与线宽都乘 {@code 屏高 / 800}，保住的是当年"±16% 屏高"那个比例。
 *
 * <p><b>顺手绕开原版的一个越界。</b> 原版循环到 {@code mWidth}，而 {@code mVizData} 只有
 * 1024 个元素 —— 屏一宽过 1024 就越界读。当年没出事只是因为那时的壁纸屏不超过 1024 宽。
 * 这里按 {@code min(屏宽, 数据长度)} 取，多出来的宽度靠横向拉伸铺满。
 *
 * <p>零 GL 依赖：几何是**静态**函数，单测直接跑真代码（不必像 {@code ManySceneTest} 那样
 * 把逻辑抄进测试里）。
 */
public final class VisualizerScene extends AudioVisBase {

    /** 原版那块屏的高度。±128 像素在它上面是 ±16% 屏高，这是要保住的比例。 */
    static final float REFERENCE_HEIGHT = 800.0f;

    /** 原版画笔的粗细（像素）。{@code paint.setStrokeWidth(2)}。 */
    static final float STROKE_WIDTH_PX = 2.0f;

    /** 采样数，与原版 {@code new int[1024]} 一致。 */
    static final int CAPTURE_SIZE = 1024;

    /** 顶点：每个采样两个（带子的上下两条边），每个 (x, y)。 */
    private final float[] mVertices = new float[CAPTURE_SIZE * 4];
    private int mVertexCount;

    /**
     * 拿不到音频时用的数据 —— 原版是 {@code Arrays.fill(mVizData, 0)}，
     * 也就是**画一条直线**，而不是什么都不画。
     */
    private final int[] mSilence = new int[CAPTURE_SIZE];

    VisualizerScene(int width, int height, Context context) {
        super(width, height, context);
    }

    // ---- AudioVisBase ----

    @Override
    protected int getAudioType() {
        return AudioCapture.TYPE_PCM;
    }

    @Override
    protected int getAudioCaptureSize() {
        return CAPTURE_SIZE;
    }

    @Override
    public void start() {
        if (mAudioCapture == null) {
            mAudioCapture = new AudioCapture(AudioCapture.TYPE_PCM, CAPTURE_SIZE);
        }
        mAudioCapture.start();
    }

    @Override
    public void stop() {
        if (mAudioCapture != null) {
            mAudioCapture.stop();
        }
    }

    // ---- 逐帧 ----

    /** 屏幕尺寸变了要跟上：顶点是按屏宽铺满、按屏高定振幅算的。 */
    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    /** 顶点数组（每顶点 2 个 float），长度够 {@link #CAPTURE_SIZE} 个采样。 */
    float[] vertices() {
        return mVertices;
    }

    /** 有效的 float 数 = 顶点数 × 2。 */
    int vertexFloats() {
        return mVertexCount * 2;
    }

    /**
     * 取一帧音频并重算顶点。
     *
     * <p>{@code getFormattedData(1, 1)} 与原版逐字一致：PCM 字节减 128，于是取值范围是
     * ±127 —— 原版就是把它当像素用的。
     */
    void updateTrace() {
        int[] data = mAudioCapture != null ? mAudioCapture.getFormattedData(1, 1) : mSilence;
        mVertexCount = buildRibbon(data, mWidth, mHeight, mVertices);
    }

    // ---- 几何（纯函数，见 VisualizerSceneTest）----

    /**
     * 这一帧画多少个采样：{@code min(屏宽, 数据长度)}。
     *
     * <p>原版是"一个采样一个像素"，一直画到屏宽为止 —— 数据不够就**越界**。这里取小值，
     * 不足的部分由横向拉伸补上（见 {@link #buildRibbon}）。
     */
    static int visibleSamples(int width, int sampleCount) {
        if (width <= 0 || sampleCount <= 0) {
            return 0;
        }
        return Math.min(width, sampleCount);
    }

    /**
     * 纵向缩放：采样值乘它得到像素。
     *
     * <p>{@code 屏高 / 800} —— 屏高 800 时恰好是 1（原版就是 1 个采样值 1 个像素）。
     */
    static float amplitudeScale(int height) {
        return Math.max(1, height) / REFERENCE_HEIGHT;
    }

    /**
     * 把 PCM 数据铺成一条 triangle strip 带子，写进 {@code out}，返回**顶点数**。
     *
     * <p>每个采样两个顶点（上下各偏半个线宽），所以顶点数是采样数的两倍。
     *
     * <p>横向是 {@code i * 屏宽 / n}：屏宽 ≤ 1024 时 {@code n == 屏宽}，式子退化成
     * {@code x = i}，与原版逐个像素一一对应；屏更宽时把 n 个采样拉开铺满。
     *
     * <p>用带子而不是 {@code GL_LINES}：GLES 不支持 {@code glLineWidth > 1}，画不出
     * 2 像素的线。
     *
     * @param data   PCM 数据（{@code getFormattedData(1,1)} 的结果），可为 null
     * @param out    长度至少 {@code 2 * min(width, data.length) * 2}
     * @return 写进去的顶点数；输入退化时为 0
     */
    static int buildRibbon(int[] data, int width, int height, float[] out) {
        if (data == null || out == null) {
            return 0;
        }
        int n = visibleSamples(width, data.length);
        if (n <= 0 || out.length < n * 4) {
            return 0;
        }

        float scale = amplitudeScale(height);
        float centerY = height * 0.5f;
        float halfStroke = STROKE_WIDTH_PX * scale * 0.5f;
        float stepX = width / (float) n;

        int cursor = 0;
        for (int i = 0; i < n; i++) {
            float x = i * stepX;
            float y = centerY + data[i] * scale;
            out[cursor++] = x;
            out[cursor++] = y - halfStroke;
            out[cursor++] = x;
            out[cursor++] = y + halfStroke;
        }
        return n * 2;
    }
}
