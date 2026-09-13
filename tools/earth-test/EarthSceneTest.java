/*
 * Sony Earth 壁纸纯逻辑回归测试 —— 纯 JVM,不需要设备:
 *
 *   javac -d /tmp/earthtest \
 *         app/src/main/java/com/reandroid/wallpaper/earth/EarthCamera.java \
 *         app/src/main/java/com/reandroid/wallpaper/earth/EarthScene.java \
 *         app/src/main/java/com/reandroid/wallpaper/earth/EarthGlobe.java \
 *         tools/earth-test/EarthSceneTest.java
 *   java -cp /tmp/earthtest com.reandroid.wallpaper.earth.EarthSceneTest
 *
 * 覆盖:网格解析与归一化(正球 + 法线重算 + UV 与原版一致);
 *       地球自转角公式;云层 720°/天;月球轨道;三镜头互不相同;
 *       惯性衰减与帧率无关。
 */
package com.reandroid.wallpaper.earth;

import java.io.FileInputStream;
import java.io.InputStream;

public final class EarthSceneTest {

    private static int failures = 0;
    private static final String OBJ_PATH = "app/src/main/assets/earth/data/globe.obj";

    public static void main(String[] args) throws Exception {
        testGlobeGeometry();
        testGlobeUvMatchesOriginal();
        testSubsolarLongitude();
        testSiderealDrift();
        testCloudRotation();
        testMoonOrbit();
        testCamerasDistinct();
        testInertiaFrameRateIndependent();
        if (failures == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println(failures + " 个用例失败");
            System.exit(1);
        }
    }

