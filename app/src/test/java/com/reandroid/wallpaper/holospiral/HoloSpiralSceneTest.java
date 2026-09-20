package com.reandroid.wallpaper.holospiral;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HoloSpiralScene 的约束测试。
 *
 * 这些几何数据直接喂给着色器,错了就是画面不对,所以这里钉住的是**结构**而不是数值:
 * 顶点数/stride 对齐、螺旋半径恒定、z 沿深度单调展开、颜色按 sin(radians/2) 插值
 * （会外插出端点色,见 HoloSpiralScene 的注释）、旋转角不越界。
 * 数值本身是眼调的,抄一遍没有意义。
 */
public class HoloSpiralSceneTest {

    /** 顶点数 × stride 必须与数组长度严格对应，否则 GL 侧按 stride 读会越界。 */
    @Test
    public void vertexCountTimesStrideMatchesArrayLength() {
        HoloSpiralScene s = new HoloSpiralScene();
        for (int count : new int[] {1, 2, 50, 100, 333}) {
            float[] data = s.buildSpiralData(count, 30.0f, 10.0f, 23.0f, 0xFF112233, 0xFF445566);
            assertEquals("count=" + count + " 的数组长度", count * HoloSpiralScene.FLOATS_PER_VERTEX,
                    data.length);
        }
    }

    /** 每个点都在以 radius 为半径的圆上（xy 平面），与深度无关。 */
    @Test
    public void spiralRadiusIsConstant() {
        HoloSpiralScene s = new HoloSpiralScene();
        float radius = 7.5f;
        float[] data = s.buildSpiralData(64, 50.0f, radius, 23.0f, 0xFF000000, 0xFFFFFFFF);
        float minR = Float.MAX_VALUE, maxR = 0f;
        for (int i = 0; i < data.length; i += HoloSpiralScene.FLOATS_PER_VERTEX) {
            float r = (float) Math.hypot(data[i], data[i + 1]);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        assertTrue("半径应恒定（min=" + minR + " max=" + maxR + "）", Math.abs(minR - radius) < 1e-3f
                && Math.abs(maxR - radius) < 1e-3f);
    }

    /** z 从 -depth/2 线性走到 +depth/2，首尾各差一个步长。 */
    @Test
    public void depthSpansSymmetrically() {
        HoloSpiralScene s = new HoloSpiralScene();
        float depth = 40.0f;
        int count = 9;
        float[] data = s.buildSpiralData(count, depth, 5.0f, 23.0f, 0xFF000000, 0xFFFFFFFF);
        float first = data[2];
        float last = data[(count - 1) * HoloSpiralScene.FLOATS_PER_VERTEX + 2];
        assertEquals("首点 z", -depth / 2.0f, first, 1e-5f);
        assertTrue("末点 z 应接近 +depth/2（实际 " + last + "）",
                Math.abs(last - (depth / 2.0f - depth / count)) < 1e-4f);
        // 单调递增
        float prev = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            float z = data[i * HoloSpiralScene.FLOATS_PER_VERTEX + 2];
            assertTrue("z 应单调递增", z > prev);
            prev = z;
        }
    }

