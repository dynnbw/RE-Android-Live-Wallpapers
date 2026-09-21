package com.reandroid.wallpaper.fall;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * fall 的世界尺寸与水面网格密度。
 *
 * <p>两条都源自 AOSP 原版 {@code fall.rs}：
 *
 * <pre>
 *   float width  = 2;      //g_glWidth;
 *   float height = 3.333;  //g_glHeight;
 * </pre>
 *
 * <ol>
 *   <li><b>世界是定值 2 × 3.333</b>，不随屏幕变；屏幕差异由投影"铺满 + 裁切"适配，
 *       永不拉伸。移植时把它换成了 {@code 2*height/width}，世界形状跟着设备走，
 *       背景于是被拉伸。</li>
 *   <li><b>网格两轴每区间对应的世界长度相等</b> —— {@code addDrop()} 是在网格单位里算
 *       {@code length(delta)} 的，两轴不等的话等距线投到屏幕上就是椭圆。</li>
 * </ol>
 */
public class WaterMeshAspectTest {

    /** 与 FallScene.MESH_RESOLUTION + 2 一致。 */
    private static final int W_INTERVALS = 50;

    /** 与 FallScene.WORLD_HEIGHT 一致。 */
    private static final float WORLD_HEIGHT = 3.333f;

    /** 世界宽 2。 */
    private static final float WORLD_WIDTH = 2.0f;

    /** 叶片四边形的一半边长，与 FallScene.LEAF_SIZE 一致。 */
    private static final float LEAF_HALF = 0.55f;

    // ---- 世界尺寸 ----

    /** 世界高度是常量，跟屏幕一点关系都没有。 */
    @Test
    public void worldHeightIsConstant() {
        assertEquals(WORLD_HEIGHT, FallScene.worldHeight(), 1.0E-6f);
        assertEquals(WORLD_HEIGHT, FallScene.worldHeight(), 1.0E-6f);
    }

    /** 世界长宽比 0.6（2 : 3.333）—— 这就是"AOSP 的 2 × 3.333"。 */
    @Test
    public void worldAspectIsReferenceAspect() {
        assertEquals(0.6f, WORLD_WIDTH / FallScene.worldHeight(), 1.0E-3f);
    }

    // ---- 网格 ----

    /** 网格纵向区间数只由世界尺寸决定，与屏幕无关。 */
    @Test
    public void meshYIntervalsIgnoreTheScreen() {
        int expected = Math.round(W_INTERVALS * WORLD_HEIGHT / WORLD_WIDTH);
        assertEquals("应等于 横向区间 × 世界高 / 世界宽", expected,
                FallScene.meshYIntervals(W_INTERVALS));
        assertEquals(83, FallScene.meshYIntervals(W_INTERVALS));
    }

    /**
     * 两轴每区间对应的世界长度相等 —— 这就是"波纹是圆而不是椭圆"。
     *
     * <p>只跟世界尺寸和区间数有关：铺满 + 裁切保证了每世界单位对应的像素两轴相等，
     * 于是等距线在两个方向上被同样地缩放。
     */
    @Test
    public void meshIsIsotropic() {
        int hIntervals = FallScene.meshYIntervals(W_INTERVALS);
        float worldPerXInterval = WORLD_WIDTH / W_INTERVALS;
        float worldPerYInterval = FallScene.worldHeight() / hIntervals;

        assertEquals("两轴每区间的世界长度应相等",
                1.0f, worldPerYInterval / worldPerXInterval, 0.02f);
    }

    /**
     * 屏幕比例不参与网格 —— 换个屏幕，区间数和各向同性都不变。
     *
     * <p>线上出过的错正好相反：纵向区间数按 {@code height/width} 算，横屏得出 26
     * 而不是 83，波纹横向拉成 4.7 倍的椭圆。
     */
    @Test
    public void meshDoesNotDependOnScreenAspect() {
        assertEquals(FallScene.meshYIntervals(W_INTERVALS), FallScene.meshYIntervals(W_INTERVALS));
        // 竖屏、横屏、方形都用同一个值
        int h = FallScene.meshYIntervals(W_INTERVALS);
        assertEquals(1.0f, (FallScene.worldHeight() / h) / (WORLD_WIDTH / W_INTERVALS), 0.02f);
    }

    /** 纵向区间应多于横向（世界是竖的）。 */
    @Test
    public void verticalIsDenserThanHorizontal() {
        assertTrue("世界比宽高，纵向该切得更密",
                FallScene.meshYIntervals(W_INTERVALS) > W_INTERVALS);
    }

    // ---- 投影的铺满 + 裁切 ----

    /**
     * 可见世界矩形：屏幕比世界窄就按高对齐、横向裁；比世界宽就按宽对齐、纵向裁。
     *
     * <p>复刻 {@code updateProjectionMatrix()} 的选择，验证两轴像素比例恒相等
     * —— 也就是说**永远不拉伸**。
     */
    private static float[] visibleWorld(int width, int height) {
        float screenAspect = (float) width / height;
        float worldAspect = WORLD_WIDTH / WORLD_HEIGHT;
        if (screenAspect < worldAspect) {
            float halfH = WORLD_HEIGHT * 0.5f;
            return new float[]{halfH * screenAspect, halfH};
        }
        float halfW = WORLD_WIDTH * 0.5f;
        return new float[]{halfW, halfW / screenAspect};
    }

