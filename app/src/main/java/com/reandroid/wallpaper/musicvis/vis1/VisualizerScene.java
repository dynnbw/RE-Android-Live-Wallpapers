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

    /** 原版画笔的粗细（像素）。{@code paint.setStrokeWidth(2)} —— 即点的直径。 */
    static final float STROKE_WIDTH_PX = 2.0f;

    /** 边缘柔化的宽度（像素）。原版开着 {@code setAntiAlias(true)}，柔边约一个像素。 */
    static final float AA_FEATHER_PX = 1.0f;

    /** 采样数，与原版 {@code new int[1024]} 一致。 */
    static final int CAPTURE_SIZE = 1024;

    /** 每个点四个 float × 六个顶点。 */
    static final int FLOATS_PER_DOT = 24;

    /** 顶点：每个采样六个（一个点 = 两个三角形），每个 (x, y, 角 x, 角 y)。 */
    private final float[] mVertices = new float[CAPTURE_SIZE * FLOATS_PER_DOT];
    private int mVertexCount;

    /** 边缘柔化宽度，单位是"半径 = 1"的角坐标；由 {@link #buildDots} 按点的大小算好。 */
    private float mFeather = 1.0f;

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

    /** 有效的 float 数 = 顶点数 × 4（x, y, 角 x, 角 y）。 */
    int vertexFloats() {
        return mVertexCount * 4;
    }

    /** 边缘柔化宽度，单位与顶点里的角坐标一致（半径 = 1）。 */
    float feather() {
        return mFeather;
    }

    /**
     * 取一帧音频并重算顶点。
     *
     * <p>{@code getFormattedData(1, 1)} 与原版逐字一致：PCM 字节减 128，于是取值范围是
     * ±127 —— 原版就是把它当像素用的。
     */
    void updateTrace() {
        int[] data = mAudioCapture != null ? mAudioCapture.getFormattedData(1, 1) : null;
        data = dataOrSilence(data, mSilence);
        mFeather = featherFor(mWidth, data.length);
        mVertexCount = buildDots(data, mWidth, mHeight, mVertices);
    }

    /**
     * 没有数据时用静音数据 —— **画一条直线，不是什么都不画**。
     *
     * <p>原版的 {@code else Arrays.fill(mVizData, 0)} 表达的就是这个意图。
     *
     * <p>顺带补上原版漏掉的那一档：**capture 在、但数据还没到**时，
     * {@code getFormattedData} 返回的是**长度 0** 的数组（{@code mFormattedNullData}），
     * 而原版照跑 {@code mWidth} 次循环 —— 那会直接越界崩。所以那一档在我们的移植里
     * 按同一个意图处理：当成静音。否则画面是全黑的，而原意是"一条躺着的线"。
     */
    static int[] dataOrSilence(int[] data, int[] silence) {
        return (data == null || data.length == 0) ? silence : data;
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
     * 把一个采样铺成一个**圆点**（外接四边形 + 角坐标），返回写进去的**顶点数**。
     *
     * <p><b>为什么是点，不是带子。</b> 原版是 {@code c.drawPoint(x, y)} —— 一行圆点，
     * 不是一条连起来的线：信号跳变时两点之间本来就断开。而带子还有个更糟的毛病：
     * 沿着 y 上下偏移出来的"带子"，波形一陡，相邻两点之间的四边形就被拉成又高又窄的
     * 细条（实测就是"每个点高长而宽小"）。所以照原样画点。
     *
     * <p><b>点的大小跟采样间距走，不跟屏高走。</b> 原版是 2 像素的点、1 像素一个采样，
     * 直径 = 2 × 间距。屏高是**振幅**那一维，跟点的大小无关 —— 曾经按屏高缩放，在 2400
     * 高的屏上把点放大到了 6 像素，而采样间距还是 1 像素，于是又细又高。
     *
     * <p><b>圆，而且边缘要柔。</b> 原版是 {@code ROUND} cap + {@code setAntiAlias(true)}：
     * 直径 2 的抗锯齿圆点。四边形本身画出来是方的、边是硬的，所以每个顶点多带一个
     * **角坐标**（±1），片元按到中心的距离把四角切掉并柔化边缘 —— 见 {@code musicvis_line_fs}。
     * 内切圆的直径正好 = 原版的笔画宽度。
     *
     * <p>横向是 {@code i * 屏宽 / n}：屏宽 ≤ 1024 时 {@code n == 屏宽}，式子退化成
     * {@code x = i}，与原版逐个像素一一对应；屏更宽时把 n 个采样拉开铺满。
     *
     * <p>一个点两个三角形（六个顶点），每个顶点四个 float（x, y, 角 x, 角 y）。
     *
     * @param data   PCM 数据（{@code getFormattedData(1,1)} 的结果），可为 null
     * @param out    长度至少 {@code 24 * min(width, data.length)}
     * @return 写进去的顶点数；输入退化时为 0
     */
    static int buildDots(int[] data, int width, int height, float[] out) {
        if (data == null || out == null) {
            return 0;
        }
        int n = visibleSamples(width, data.length);
        if (n <= 0 || out.length < n * FLOATS_PER_DOT) {
            return 0;
        }

        float scale = amplitudeScale(height);
        float centerY = height * 0.5f;
        float stepX = width / (float) n;
        // 外接四边形的半边长：**实心部分 + 外面一圈柔边**。
        // 实心直径 = 原版的笔画宽度（2 × 采样间距），柔边是加在它**外面**的 —— 不能挤占
        // 实心，否则整颗点都在渐隐，画面上就只剩一团灰（实测就是这样，白点几乎看不见）。
        float half = (STROKE_WIDTH_PX * stepX + AA_FEATHER_PX) * 0.5f;

        int cursor = 0;
        for (int i = 0; i < n; i++) {
            float cx = i * stepX;
            float cy = centerY + data[i] * scale;
            float left = cx - half;
            float right = cx + half;
            float top = cy - half;
            float bottom = cy + half;
            cursor = put(out, cursor, left, top, -1.0f, -1.0f);
            cursor = put(out, cursor, right, top, 1.0f, -1.0f);
            cursor = put(out, cursor, left, bottom, -1.0f, 1.0f);
            cursor = put(out, cursor, right, top, 1.0f, -1.0f);
            cursor = put(out, cursor, right, bottom, 1.0f, 1.0f);
            cursor = put(out, cursor, left, bottom, -1.0f, 1.0f);
        }
        return n * 6;
    }

    private static int put(float[] out, int cursor, float x, float y, float cx, float cy) {
        out[cursor++] = x;
        out[cursor++] = y;
        out[cursor++] = cx;
        out[cursor++] = cy;
        return cursor;
    }

    /**
     * 边缘柔化的宽度，单位是"半径 = 1"的角坐标 —— 即柔掉的那一圈占半径的比例。
     *
     * <p>原版开的是 {@code setAntiAlias(true)}，柔边约一个**像素**。这一圈是加在实心
     * **外面**的，所以半径 = (实心直径 + 柔边) / 2，比值就是
     * {@code 柔边 / (实心直径 + 柔边)} —— 点越小柔边占比越大。
     *
     * <p>上界 1 只是防御：那意味着整颗点都在柔（实心为零），正常参数下到不了。
     */
    static float featherFor(int width, int sampleCount) {
        int n = visibleSamples(width, sampleCount);
        if (n <= 0) {
            return 1.0f;
        }
        float stepX = width / (float) n;
        float diameter = STROKE_WIDTH_PX * stepX + AA_FEATHER_PX;
        return Math.min(1.0f, AA_FEATHER_PX / Math.max(diameter, 0.001f));
    }
}
