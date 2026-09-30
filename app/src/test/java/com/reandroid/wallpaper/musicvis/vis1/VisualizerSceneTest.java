package com.reandroid.wallpaper.musicvis.vis1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * vis1（Visualizer）几何的守卫。
 *
 * <p>跑的是真代码（静态函数），不是把算式抄进测试里 —— 抄一遍只能证明抄对了。
 *
 * <p>守两件事：
 * <ul>
 *   <li>**按屏等比**：屏宽 ≤ 1024 时横向必须退化成原版的"一个采样一个像素"，
 *       更宽的屏则铺满整宽；纵向必须保住 ±16% 屏高那个比例。</li>
 *   <li>**不多读**：原版画到屏宽为止，而数据只有 1024 个 —— 宽屏上原版会越界。
 *       这里取两者的较小值，越界就是这里红。</li>
 * </ul>
 */
public class VisualizerSceneTest {

    /** 造一段数据集：值在 [-amp, amp] 之间来回。 */
    private static int[] wave(int count, int amp) {
        int[] d = new int[count];
        for (int i = 0; i < count; i++) {
            d[i] = (i % 2 == 0) ? amp : -amp;
        }
        return d;
    }

    // ─────────── 取多少个采样 ───────────

    @Test
    public void narrowScreenDrawsOneSamplePerPixel() {
        assertEquals(480, VisualizerScene.visibleSamples(480, 1024));
        assertEquals(800, VisualizerScene.visibleSamples(800, 1024));
    }

    @Test
    public void wideScreenStopsAtTheDataItHas() {
        // 原版会在这里越界读：屏 1080 宽，数据只有 1024 个
        assertEquals(1024, VisualizerScene.visibleSamples(1080, 1024));
        assertEquals(1024, VisualizerScene.visibleSamples(1440, 1024));
    }

    @Test
    public void dataShorterThanTheScreenWins() {
        assertEquals(100, VisualizerScene.visibleSamples(1080, 100));
    }

    @Test
    public void degenerateSizesGiveNoSamples() {
        assertEquals(0, VisualizerScene.visibleSamples(0, 1024));
        assertEquals(0, VisualizerScene.visibleSamples(-5, 1024));
        assertEquals(0, VisualizerScene.visibleSamples(1080, 0));
    }

    // ─────────── 纵向比例 ───────────

    @Test
    public void theReferenceHeightIsOneToOne() {
        assertEquals(1.0f, VisualizerScene.amplitudeScale(800), 0.0f);
    }

    @Test
    public void theAmplitudeKeepsTheOriginalShareOfTheScreen() {
        int height = 2400;
        float scale = VisualizerScene.amplitudeScale(height);
        // 原版满量程是 ±128 像素，那块 800 高的屏上是 ±16% 屏高
        float excursion = 128.0f * scale;
        assertEquals(0.16f, excursion / height, 1.0E-4f);
        assertEquals(384.0f, excursion, 1.0E-3f);
    }

    @Test
    public void aZeroHeightScreenDoesNotDivideByZero() {
        assertTrue(VisualizerScene.amplitudeScale(0) > 0.0f);
    }

    // ─────────── 顶点 ───────────

    @Test
    public void everySampleProducesTwoVertices() {
        int[] data = wave(480, 100);
        float[] out = new float[480 * 4];
        assertEquals(960, VisualizerScene.buildRibbon(data, 480, 800, out));
    }

    /** 屏宽 ≤ 1024 时必须与原版逐个像素一致：第 i 个采样的 x 就是 i。 */
    @Test
    public void onNarrowScreensTheXIsThePixelIndex() {
        int[] data = wave(480, 100);
        float[] out = new float[480 * 4];
        VisualizerScene.buildRibbon(data, 480, 800, out);

        for (int i : new int[]{0, 1, 10, 479}) {
            assertEquals("第 " + i + " 个采样的 x 偏移了", (float) i, out[i * 4], 0.0f);
        }
    }

