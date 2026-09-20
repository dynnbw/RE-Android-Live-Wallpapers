package com.reandroid.wallpaper.earth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Earth 壁纸视图矩阵回归测试 —— 纯 JVM,不需要设备。
 *
 * <p>覆盖:星空随相机旋转(相机转向哪就看见哪片星);天空与相机的旋转部分逐位一致
 * (星星与地表同步扫过屏幕);天空不含相机平移(切机位星野不平移);
 * 黄赤交角保留;一天下来星野相对地表只漂 0.9856°。
 */
public class EarthViewTest {

    private static final float EPS = 1.0E-4f;

    /**
     * 星空必须随相机转:相机转到哪,看到的就是哪片星。
     *
     * <p>取一颗固定在世界空间的星(世界 +X 方向)。相机绕 Y 转 90° 后,它应当正好
     * 落在屏幕中心 —— 相机朝 -Z 看,所以屏幕中心就是 eye 空间的 (0,0,-1)。
     * (未修之前星空视图里没有相机旋转,这颗星的 eye 方向恒为 Rz(23.5°)·(1,0,0),
     * 不随相机变,这就是"星空完全不随摄像机移动"。)
     */
    @Test
    public void skyFollowsCamera() {
        float[] sky = new float[16];
        EarthView.buildSky(sky, camera(0.0f));
        float[] before = transformDir(sky, 1.0f, 0.0f, 0.0f);

        EarthView.buildSky(sky, camera(90.0f));
        float[] after = transformDir(sky, 1.0f, 0.0f, 0.0f);

        // 转了 90° 之后,世界 +X 的星走到屏幕正中
        assertEquals("转 90° 后星星的 eye x", 0.0f, after[0], EPS);
        assertEquals("转 90° 后星星的 eye y", 0.0f, after[1], EPS);
        assertEquals("转 90° 后星星的 eye z", -1.0f, after[2], EPS);

        // 转 90° 就必须实测到 90° 的位移(防"星星恒在屏幕中心"那种假修法)
        assertEquals("转 90° 时星星位置的位移", 90.0f, angleBetween(before, after), 1.0f);

        // 转回去应当回到原位
        EarthView.buildSky(sky, camera(360.0f));
        float[] back = transformDir(sky, 1.0f, 0.0f, 0.0f);
        assertEquals("转满一圈回到原位 x", before[0], back[0], EPS);
        assertEquals("转满一圈回到原位 y", before[1], back[1], EPS);
        assertEquals("转满一圈回到原位 z", before[2], back[2], EPS);
    }

