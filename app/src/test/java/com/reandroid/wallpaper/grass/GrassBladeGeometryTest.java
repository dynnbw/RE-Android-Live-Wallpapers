package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static com.reandroid.wallpaper.grass.GrassConstants.HALF_TESSELATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 叶片中心线行走的回归。
 *
 * <p>这一条测试的份量比看上去重：{@link GrassBladeGeometry} 是从
 * {@code GrassRenderDataBuilder.appendBladeVertices()} 里**抽**出来的，目的是让挂珠
 * 复用同一条行走（两处各写一份的话，日后一改叶片形状，水珠就会飘到叶外）。
 *
 * <p>但"抽出来"这件事本身就是风险：叶片几何差了半个像素，**不会有任何断言变红**，
 * 只会让整片草看起来"有点不对"，而且极难定位。所以这里把**重构前的那段循环原样再抄一份**
 * 当参照，逐位比对两份实现的结果。两份必须给出完全相同的浮点数。
 */
public class GrassBladeGeometryTest {

    private static Blade makeBlade(int size, float angle) {
        Blade b = new Blade();
        b.size = size;
        b.angle = angle;
        b.xPos = 137.5f;
        b.yPos = 640.25f;
        b.scale = 3.75f;
        b.lengthX = 11.5f;
        b.lengthY = 13.25f;
        b.hardness = 0.6f;
        return b;
    }

    /**
     * 重构前的顶点生成，**原样**抄自 {@code appendBladeVertices()}。
     *
     * <p>输出是 {@code [x0L,y0L, x0R,y0R, x1L,y1L, ...]}，与当时的 {@code putVertex}
     * 调用顺序一致。
     */
    private static float[] verticesBeforeRefactor(Blade blade, float originX, float scale,
                                                  float hardnessScale, float heightScale) {
        int size = blade.size;
        float[] out = new float[(size + 1) * 4];
        int cursor = 0;

        float bottomX = originX;
        float bottomY = blade.yPos;
        float d = blade.angle * blade.hardness * hardnessScale;
        float stepCos = (float) Math.cos(d);
        float stepSin = (float) Math.sin(d);
        float currentCos = 0.0f;
        float currentSin = 1.0f;

        float si = size * scale;
        out[cursor++] = bottomX - si;
        out[cursor++] = bottomY + HALF_TESSELATION;
        out[cursor++] = bottomX + si;
        out[cursor++] = bottomY + HALF_TESSELATION;

        for (; size > 0; size--) {
            float lengthX = blade.lengthX * heightScale;
            float lengthY = blade.lengthY * heightScale;
            float topX = bottomX - currentCos * lengthX;
            float topY = bottomY - currentSin * lengthY;
            si = size * scale;
            float spi = si - scale;
            out[cursor++] = topX - spi;
            out[cursor++] = topY;
            out[cursor++] = topX + spi;
            out[cursor++] = topY;
            bottomX = topX;
            bottomY = topY;
            float nextCos = currentCos * stepCos - currentSin * stepSin;
            float nextSin = currentSin * stepCos + currentCos * stepSin;
            currentCos = nextCos;
            currentSin = nextSin;
        }
        return out;
    }

    /** 用 trace 的输出重建同一串顶点。 */
    private static float[] verticesFromTrace(Blade blade, float originX, float scale,
                                             float hardnessScale, float heightScale) {
        float[] xy = new float[GrassBladeGeometry.pointCount(blade) * 2];
        float[] hw = new float[GrassBladeGeometry.pointCount(blade)];
        GrassBladeGeometry.trace(blade, originX, scale, hardnessScale, heightScale, xy, hw);

        float[] out = new float[(blade.size + 1) * 4];
        int c = 0;
        out[c++] = xy[0] - hw[0];
        out[c++] = xy[1];
        out[c++] = xy[0] + hw[0];
        out[c++] = xy[1];
        for (int k = 1; k <= blade.size; k++) {
            out[c++] = xy[k * 2] - hw[k];
            out[c++] = xy[k * 2 + 1];
            out[c++] = xy[k * 2] + hw[k];
            out[c++] = xy[k * 2 + 1];
        }
        return out;
    }

    /**
     * 抽出来之后必须**逐位**还原原来的顶点。
     *
     * <p>覆盖多种叶长、叶角、弯曲度和缩放 —— 单点对拍容易恰好躲开浮点差异。
     */
    @Test
    public void traceReproducesTheOriginalWalkExactly() {
        int[] sizes = {1, 2, 5, 8, 13};
        float[] angles = {0.0f, 0.25f, -0.25f, 1.1f, -2.7f, 3.0f};
        float[] scales = {0.5f, 3.75f, 12.0f};
        float[] hardnesses = {0.0f, 0.6f, 1.0f, 2.5f};

        for (int size : sizes) {
            for (float angle : angles) {
                for (float scale : scales) {
                    for (float hardnessScale : hardnesses) {
                        Blade b = makeBlade(size, angle);
                        float originX = b.xPos + 42.5f;   // sd.xDraw 的效果
                        float heightScale = 0.9f;

                        float[] before = verticesBeforeRefactor(
                                b, originX, scale, hardnessScale, heightScale);
                        float[] after = verticesFromTrace(
                                b, originX, scale, hardnessScale, heightScale);

                        assertEquals("顶点数", before.length, after.length);
                        for (int i = 0; i < before.length; i++) {
                            assertEquals("size=" + size + " angle=" + angle + " scale=" + scale
                                            + " hardness=" + hardnessScale + " 第 " + i + " 个浮点数",
                                    before[i], after[i], 0.0f);
                        }
                    }
                }
            }
        }
    }