    /** 数据比屏窄时，这 n 个采样要铺满整宽而不是挤在左边。 */
    @Test
    public void fewerSamplesThanPixelsSpreadAcrossTheWholeWidth() {
        int width = 1080;
        int[] data = wave(540, 100);
        float[] out = new float[540 * 4];
        VisualizerScene.buildRibbon(data, width, 800, out);

        float step = out[8] - out[4];              // 第 2 个与第 1 个采样的间距
        assertEquals("采样没有铺满整宽", 2.0f, step, 1.0E-3f);
        // 最后一个采样落在屏幕的 539/540 处
        assertEquals(width * 539.0f / 540.0f, out[539 * 4], 1.0E-2f);
    }

    @Test
    public void theTraceIsCentredAndSymmetric() {
        int height = 2400;
        int[] data = wave(64, 127);
        float[] out = new float[64 * 4];
        VisualizerScene.buildRibbon(data, 64, height, out);

        float center = height * 0.5f;
        float scale = VisualizerScene.amplitudeScale(height);
        // 偶数下标是 +amp、奇数下标是 -amp
        assertEquals(center + 127 * scale, (out[1] + out[3]) * 0.5f, 1.0E-2f);
        assertEquals(center - 127 * scale, (out[5] + out[7]) * 0.5f, 1.0E-2f);
    }

    @Test
    public void theStrokeNeverCollapsesToNothing() {
        int[] data = wave(8, 0);
        float[] out = new float[8 * 4];
        VisualizerScene.buildRibbon(data, 8, 2400, out);
        assertTrue("线宽被算成了 0", out[3] - out[1] > 0.0f);
    }

    // ─────────── 不多读 ───────────

    /**
     * 数据比屏窄时只读得到这么多个。**这条要是红了就是越界读** ——
     * Java 数组越界会抛，所以这个调用本身就是证明。
     */
    @Test
    public void neverReadsPastTheEndOfTheData() {
        int[] data = new int[100];                 // 正好 100 个，多一个都没有
        float[] out = new float[100 * 4];
        assertEquals(200, VisualizerScene.buildRibbon(data, 1080, 2400, out));
        assertEquals(1080.0f * 99.0f / 100.0f, out[99 * 4], 1.0E-2f);
    }

    @Test
    public void degenerateInputIsIgnored() {
        float[] out = new float[16];
        assertEquals(0, VisualizerScene.buildRibbon(null, 100, 800, out));
        assertEquals(0, VisualizerScene.buildRibbon(new int[100], 0, 800, out));
        // 输出数组装不下
        assertEquals(0, VisualizerScene.buildRibbon(new int[100], 100, 800, new float[8]));
        assertEquals(0, VisualizerScene.buildRibbon(new int[100], 100, 800, null));
    }

    // ─────────── 没有音频时 ───────────

    /**
     * 拿不到音频要画**一条直线**，不是什么都不画。
     *
     * <p>原版写得很明确：{@code else Arrays.fill(mVizData, 0)} —— 于是寂静时是一条落在中线上的
     * 线。这也正好是设置页预览该有的样子（预览里通常没有音频）。
     */
    @Test
    public void withoutAudioTheTraceIsAFlatCentreLine() {
        VisualizerScene scene = new VisualizerScene(1080, 2400, null);
        scene.updateTrace();

        int floats = scene.vertexFloats();
        assertTrue("没有音频时什么都没画出来", floats > 0);

        float[] v = scene.vertices();
        float center = 1200.0f;
        for (int i = 0; i < floats; i += 4) {
            // 每个顶点：(x, y-半宽), (x, y+半宽) —— 两个顶点关于中线对称
            assertEquals("线没有压在中线上", center, (v[i + 1] + v[i + 3]) * 0.5f, 1.0E-3f);
        }
    }

    @Test
    public void resizeChangesTheGeometry() {
        VisualizerScene scene = new VisualizerScene(1080, 2400, null);
        scene.updateTrace();
        float firstX = scene.vertices()[0];
        int firstFloats = scene.vertexFloats();

        scene.resize(480, 800);
        scene.updateTrace();

        // 480 个采样 → 960 个顶点 → 1920 个 float
        assertEquals("换屏之后采样的个数没跟着变", 480 * 4, scene.vertexFloats());
        assertTrue(firstFloats > 0);
        assertEquals(0.0f, firstX, 0.0f);
    }
}
