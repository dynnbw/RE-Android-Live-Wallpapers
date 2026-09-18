/*
 * HoloSpiralScene 的约束测试 —— 纯 JVM:
 *
 *   javac -d /tmp/hstest \
 *         tools/holospiral-test/android/content/SharedPreferences.java \
 *         app/src/main/java/com/reandroid/wallpaper/holospiral/HoloSpiralScene.java \
 *         tools/holospiral-test/HoloSpiralSceneTest.java
 *   java -cp /tmp/hstest com.reandroid.wallpaper.holospiral.HoloSpiralSceneTest
 *
 * (SharedPreferences 用 tools/ 下的替身:真身是 Android 类,纯 JVM 编不过。
 *  只列这几个源文件,别把真身也放进来。)
 *
 * 这些几何数据直接喂给着色器,错了就是画面不对,所以这里钉住的是**结构**而不是数值:
 * 顶点数/stride 对齐、螺旋半径恒定、z 沿深度单调展开、颜色按 sin(radians/2) 插值
 * （会外插出端点色,见 HoloSpiralScene 的注释）、旋转角不越界。
 * 数值本身是眼调的,抄一遍没有意义。
 */
package com.reandroid.wallpaper.holospiral;

public final class HoloSpiralSceneTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testVertexCountAndStride();
        testSpiralRadiusIsConstant();
        testDepthSpansSymmetrically();
        testColorInterpolation();
        testColorConversion();
        testModulo360WrapsIntoRange();
        testRotationAdvancesAreModuloed();
        testBackgroundCorners();

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }

    /** 顶点数 × stride 必须与数组长度严格对应，否则 GL 侧按 stride 读会越界。 */
    private static void testVertexCountAndStride() {
        HoloSpiralScene s = new HoloSpiralScene();
        for (int count : new int[] {1, 2, 50, 100, 333}) {
            float[] data = s.buildSpiralData(count, 30.0f, 10.0f, 23.0f, 0xFF112233, 0xFF445566);
            assertEquals("count=" + count + " 的数组长度", count * HoloSpiralScene.FLOATS_PER_VERTEX,
                    data.length);
        }
        System.out.println("顶点数 × stride 对齐（FLOATS_PER_VERTEX="
                + HoloSpiralScene.FLOATS_PER_VERTEX + "）");
    }

    /** 每个点都在以 radius 为半径的圆上（xy 平面），与深度无关。 */
    private static void testSpiralRadiusIsConstant() {
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
        System.out.println("螺旋半径恒定 = " + radius);
    }

    /** z 从 -depth/2 线性走到 +depth/2，首尾各差一个步长。 */
    private static void testDepthSpansSymmetrically() {
        HoloSpiralScene s = new HoloSpiralScene();
        float depth = 40.0f;
        int count = 9;
        float[] data = s.buildSpiralData(count, depth, 5.0f, 23.0f, 0xFF000000, 0xFFFFFFFF);
        float first = data[2];
        float last = data[(count - 1) * HoloSpiralScene.FLOATS_PER_VERTEX + 2];
        assertEquals("首点 z", -depth / 2.0f, first);
        assertTrue("末点 z 应接近 +depth/2（实际 " + last + "）",
                Math.abs(last - (depth / 2.0f - depth / count)) < 1e-4f);
        // 单调递增
        float prev = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            float z = data[i * HoloSpiralScene.FLOATS_PER_VERTEX + 2];
            assertTrue("z 应单调递增", z > prev);
            prev = z;
        }
        System.out.println("z 沿深度单调展开，首点 = " + first);
    }

    /**
     * 颜色插值的三条性质（**不是**"落在两端点之间" —— 因子是 sin(radians/2)，
     * 值域 [-1,1]，会外插出去，见 HoloSpiralScene 的注释）：
     *
     *   1. radians=0 处等于 primary（sin(0)=0）；
     *   2. 每个通道都落在外插包络 [2p-s, s] 内（因为因子被 sin 限制在 ±1）；
     *   3. 一个完整周期内确实摆到两端（说明两个颜色都用上了，没有退化成单色）。
     */
    private static void testColorInterpolation() {
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
            assertEquals("第 0 点通道 " + c + " 应等于 primary", p[c], data[off + c]);
        }

        for (int c = 0; c < 4; c++) {
            float lo = Math.min(2 * p[c] - q[c], q[c]);
            float hi = Math.max(2 * p[c] - q[c], q[c]);
            float minSeen = Float.MAX_VALUE, maxSeen = Float.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                float v = data[i * HoloSpiralScene.FLOATS_PER_VERTEX + off + c];
                if (v < lo - 1e-4f || v > hi + 1e-4f) {
                    failures++;
                    System.out.println("失败: 通道 " + c + " 越出外插包络（v=" + v
                            + " 包络 " + lo + ".." + hi + "）");
                    return;
                }
                minSeen = Math.min(minSeen, v);
                maxSeen = Math.max(maxSeen, v);
            }
            float span = Math.abs(q[c] - p[c]);
            if (span > 1e-3f) {
                assertTrue("通道 " + c + " 应摆到两端（min=" + minSeen + " max=" + maxSeen + "）",
                        minSeen < p[c] - span * 0.05f && maxSeen > p[c] + span * 0.05f);
            }
        }
        System.out.println("颜色按 sin(radians/2) 外插，第 0 点 = primary");
    }

    /** ARGB → RGBA 各通道归一化，且顺序不能反（R 在前、A 在后）。 */
    private static void testColorConversion() {
        float[] c = HoloSpiralScene.convertColor(0x80402010);   // a=80 r=40 g=20 b=10
        assertEquals("r", 0x40 / 255.0f, c[0]);
        assertEquals("g", 0x20 / 255.0f, c[1]);
        assertEquals("b", 0x10 / 255.0f, c[2]);
        assertEquals("a", 0x80 / 255.0f, c[3]);
        float[] full = HoloSpiralScene.convertColor(0xFFFFFFFF);
        for (int i = 0; i < 4; i++) assertEquals("全白通道 " + i, 1.0f, full[i]);
        System.out.println("convertColor 顺序为 RGBA");
    }

    /** modulo360 把任意角度折进 [0,360)，含负角。 */
    private static void testModulo360WrapsIntoRange() {
        assertTrue("0 应保持 0", Math.abs(HoloSpiralScene.modulo360(0f)) < 1e-6f);
        assertTrue("359 应保持 359", Math.abs(HoloSpiralScene.modulo360(359f) - 359f) < 1e-3f);
        assertTrue("360 应折为 0", Math.abs(HoloSpiralScene.modulo360(360f)) < 1e-3f);
        assertTrue("720.5 应折为 0.5", Math.abs(HoloSpiralScene.modulo360(720.5f) - 0.5f) < 1e-2f);
        System.out.println("modulo360 折进 [0,360)");
    }

    /**
     * 旋转角推进后仍留在 [0,360)：跑很多帧不能累积成天文数字。
     * 这正是原实现用 modulo360 而不是裸累加的原因。
     */
    private static void testRotationAdvancesAreModuloed() {
        HoloSpiralScene s = new HoloSpiralScene();
        s.resetAnimation();
        for (int i = 0; i < 200000; i++) {
            s.advanceRotateAngles(0.016f);
            float in = s.getInnerRotateAngle(), out = s.getOuterRotateAngle();
            if (in < 0f || in >= 360f || out < 0f || out >= 360f) {
                failures++;
                System.out.println("失败: 第 " + i + " 帧后角度越界 in=" + in + " out=" + out);
                return;
            }
        }
        System.out.println("20 万帧后仍在 [0,360)：in=" + s.getInnerRotateAngle()
                + " out=" + s.getOuterRotateAngle());
    }

    /** 背景是 4 个顶点的全屏四边形，上下两条边各取一种端色。 */
    private static void testBackgroundCorners() {
        HoloSpiralScene s = new HoloSpiralScene();
        s.bgColorTop = 0xFF0000FF;     // 顶：纯蓝
        s.bgColorBottom = 0xFF00FF00;  // 底：纯绿
        float[] data = s.buildBackgroundData();
        assertEquals("背景顶点数 × stride", 4 * HoloSpiralScene.FLOATS_PER_VERTEX, data.length);
        int n = HoloSpiralScene.FLOATS_PER_VERTEX;
        // 第 1、3 个顶点在上边（y=+1），第 2、4 个在下边（y=-1）
        assertTrue("顶点 1 在上边", data[1] > 0f);
        assertTrue("顶点 2 在下边", data[n + 1] < 0f);
        assertEquals("上边取 bgColorTop 的 r", 0f, data[3]);
        assertEquals("上边取 bgColorTop 的 b", 1f, data[5]);
        assertEquals("下边取 bgColorBottom 的 g", 1f, data[n + 4]);
        System.out.println("背景四边形 4 顶点，上下取端色");
    }

    private static void assertTrue(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("失败: " + name);
        }
    }

    private static void assertEquals(String name, float expect, float actual) {
        if (Math.abs(expect - actual) > 1e-5f) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void assertEquals(String name, int expect, int actual) {
        if (expect != actual) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }
}