    /**
     * 天空与相机的旋转部分必须逐位一致 —— 这等于"星星和地表以同样的幅度扫过屏幕",
     * 也就是拖拽时地球与星空是一起转的(真实相机绕地球转动的观感)。
     */
    @Test
    public void skyRotationMatchesCamera() {
        float[] view = new float[16];
        float[] sky = new float[16];
        for (float yaw : new float[] { 0.0f, 37.0f, 120.0f, 251.0f, 359.0f }) {
            EarthCamera cam = camera(yaw);
            EarthView.buildCamera(view, cam);
            EarthView.buildSky(sky, cam);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    int i = c * 4 + r;
                    assertEquals("yaw=" + yaw + " 旋转元素 [" + r + "][" + c + "]",
                            view[i], sky[i], EPS);
                }
            }
        }
    }

    /**
     * 天空不能含相机平移:星空在无穷远,三个机位之间切换时星野不该平移。
     * 相机的平移列则是 Rz(黄赤交角) 作用后的机位坐标(顺序 Rz · T · R_cam 的直接结果)。
     */
    @Test
    public void skyDropsCameraTranslation() {
        float[] view = new float[16];
        float[] sky = new float[16];
        for (int id = 0; id < 3; id++) {
            EarthCamera cam = new EarthScene().cameras()[id];
            EarthView.buildCamera(view, cam);
            EarthView.buildSky(sky, cam);

            assertEquals("机位 " + id + " 天空平移 x", 0.0f, sky[12], EPS);
            assertEquals("机位 " + id + " 天空平移 y", 0.0f, sky[13], EPS);
            assertEquals("机位 " + id + " 天空平移 z", 0.0f, sky[14], EPS);
            assertEquals("机位 " + id + " 天空 w", 1.0f, sky[15], EPS);

            // 机位坐标经 Rz(23.5°) 后的期望值
            double t = Math.toRadians(EarthView.UNIVERSE_TILT_DEG);
            float ex = (float) (Math.cos(t) * cam.x - Math.sin(t) * cam.y);
            float ey = (float) (Math.sin(t) * cam.x + Math.cos(t) * cam.y);
            assertEquals("机位 " + id + " 相机平移 x", ex, view[12], EPS);
            assertEquals("机位 " + id + " 相机平移 y", ey, view[13], EPS);
            assertEquals("机位 " + id + " 相机平移 z", cam.z, view[14], EPS);

            // 相机平移非零,才能证明上面对天空的断言不是因为矩阵整体是单位阵
            assertTrue("机位 " + id + " 相机平移应非零",
                    Math.abs(view[12]) + Math.abs(view[13]) + Math.abs(view[14]) > 1.0f);
        }
    }

    /**
     * 黄赤交角必须保留:它是整个场景的姿态,星空跟着倾斜才对。
     * 相机不转时,世界 +X 的星应落在 Rz(23.5°)·(1,0,0)。
     */
    @Test
    public void universeTiltKept() {
        float[] sky = new float[16];
        EarthView.buildSky(sky, camera(0.0f));
        float[] d = transformDir(sky, 1.0f, 0.0f, 0.0f);
        double t = Math.toRadians(EarthView.UNIVERSE_TILT_DEG);
        assertEquals("倾斜后的 x", (float) Math.cos(t), d[0], EPS);
        assertEquals("倾斜后的 y", (float) Math.sin(t), d[1], EPS);
        assertEquals("倾斜后的 z", 0.0f, d[2], EPS);
    }

    /**
     * 一天下来,星野相对地球表面只漂 0.9856°(≈4 分钟)—— 星星每天提早 4 分钟升起。
     *
     * <p>这条把两个层的速率锁在一起:地球自转 +360°/天(时钟推出),
     * 星空在同一个世界空间里 -360.9856°/天(恒星日),两者之差就是那 1°。
     * 单独看 skyAngleY 是看不出对错的,必须和 earthAngleY 一起比。
     */
    @Test
    public void starsStayLockedToGround() {
        EarthScene day0 = new EarthScene();
        day0.setClock(0L, 0, 1, 0.0);
        EarthScene day1 = new EarthScene();
        day1.setClock(0L, 0, 2, 1.0);

        float[] e0 = { 1.0f, 0.0f, 0.0f };
        float[] e1 = { 1.0f, 0.0f, 0.0f };
        rotateY(e0, day0.earthAngleY());
        rotateY(e1, day1.earthAngleY());

        float[] s0 = { 1.0f, 0.0f, 0.0f };
        float[] s1 = { 1.0f, 0.0f, 0.0f };
        rotateY(s0, day0.skyAngleY());
        rotateY(s1, day1.skyAngleY());

        float rel0 = angleBetween(e0, s0);
        float rel1 = angleBetween(e1, s1);
        float drift = Math.abs(rel1 - rel0);
        if (drift > 180.0f) drift = 360.0f - drift;
        assertEquals("星野相对地表一天漂移", 0.9856f, drift, 0.01f);
    }

    // ------------------------------------------------------------------

    private static EarthCamera camera(float yaw) {
        EarthCamera cam = new EarthScene().cameras()[EarthScene.CAMERA_DISTANCE];
        cam.angleY = yaw;
        return cam;
    }

    /** 用矩阵的旋转部分变换一个方向(不含平移,也不做透视除法)。 */
    private static float[] transformDir(float[] m, float x, float y, float z) {
        return new float[] {
                m[0] * x + m[4] * y + m[8] * z,
                m[1] * x + m[5] * y + m[9] * z,
                m[2] * x + m[6] * y + m[10] * z,
        };
    }

    /** 就地绕 Y 轴旋转一个方向。 */
    private static void rotateY(float[] v, float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float x = v[0], z = v[2];
        v[0] = c * x + s * z;
        v[2] = -s * x + c * z;
    }

    /** 两个方向之间的夹角(度)。 */
    private static float angleBetween(float[] a, float[] b) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        d /= (la * lb);
        if (d > 1.0) d = 1.0;
        if (d < -1.0) d = -1.0;
        return (float) Math.toDegrees(Math.acos(d));
    }
}
