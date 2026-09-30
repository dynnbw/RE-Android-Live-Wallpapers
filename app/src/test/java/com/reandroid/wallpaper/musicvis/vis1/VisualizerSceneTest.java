package com.reandroid.wallpaper.musicvis.vis1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * vis1（Visualizer）几何的守卫。
 *
 * <p>跑的是真代码（静态函数），不是把算式抄进测试里 —— 抄一遍只能证明抄对了。
 *
 * <p>守三件事：
 * <ul>
 *   <li>**按屏等比**：屏宽 ≤ 1024 时横向必须退化成原版的"一个采样一个像素"，
 *       更宽的屏则铺满整宽；纵向必须保住 ±16% 屏高那个比例。</li>
 *   <li>**点必须是正的**：宽 = 高，而且大小只跟采样间距有关、与屏高无关。</li>
 *   <li>**不多读**：原版画到屏宽为止，而数据只有 1024 个 —— 宽屏上原版会越界。
 *       这里取两者的较小值，越界就是这里红。</li>
 * </ul>
 */
public class VisualizerSceneTest {

    /** 一个点的顶点步长（六个顶点 × 四个 float：x, y, 角 x, 角 y）。 */
    private static final int DOT = VisualizerScene.FLOATS_PER_DOT;

    /** 造一段数据集：值在 [-amp, amp] 之间来回。 */
    private static int[] wave(int count, int amp) {
        int[] d = new int[count];
        for (int i = 0; i < count; i++) {
            d[i] = (i % 2 == 0) ? amp : -amp;
        }
        return d;
    }

    private static float left(float[] out, int dot) {
        return out[dot * DOT];
    }

    private static float right(float[] out, int dot) {
        return out[dot * DOT + 4];          // 第二个顶点（右上）
    }

    private static float top(float[] out, int dot) {
        return out[dot * DOT + 1];
    }

    private static float bottom(float[] out, int dot) {
        return out[dot * DOT + 9];          // 第三个顶点（左下）
    }

    private static float centreX(float[] out, int dot) {
        return (left(out, dot) + right(out, dot)) * 0.5f;
    }