    /** 网格必须被归一化成正球,且法线等于归一化位置。 */
    private static void testGlobeGeometry() throws Exception {
        EarthGlobe g = loadGlobe();
        // 源文件 4290 个 v / 4357 个 vt。UV 接缝上同一条经线要 u=0 与 u=1 两个顶点,
        // 所以展开后的顶点数等于 vt 数(4357)而不是 v 数 —— 这是正确的。
        assertTrue("顶点数应为 4357(实际 " + g.vertexCount + ")", g.vertexCount == 4357);
        assertTrue("展开后顶点数不应超过源 vt 数", g.vertexCount == 4357);
        assertTrue("三角数应为 8576(实际 " + g.triangleCount + ")", g.triangleCount == 8576);
        assertTrue("索引应能放进 short", g.vertexCount < Short.MAX_VALUE);

        float minR = Float.MAX_VALUE;
        float maxR = 0.0f;
        float maxNormalErr = 0.0f;
        for (int i = 0; i < g.vertexCount; i++) {
            int o = i * EarthGlobe.FLOATS_PER_VERTEX;
            float x = g.vertices[o], y = g.vertices[o + 1], z = g.vertices[o + 2];
            float nx = g.vertices[o + 3], ny = g.vertices[o + 4], nz = g.vertices[o + 5];
            float r = (float) Math.sqrt(x * x + y * y + z * z);
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            // 法线与"位置的归一化方向"一致
            float dx = nx - x / r, dy = ny - y / r, dz = nz - z / r;
            maxNormalErr = Math.max(maxNormalErr, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        assertEquals("最小半径", EarthGlobe.NORMALIZED_RADIUS, minR, 1.0E-5f);
        assertEquals("最大半径", EarthGlobe.NORMALIZED_RADIUS, maxR, 1.0E-5f);
        assertTrue("法线应等于归一化位置(最大误差 " + maxNormalErr + ")", maxNormalErr < 1.0E-5f);

        // 索引不越界
        int bad = 0;
        for (short idx : g.indices) {
            if (idx < 0 || idx >= g.vertexCount) bad++;
        }
        assertTrue("索引越界数应为 0(实际 " + bad + ")", bad == 0);
    }

    /**
     * UV 必须与原版网格逐点一致,否则贴图会转到别处去。
     * 探针取自原版实测:+X 赤道 u=0.531/v=0.5、+Z 赤道 u=0.281/v=0.5、
     * 北极 u=0.5/v=0.0、南极 u=0.5/v=1.0。
     */
    private static void testGlobeUvMatchesOriginal() throws Exception {
        EarthGlobe g = loadGlobe();
        assertUvNear(g, 1.0f, 0.0f, 0.0f, 0.531f, 0.5f, "+X 赤道");
        assertUvNear(g, 0.0f, 0.0f, 1.0f, 0.281f, 0.5f, "+Z 赤道");
        assertUvNear(g, 0.0f, 1.0f, 0.0f, 0.5f, 0.0f, "北极");
        assertUvNear(g, 0.0f, -1.0f, 0.0f, 0.5f, 1.0f, "南极");
    }

    private static void assertUvNear(EarthGlobe g, float tx, float ty, float tz,
                                     float expectU, float expectV, String label) {
        int best = -1;
        float bd = 9.0f;
        for (int i = 0; i < g.vertexCount; i++) {
            int o = i * EarthGlobe.FLOATS_PER_VERTEX;
            float nx = g.vertices[o + 3], ny = g.vertices[o + 4], nz = g.vertices[o + 5];
            float d = (nx - tx) * (nx - tx) + (ny - ty) * (ny - ty) + (nz - tz) * (nz - tz);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        assertTrue(label + " 找不到对应顶点", best >= 0);
        int o = best * EarthGlobe.FLOATS_PER_VERTEX;
        float u = g.vertices[o + 6];
        float v = g.vertices[o + 7];
        assertEquals(label + " u", expectU, u, 0.01f);
        assertEquals(label + " v", expectV, v, 0.01f);
    }

    /**
     * 太阳直射经度 = -(UTC 距正午的小时数) × 15°。
     * 纬度用不着管，这里只验证经度（也就是"当地时间对不对"）。
     *
     * <p>角度与经度的关系：直射经度 = 75° + earthAngleY（常量 75 由原版那个 -75 定出，
     * 它保证 UTC 正午时直射经度为 0°）。
     */
    private static void testSubsolarLongitude() {
        // UTC 正午 → 直射 0°
        assertEquals("UTC 正午直射经度", 0.0f, subsolarLon(0L, 0), 0.5f);
        // UTC 06:00 → 直射 90°E
        assertEquals("UTC 06:00", 90.0f, subsolarLon(-6L * 3600 * 1000, 0), 0.5f);
        // UTC 18:00 → 直射 90°W
        assertEquals("UTC 18:00", -90.0f, subsolarLon(6L * 3600 * 1000, 0), 0.5f);
        // 关键回归：本地 15:50 @ UTC+8 → UTC 07:50 → 直射 62.5°E（印度应是下午）
        long localMs = ((15 - 12) * 3600L + 50 * 60L) * 1000L;
        assertEquals("本地 15:50 UTC+8 → 直射经度", 62.5f,
                subsolarLon(localMs, 8 * 3600 * 1000), 1.0f);
        // 时区不影响直射经度：同一 UTC 时刻，换个时区结果必须一样
        float a = subsolarLon(localMs, 8 * 3600 * 1000);
        float b = subsolarLon(localMs - 8 * 3600 * 1000, 0);
        assertEquals("同一 UTC 时刻换时区结果不变", a, b, 1.0E-3f);
    }

    /** 由"本地距正午毫秒 + 时区偏移"算出太阳直射经度（东经为正）。 */
    private static float subsolarLon(long localMsSinceNoon, int tzRawOffsetMs) {
        EarthScene s = new EarthScene();
        s.setClock(localMsSinceNoon, tzRawOffsetMs, 1, 0.0);
        float lon = 75.0f + s.earthAngleY();
        while (lon > 180.0f) lon -= 360.0f;
        while (lon < -180.0f) lon += 360.0f;
        return lon;
    }

    /** 星空按恒星日漂移：每天相对地球表面多转约 1°（≈4 分钟）。 */
    private static void testSiderealDrift() {
        EarthScene s = new EarthScene();
        s.setClock(0L, 0, 1, 0.0);
        float day0 = s.skyAngleY();
        s.setClock(0L, 0, 2, 1.0);
        float day1 = s.skyAngleY();
        float drift = Math.abs(day1 - day0);
        if (drift > 180.0f) drift = 360.0f - drift;
        assertEquals("一天漂移角度", 0.9856f, drift, 0.01f);
    }

    /**
     * 云层自转速率 = 0.008333334 度/秒(一天 720°)。
     * 只跑 1 小时(30°)—— 跑满一天会绕圈回到起点,验证不到速率还会被 float 累积误差干扰。
     */
    private static void testCloudRotation() {
        EarthScene s = new EarthScene();
        for (int i = 0; i < 3600; i++) {
            s.update(1.0f);
        }
        assertEquals("1 小时后云层角度", 30.0f, s.cloudAngleY(), 0.01f);

        EarthScene s2 = new EarthScene();
        for (int i = 0; i < 36000; i++) {   // 10 小时 = 300°
            s2.update(1.0f);
        }
        assertEquals("10 小时后云层角度", 300.0f, s2.cloudAngleY(), 1.0E-3f);
    }

    /** 月球轨道：角度 = 第几天 × 13.186813；位置落在半径 4 的圆上。 */
    private static void testMoonOrbit() {
        EarthScene s = new EarthScene();
        s.setClock(0L, 0, 10, 0.0);
        assertEquals("第 10 天的月球角", 10 * EarthScene.MOON_DEGREES_PER_DAY, s.moonAngleY(), 1.0E-3f);
        float r = (float) Math.sqrt(s.moonX() * s.moonX() + s.moonZ() * s.moonZ());
        assertEquals("轨道半径", EarthScene.MOON_ORBIT_RADIUS, r, 1.0E-3f);

        s.setClock(0L, 0, 100, 0.0);
        float r2 = (float) Math.sqrt(s.moonX() * s.moonX() + s.moonZ() * s.moonZ());
        assertEquals("第 100 天仍在同一圆上", EarthScene.MOON_ORBIT_RADIUS, r2, 1.0E-3f);
    }

    /**
     * 三个镜头的位置必须互不相同。
     * 原版反编译后把数组写成了 {camera, camera, camera}（三格同一对象），
     * 这个用例就是防它复发的。
     */
    private static void testCamerasDistinct() {
        EarthScene s = new EarthScene();
        EarthCamera[] cams = s.cameras();
        assertTrue("应有三个镜头", cams.length == 3);
        for (int i = 0; i < cams.length; i++) {
            assertEquals("镜头 " + i + " id", i, cams[i].id);
            for (int j = i + 1; j < cams.length; j++) {
                boolean same = cams[i].x == cams[j].x && cams[i].y == cams[j].y && cams[i].z == cams[j].z;
                assertTrue("镜头 " + i + " 与 " + j + " 位置不能相同", !same);
            }
        }
        // 点击循环 0→1→2→0
        assertEquals("初始镜头", EarthScene.CAMERA_DISTANCE, s.activeCameraId());
        s.onTap();
        assertEquals("点一次", EarthScene.CAMERA_CLOSEUP, s.activeCameraId());
        s.onTap();
        assertEquals("点两次", EarthScene.CAMERA_CORNER, s.activeCameraId());
        s.onTap();
        assertEquals("点三次回到起点", EarthScene.CAMERA_DISTANCE, s.activeCameraId());
    }

    /** 惯性衰减必须与帧率无关：同样跑 1 秒，16ms 与 33ms 步长的结果应接近。 */
    private static void testInertiaFrameRateIndependent() {
        float slow = spinAfterDrag(16.0f);
        float fast = spinAfterDrag(33.0f);
        float diff = Math.abs(slow - fast);
        assertTrue("惯性应与帧率无关 16ms=" + slow + " 33ms=" + fast + " 差 " + diff, diff < 1.0f);
    }

    /** 给一次拖拽，然后以固定步长跑 1 秒，返回当前镜头的 angleY 增量。 */
    private static float spinAfterDrag(float dtMs) {
        EarthScene s = new EarthScene();
        s.setSpinDegPerSec(0.0f);           // 关掉空闲自转，只看惯性
        EarthCamera c = s.activeCamera();
        float before = c.angleY;
        s.drag(120.0f);
        int steps = Math.round(1000.0f / dtMs);
        for (int i = 0; i < steps; i++) {
            s.update(dtMs / 1000.0f);
        }
        return Math.abs(c.angleY - before);
    }

    private static EarthGlobe loadGlobe() throws Exception {
        InputStream in = new FileInputStream(OBJ_PATH);
        try {
            return EarthGlobe.load(in);
        } finally {
            in.close();
        }
    }

    private static void assertTrue(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("失败: " + name);
        }
    }

    private static void assertEquals(String name, float expect, float actual, float eps) {
        if (Math.abs(expect - actual) > eps) {
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