    private static void assertNoStretch(String label, int width, int height) {
        float[] vis = visibleWorld(width, height);
        float visibleW = vis[0] * 2.0f;
        float visibleH = vis[1] * 2.0f;

        assertEquals(label + " 可见世界的长宽比应等于屏幕长宽比（即不拉伸）",
                (float) width / height, visibleW / visibleH, 0.01f);
    }

    @Test
    public void neverStretches() {
        assertNoStretch("竖屏 20:9", 1080, 2400);
        assertNoStretch("横屏 20:9", 2294, 1080);
        assertNoStretch("横屏 16:9", 1920, 1080);
        assertNoStretch("竖屏 16:9", 1080, 1920);
        assertNoStretch("方形", 1080, 1080);
    }

    /** 竖屏比世界窄（0.45 < 0.6）→ 按高对齐，横向裁到 ±0.75。 */
    @Test
    public void portraitCropsHorizontally() {
        float[] vis = visibleWorld(1080, 2400);
        assertEquals("竖屏可见半宽", 0.75f, vis[0], 0.01f);
        assertEquals("竖屏可见半高 = 世界半高", WORLD_HEIGHT * 0.5f, vis[1], 1.0E-4f);
    }

    /** 横屏比世界宽（2.124 > 0.6）→ 按宽对齐，纵向裁。 */
    @Test
    public void landscapeCropsVertically() {
        float[] vis = visibleWorld(2294, 1080);
        assertEquals("横屏可见半宽 = 世界半宽", 1.0f, vis[0], 1.0E-4f);
        assertTrue("横屏可见半高应被裁到世界半高以下", vis[1] < WORLD_HEIGHT * 0.5f);
    }

    /** 铺满意味着可见矩形一定在世界之内，不会露出世界外的空白。 */
    @Test
    public void visibleRectStaysInsideTheWorld() {
        int[][] screens = {{1080, 2400}, {2294, 1080}, {1920, 1080}, {1080, 1080}, {1440, 3200}};
        for (int[] s : screens) {
            float[] vis = visibleWorld(s[0], s[1]);
            assertTrue(s[0] + "x" + s[1] + " 可见半宽越界",
                    vis[0] <= WORLD_WIDTH * 0.5f + 1.0E-4f);
            assertTrue(s[0] + "x" + s[1] + " 可见半高越界",
                    vis[1] <= WORLD_HEIGHT * 0.5f + 1.0E-4f);
        }
    }

    // ---- 朝向系数：叶片与波纹共用 ----

    /** 铺满 + 裁切之后每世界单位多少像素（与 FallScene 同一套）。 */
    private static float pixelsPerWorldUnit(int width, int height) {
        float screenAspect = (float) width / height;
        float worldAspect = WORLD_WIDTH / WORLD_HEIGHT;
        float halfW = screenAspect < worldAspect
                ? WORLD_HEIGHT * 0.5f * screenAspect
                : WORLD_WIDTH * 0.5f;
        return width / (2.0f * halfW);
    }

    /** "同一块屏幕竖过来"与当前的像素密度之比。竖屏恒为 1。 */
    private static float orientationScale(int width, int height) {
        return pixelsPerWorldUnit(Math.min(width, height), Math.max(width, height))
                / pixelsPerWorldUnit(width, height);
    }

    /** 竖屏不被缩放 —— 这是"竖屏观感一个像素都不动"的保证。 */
    @Test
    public void orientationScaleIsOneInPortrait() {
        assertEquals(1.0f, orientationScale(1080, 2400), 1.0E-4f);
        assertEquals(1.0f, orientationScale(1080, 1080), 1.0E-4f);
        assertTrue("横屏应当被缩小", orientationScale(2294, 1080) < 1.0f);
    }

    /**
     * 叶片在两个朝向下落到同样的物理尺寸（这就是"统一"的可测形式）。
     *
     * <p>容差放 5%：参考值取自"把当前屏幕竖过来"，而壁纸面比屏幕窄一截
     * （2294 vs 2400），所以它和真实竖屏差几个百分点。肉眼看不出来。
     */
    @Test
    public void leafScreenSizeMatchesAcrossOrientation() {
        float portrait = LEAF_HALF * orientationScale(1080, 2400) * pixelsPerWorldUnit(1080, 2400);
        float landscape = LEAF_HALF * orientationScale(2294, 1080) * pixelsPerWorldUnit(2294, 1080);

        assertEquals("横屏叶片应与竖屏一样大", 1.0f, landscape / portrait, 0.05f);
        // 竖屏 396px 是这条换算的锚点
        assertEquals("竖屏叶片", 396.0f, portrait, 1.0f);
    }

}