    private static float centreY(float[] out, int dot) {
        return (top(out, dot) + bottom(out, dot)) * 0.5f;
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

    // ─────────── 点 ───────────

    @Test
    public void everySampleProducesASixVertexDot() {
        int[] data = wave(480, 100);
        float[] out = new float[480 * DOT];
        assertEquals(480 * 6, VisualizerScene.buildDots(data, 480, 800, out));
    }

    /** 屏宽 ≤ 1024 时必须与原版逐个像素一致：第 i 个采样的中心就是 x = i。 */
    @Test
    public void onNarrowScreensTheDotIsCentredOnThePixelIndex() {
        int[] data = wave(480, 100);
        float[] out = new float[480 * DOT];
        VisualizerScene.buildDots(data, 480, 800, out);

        for (int i : new int[]{0, 1, 10, 479}) {
            assertEquals("第 " + i + " 个采样的中心偏了", (float) i, centreX(out, i), 0.0f);
        }
    }

    /**
     * **点必须是正的。** 这一条是为一个实测的毛病写的：早先按屏高缩放点的大小，在 2400 高的屏上
     * 点被放大到 6 像素，而采样间距还是 1 像素 —— 画面上每个点又高又窄。
     */
    @Test
    public void everyDotIsSquare() {
        int[] data = wave(1024, 100);
        float[] out = new float[1024 * DOT];
        VisualizerScene.buildDots(data, 1080, 2400, out);

        for (int i : new int[]{0, 1, 500, 1023}) {
            assertEquals("第 " + i + " 个点不是正的",
                    right(out, i) - left(out, i), bottom(out, i) - top(out, i), 1.0E-3f);
        }
    }

    /**
     * 角坐标必须是四角（±1），片元才切得出圆。
     *
     * <p>原版是 {@code drawPoint} + {@code ROUND} cap：画出来是**圆点**，不是方块。
     * 四个角写错，画面上就是方的。
     */
    @Test
    public void everyDotCarriesItsFourCorners() {
        int[] data = wave(8, 0);
        float[] out = new float[8 * DOT];
        VisualizerScene.buildDots(data, 8, 800, out);

        float[][] actual = {
                {out[2], out[3]}, {out[6], out[7]}, {out[10], out[11]},
                {out[14], out[15]}, {out[18], out[19]}, {out[22], out[23]},
        };
        float[][] expected = {{-1, -1}, {1, -1}, {-1, 1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < actual.length; i++) {
            assertEquals("第 " + i + " 个顶点的角坐标 x 不对",
                    expected[i][0], actual[i][0], 0.0f);
            assertEquals("第 " + i + " 个顶点的角坐标 y 不对",
                    expected[i][1], actual[i][1], 0.0f);
        }
    }

    /** 点的大小跟着采样间距走（原版 2 像素点、1 像素间距），与屏高无关。 */
    @Test
    public void theDotSizeFollowsTheSampleSpacingNotTheScreenHeight() {
        int[] data = wave(480, 100);
        float[] out = new float[480 * DOT];

        VisualizerScene.buildDots(data, 480, 800, out);
        float onShortScreen = right(out, 0) - left(out, 0);

        VisualizerScene.buildDots(data, 480, 2400, out);
        float onTallScreen = right(out, 0) - left(out, 0);

        assertEquals("屏变高把点放大了 —— 屏高是振幅那一维", onShortScreen, onTallScreen, 0.0f);
        // 480 宽 480 个采样 → 实心 2 像素 + 柔边 1 像素
        assertEquals(VisualizerScene.STROKE_WIDTH_PX + VisualizerScene.AA_FEATHER_PX,
                onShortScreen, 1.0E-4f);
    }

    /**
     * **实心部分的直径必须保住原版的笔画宽度**（2 × 采样间距），柔边只能加在它外面。
     *
     * <p>这条是为一个实测的毛病写的：早先让柔边去挤占实心 —— 半径只有 1 像素、柔边也
     * 铺满 1 像素，于是整颗点从头到尾都在渐隐、没有一处是实心的，画面上白点几乎看不见。
     */
    @Test
    public void theSolidCoreKeepsTheOriginalStrokeWidth() {
        for (int width : new int[]{480, 1080, 1440}) {
            VisualizerScene scene = new VisualizerScene(width, 800, null);
            scene.updateTrace();

            float[] v = scene.vertices();
            float radiusPx = (right(v, 0) - left(v, 0)) * 0.5f;
            float solidRadiusPx = radiusPx * (1.0f - scene.feather());
            // 原版的关系是"直径 = 2 × 采样间距"：480 宽 480 个采样时正好 2 像素，
            // 采样被拉开的屏上按同一比例变粗
            float stepX = centreX(v, 1) - centreX(v, 0);

            assertEquals("屏宽 " + width + "：实心直径不再是 2 × 采样间距",
                    VisualizerScene.STROKE_WIDTH_PX * stepX, solidRadiusPx * 2.0f, 1.0E-3f);
        }
    }

    /**
     * 柔边宽度对应"一个像素"，占半径的比例 = 柔边 /（实心直径 + 柔边）——
     * 点越小占比越大，但永远小于 1（总得留一块实心）。
     */
    @Test
    public void theFeatherIsOnePixelWorthOfTheRadius() {
        VisualizerScene scene = new VisualizerScene(480, 800, null);
        scene.updateTrace();
        // 实心 2 + 柔边 1 → 柔边占半径的 1/3
        assertEquals(1.0f / 3.0f, scene.feather(), 1.0E-4f);

        VisualizerScene wide = new VisualizerScene(1080, 800, null);
        wide.updateTrace();
        // 采样被拉开（实心变粗）→ 同样的 1 像素柔边占比变小
        assertTrue("点变大之后柔边没有跟着变窄", wide.feather() < scene.feather());
        assertTrue(wide.feather() > 0.0f);
    }

    /** 数据比屏窄时，这 n 个采样要铺满整宽而不是挤在左边。 */
    @Test
    public void fewerSamplesThanPixelsSpreadAcrossTheWholeWidth() {
        int width = 1080;
        int[] data = wave(540, 100);
        float[] out = new float[540 * DOT];
        VisualizerScene.buildDots(data, width, 800, out);

        assertEquals("采样没有铺满整宽", 2.0f, centreX(out, 1) - centreX(out, 0), 1.0E-3f);
        // 最后一个采样落在屏幕的 539/540 处
        assertEquals(width * 539.0f / 540.0f, centreX(out, 539), 1.0E-2f);
    }

    @Test
    public void theTraceIsCentredOnTheSampleValue() {
        int height = 2400;
        int[] data = wave(64, 127);
        float[] out = new float[64 * DOT];
        VisualizerScene.buildDots(data, 64, height, out);

        float center = height * 0.5f;
        float scale = VisualizerScene.amplitudeScale(height);
        // y 向下：+amp 画在下方
        assertEquals(center + 127 * scale, centreY(out, 0), 1.0E-2f);
        assertEquals(center - 127 * scale, centreY(out, 1), 1.0E-2f);
    }

    @Test
    public void theDotNeverCollapsesToNothing() {
        int[] data = wave(8, 0);
        float[] out = new float[8 * DOT];
        VisualizerScene.buildDots(data, 8, 2400, out);
        assertTrue("点的大小被算成了 0", right(out, 0) - left(out, 0) > 0.0f);
    }

    // ─────────── 不多读 ───────────

    /**
     * 数据比屏窄时只读得到这么多个。**这条要是红了就是越界读** ——
     * Java 数组越界会抛，所以这个调用本身就是证明。
     */
    @Test
    public void neverReadsPastTheEndOfTheData() {
        int[] data = new int[100];                 // 正好 100 个，多一个都没有
        float[] out = new float[100 * DOT];
        assertEquals(600, VisualizerScene.buildDots(data, 1080, 2400, out));
        assertEquals(1080.0f * 99.0f / 100.0f, centreX(out, 99), 1.0E-2f);
    }

    @Test
    public void degenerateInputIsIgnored() {
        float[] out = new float[16];
        assertEquals(0, VisualizerScene.buildDots(null, 100, 800, out));
        assertEquals(0, VisualizerScene.buildDots(new int[100], 0, 800, out));
        // 输出数组装不下
        assertEquals(0, VisualizerScene.buildDots(new int[100], 100, 800, new float[8]));
        assertEquals(0, VisualizerScene.buildDots(new int[100], 100, 800, null));
    }

    // ─────────── 没有音频时 ───────────

    /**
     * "capture 在、数据还没到"那一档也要画出直线。
     *
     * <p>原版这里是**崩**的：{@code getFormattedData} 返回长度 0 的数组，而循环照跑
     * {@code mWidth} 次。我们按原版的**意图**（{@code else Arrays.fill(..., 0)}）处理 ——
     * 否则头几帧与设置页预览会是一片全黑。
     */
    @Test
    public void emptyDataCountsAsSilenceNotAsNothing() {
        int[] silence = new int[8];
        int[] empty = new int[0];

        assertSame(silence, VisualizerScene.dataOrSilence(empty, silence));
        assertSame(silence, VisualizerScene.dataOrSilence(null, silence));

        int[] real = new int[]{1, 2, 3};
        assertSame("有数据时不该被换掉", real, VisualizerScene.dataOrSilence(real, silence));
    }

    @Test
    public void withoutAudioTheTraceIsAFlatCentreLine() {
        VisualizerScene scene = new VisualizerScene(1080, 2400, null);
        scene.updateTrace();

        int floats = scene.vertexFloats();
        assertTrue("没有音频时什么都没画出来", floats > 0);

        float[] v = scene.vertices();
        for (int i = 0; i < floats; i += DOT) {
            assertEquals("点没有压在中线上", 1200.0f, (v[i + 1] + v[i + 9]) * 0.5f, 1.0E-3f);
        }
    }

    @Test
    public void resizeChangesTheGeometry() {
        VisualizerScene scene = new VisualizerScene(1080, 2400, null);
        scene.updateTrace();
        int firstFloats = scene.vertexFloats();

        scene.resize(480, 800);
        scene.updateTrace();

        // 480 个采样 → 2880 个顶点 → 11520 个 float
        assertEquals("换屏之后采样的个数没跟着变", 480 * DOT, scene.vertexFloats());
        assertTrue(firstFloats > 0);
    }
}