    /**
     * 颜色插值的三条性质（**不是**"落在两端点之间" —— 因子是 sin(radians/2)，
     * 值域 [-1,1]，会外插出去，见 HoloSpiralScene 的注释）：
     *
     *   1. radians=0 处等于 primary（sin(0)=0）；
     *   2. 每个通道都落在外插包络 [2p-s, s] 内（因为因子被 sin 限制在 ±1）；
     *   3. 一个完整周期内确实摆到两端（说明两个颜色都用上了，没有退化成单色）。
     */
    @Test
    public void colorInterpolatesWithSineEnvelope() {
        HoloSpiralScene s = new HoloSpiralScene();
        int primary = 0xB30000FF;
        int secondary = 0xD2A633FF;
        // 取够一个完整周期：因子周期是 radians 走 4π，即顶点数 = 4π / separationRads
        int count = 100;
        float[] data = s.buildSpiralData(count, 30.0f, 10.0f, 23.0f, primary, secondary);

        float[] p = HoloSpiralScene.convertColor(primary);
        float[] q = HoloSpiralScene.convertColor(secondary);
        int off = 3;   // rgb 紧跟 xyz

        for (int c = 0; c < 4; c++) {
            assertEquals("第 0 点通道 " + c + " 应等于 primary", p[c], data[off + c], 1e-5f);
        }

        for (int c = 0; c < 4; c++) {
            float lo = Math.min(2 * p[c] - q[c], q[c]);
            float hi = Math.max(2 * p[c] - q[c], q[c]);
            float minSeen = Float.MAX_VALUE, maxSeen = Float.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                float v = data[i * HoloSpiralScene.FLOATS_PER_VERTEX + off + c];
                assertTrue("通道 " + c + " 越出外插包络（v=" + v
                                + " 包络 " + lo + ".." + hi + "）",
                        v >= lo - 1e-4f && v <= hi + 1e-4f);
                minSeen = Math.min(minSeen, v);
                maxSeen = Math.max(maxSeen, v);
            }
            float span = Math.abs(q[c] - p[c]);
            if (span > 1e-3f) {
                assertTrue("通道 " + c + " 应摆到两端（min=" + minSeen + " max=" + maxSeen + "）",
                        minSeen < p[c] - span * 0.05f && maxSeen > p[c] + span * 0.05f);
            }
        }
    }

    /** ARGB → RGBA 各通道归一化，且顺序不能反（R 在前、A 在后）。 */
    @Test
    public void convertColorYieldsRgba() {
        float[] c = HoloSpiralScene.convertColor(0x80402010);   // a=80 r=40 g=20 b=10
        assertEquals("r", 0x40 / 255.0f, c[0], 1e-5f);
        assertEquals("g", 0x20 / 255.0f, c[1], 1e-5f);
        assertEquals("b", 0x10 / 255.0f, c[2], 1e-5f);
        assertEquals("a", 0x80 / 255.0f, c[3], 1e-5f);
        float[] full = HoloSpiralScene.convertColor(0xFFFFFFFF);
        for (int i = 0; i < 4; i++) assertEquals("全白通道 " + i, 1.0f, full[i], 1e-5f);
    }

    /** modulo360 把任意角度折进 [0,360)，含负角。 */
    @Test
    public void modulo360WrapsIntoRange() {
        assertTrue("0 应保持 0", Math.abs(HoloSpiralScene.modulo360(0f)) < 1e-6f);
        assertTrue("359 应保持 359", Math.abs(HoloSpiralScene.modulo360(359f) - 359f) < 1e-3f);
        assertTrue("360 应折为 0", Math.abs(HoloSpiralScene.modulo360(360f)) < 1e-3f);
        assertTrue("720.5 应折为 0.5", Math.abs(HoloSpiralScene.modulo360(720.5f) - 0.5f) < 1e-2f);
    }

    /**
     * 旋转角推进后仍留在 [0,360)：跑很多帧不能累积成天文数字。
     * 这正是原实现用 modulo360 而不是裸累加的原因。
     */
    @Test
    public void rotationAnglesStayModuloed() {
        HoloSpiralScene s = new HoloSpiralScene();
        s.resetAnimation();
        for (int i = 0; i < 200000; i++) {
            s.advanceRotateAngles(0.016f);
            float in = s.getInnerRotateAngle(), out = s.getOuterRotateAngle();
            assertTrue("第 " + i + " 帧后角度越界 in=" + in + " out=" + out,
                    in >= 0f && in < 360f && out >= 0f && out < 360f);
        }
    }

    /** 背景是 4 个顶点的全屏四边形，上下两条边各取一种端色。 */
    @Test
    public void backgroundQuadTakesCornerColors() {
        HoloSpiralScene s = new HoloSpiralScene();
        s.bgColorTop = 0xFF0000FF;     // 顶：纯蓝
        s.bgColorBottom = 0xFF00FF00;  // 底：纯绿
        float[] data = s.buildBackgroundData();
        assertEquals("背景顶点数 × stride", 4 * HoloSpiralScene.FLOATS_PER_VERTEX, data.length);
        int n = HoloSpiralScene.FLOATS_PER_VERTEX;
        // 第 1、3 个顶点在上边（y=+1），第 2、4 个在下边（y=-1）
        assertTrue("顶点 1 在上边", data[1] > 0f);
        assertTrue("顶点 2 在下边", data[n + 1] < 0f);
        assertEquals("上边取 bgColorTop 的 r", 0f, data[3], 1e-5f);
        assertEquals("上边取 bgColorTop 的 b", 1f, data[5], 1e-5f);
        assertEquals("下边取 bgColorBottom 的 g", 1f, data[n + 4], 1e-5f);
    }
}