    /** 半宽从叶根的 size*scale 线性减到叶尖的 0 —— 这就是叶子是尖的。 */
    @Test
    public void halfWidthTapersToTheTip() {
        Blade b = makeBlade(6, 0.4f);
        float scale = 2.5f;
        float[] xy = new float[7 * 2];
        float[] hw = new float[7];
        GrassBladeGeometry.trace(b, b.xPos, scale, 1.0f, 1.0f, xy, hw);

        assertEquals("叶根半宽", 6 * scale, hw[0], 0.0f);
        assertEquals("叶尖半宽", 0.0f, hw[6], 0.0f);
        for (int k = 1; k < hw.length; k++) {
            assertTrue("第 " + k + " 点半宽没有变窄：" + hw[k - 1] + " -> " + hw[k],
                    hw[k] < hw[k - 1]);
        }
    }

    /** 叶角为 0 时叶片是竖直的：所有点 x 相同，且只往上走（y 递减）。 */
    @Test
    public void aStraightBladeGoesStraightUp() {
        Blade b = makeBlade(4, 0.0f);
        float[] xy = new float[5 * 2];
        float[] hw = new float[5];
        GrassBladeGeometry.trace(b, 100.0f, 2.0f, 1.0f, 1.0f, xy, hw);

        for (int k = 1; k <= 4; k++) {
            assertEquals("第 " + k + " 点的 x 应当与叶根一致", 100.0f, xy[k * 2], 1.0E-4f);
            assertTrue("第 " + k + " 点应当更高（y 更小）", xy[k * 2 + 1] < xy[(k - 1) * 2 + 1]);
        }
    }

    /** pointAt 在 u=±1 时给的是左右边缘，u=0 时给中心线。 */
    @Test
    public void pointAtHitsTheEdgesAndTheCentre() {
        Blade b = makeBlade(5, 0.3f);
        float[] xy = new float[6 * 2];
        float[] hw = new float[6];
        GrassBladeGeometry.trace(b, b.xPos, 3.0f, 1.0f, 1.0f, xy, hw);

        float[] out = new float[2];
        int k = 3;
        GrassBladeGeometry.pointAt(xy, hw, k, 0.0f, out);
        assertEquals("中心线 x", xy[k * 2], out[0], 1.0E-4f);
        assertEquals("中心线 y", xy[k * 2 + 1], out[1], 1.0E-4f);

        GrassBladeGeometry.pointAt(xy, hw, k, 1.0f, out);
        assertEquals("右边缘 x", xy[k * 2] + hw[k], out[0], 1.0E-4f);
        GrassBladeGeometry.pointAt(xy, hw, k, -1.0f, out);
        assertEquals("左边缘 x", xy[k * 2] - hw[k], out[0], 1.0E-4f);
    }

    /** k 是小数：在两点之间线性插值，且越界会被夹住（水珠滑出叶尖时不会算飞）。 */
    @Test
    public void pointAtInterpolatesAndClamps() {
        Blade b = makeBlade(4, 0.0f);
        float[] xy = new float[5 * 2];
        float[] hw = new float[5];
        GrassBladeGeometry.trace(b, 50.0f, 2.0f, 1.0f, 1.0f, xy, hw);

        float[] out = new float[2];
        GrassBladeGeometry.pointAt(xy, hw, 1.5f, 0.0f, out);
        // 注意 y 在奇数下标上：第 1 点的 y 是 xy[3]，第 2 点是 xy[5]
        assertEquals("中点 y 应当是两点平均",
                (xy[3] + xy[5]) / 2.0f, out[1], 1.0E-4f);

        GrassBladeGeometry.pointAt(xy, hw, -5.0f, 0.0f, out);
        assertEquals("k<0 夹到叶根", xy[1], out[1], 1.0E-4f);

        GrassBladeGeometry.pointAt(xy, hw, 99.0f, 0.0f, out);
        assertEquals("k 过大夹到叶尖", xy[4 * 2 + 1], out[1], 1.0E-4f);
    }

    /** 数组太小要报错，而不是静默越界写。 */
    @Test(expected = IllegalArgumentException.class)
    public void traceRejectsUndersizedArrays() {
        Blade b = makeBlade(6, 0.2f);
        GrassBladeGeometry.trace(b, b.xPos, 1.0f, 1.0f, 1.0f, new float[4], new float[2]);
    }
}
