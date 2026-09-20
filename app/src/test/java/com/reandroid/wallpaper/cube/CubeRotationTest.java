package com.reandroid.wallpaper.cube;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 立方体触摸旋转(四元数视角)回归测试 —— 纯 JVM,不需要设备。
 *
 * <p>覆盖:单位姿态是恒等变换;单轴拖拽与原版欧拉公式等价;拖拽方向符合直觉
 * (指尖按住的那一面跟着手指走);增量恒在屏幕坐标系里左乘(不会退化成
 * 姿态相关的旋转);长时间累乘后仍是正交的单位旋转;反向拖拽可回到原姿态。
 */
public class CubeRotationTest {

    private static final float EPS = 1.0E-4f;

    /** 原版的旋转公式(Ry(yrot) · Rx(xrot)),用来做等价性对照。 */
    private static float[] originalRotate(float xrot, float yrot, float x, float y, float z) {
        float newZ = (float) (Math.cos(xrot) * z - Math.sin(xrot) * y);
        float newY = (float) (Math.sin(xrot) * z + Math.cos(xrot) * y);
        float newX = (float) (Math.sin(yrot) * newZ + Math.cos(yrot) * x);
        newZ = (float) (Math.cos(yrot) * newZ - Math.sin(yrot) * x);
        return new float[] { newX, newY, newZ };
    }

    private static float[] matrix(CubeViewRotation view) {
        float[] m = new float[9];
        view.toMatrix(m);
        return m;
    }

    private static float[] applyMatrix(float[] m, float x, float y, float z) {
        return new float[] {
                m[0] * x + m[1] * y + m[2] * z,
                m[3] * x + m[4] * y + m[5] * z,
                m[6] * x + m[7] * y + m[8] * z,
        };
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    /** 原版单轴公式对应的 3x3 矩阵。 */
    private static float[] yawMatrix(float yrot) {
        float c = (float) Math.cos(yrot), s = (float) Math.sin(yrot);
        return new float[] { c, 0f, s, 0f, 1f, 0f, -s, 0f, c };
    }

    private static float[] pitchMatrix(float xrot) {
        float c = (float) Math.cos(xrot), s = (float) Math.sin(xrot);
        return new float[] { 1f, 0f, 0f, 0f, c, s, 0f, -s, c };
    }

    /** 透视投影(与原版一致),返回 {屏幕上向右, 屏幕上向下}。 */
    private static float[] project(float[] p) {
        float scale = 4.0f - p[2] / 400.0f;
        return new float[] { p[0] / scale, p[1] / scale };
    }

    /** 初始姿态为单位四元数,且变换不改变任何顶点。 */
    @Test
    public void identityIsNoOp() {
        CubeViewRotation view = new CubeViewRotation();
        assertTrue("初始姿态为单位四元数", view.isIdentity());
        float[] m = matrix(view);
        float[] p = applyMatrix(m, 0.37f, -1.24f, 2.5f);
        assertEquals("单位姿态不改变顶点", 0.37f, p[0], EPS);
        assertEquals("单位姿态不改变顶点", -1.24f, p[1], EPS);
        assertEquals("单位姿态不改变顶点", 2.5f, p[2], EPS);
    }

    /** 纯偏航拖拽累加 1.0rad == 原版 yrot = 1.0。 */
    @Test
    public void yawMatchesOriginal() {
        CubeViewRotation view = new CubeViewRotation();
        for (int i = 0; i < 100; i++) {
            view.apply(0.01f, 0f);
        }
        float[] m = matrix(view);
        for (float[] p : PROBES) {
            float[] expect = originalRotate(0f, 1.0f, p[0], p[1], p[2]);
            float[] actual = applyMatrix(m, p[0], p[1], p[2]);
            assertEquals("纯偏航与原版一致 x", expect[0], actual[0], EPS);
            assertEquals("纯偏航与原版一致 y", expect[1], actual[1], EPS);
            assertEquals("纯偏航与原版一致 z", expect[2], actual[2], EPS);
        }
    }

    /** 纯俯仰拖拽累加 1.0rad == 原版 xrot = 1.0。 */
    @Test
    public void pitchMatchesOriginal() {
        CubeViewRotation view = new CubeViewRotation();
        for (int i = 0; i < 100; i++) {
            view.apply(0f, 0.01f);
        }
        float[] m = matrix(view);
        for (float[] p : PROBES) {
            float[] expect = originalRotate(1.0f, 0f, p[0], p[1], p[2]);
            float[] actual = applyMatrix(m, p[0], p[1], p[2]);
            assertEquals("纯俯仰与原版一致 x", expect[0], actual[0], EPS);
            assertEquals("纯俯仰与原版一致 y", expect[1], actual[1], EPS);
            assertEquals("纯俯仰与原版一致 z", expect[2], actual[2], EPS);
        }
    }

    /** 直接操纵:横拖正面往右,竖拖正面往下(屏幕上向下 = mProjectedY 正方向)。 */
    @Test
    public void dragFollowsFinger() {
        float[] front = { 0f, 0f, 1f };

        CubeViewRotation yawed = new CubeViewRotation();
        yawed.apply(0.2f, 0f);
        float[] beforeYaw = project(front);
        float[] afterYaw = project(applyMatrix(matrix(yawed), front[0], front[1], front[2]));
        assertTrue("横拖(向右)正面跟着往右", afterYaw[0] > beforeYaw[0] + 0.01f);
        assertEquals("横拖不改变正面的上下位置", beforeYaw[1], afterYaw[1], EPS);

        CubeViewRotation pitched = new CubeViewRotation();
        pitched.apply(0f, 0.2f);
        float[] beforePitch = project(front);
        float[] afterPitch = project(applyMatrix(matrix(pitched), front[0], front[1], front[2]));
        assertTrue("竖拖(向下)正面跟着往下", afterPitch[1] > beforePitch[1] + 0.01f);
        assertEquals("竖拖不改变正面的左右位置", beforePitch[0], afterPitch[0], EPS);
    }

    /**
     * 增量必须左乘(作用在屏幕坐标系),否则拖拽方向会随姿态漂移。
     * 任意姿态下再拖一点点,结果都应等于 Ry(δ)·R(当前)。
     */
    @Test
    public void incrementsStayInScreenSpace() {
        CubeViewRotation view = new CubeViewRotation();
        view.apply(0.9f, 0.4f);
        view.apply(-1.7f, 2.3f);
        view.apply(3.1f, -0.8f);

        float[] before = matrix(view);
        float delta = 0.05f;

        view.apply(delta, 0f);
        assertMatrixNear("偏航增量作用在屏幕竖直轴", multiply(yawMatrix(delta), before), matrix(view), EPS);

        view.apply(-delta, 0f);
        view.apply(0f, delta);
        assertMatrixNear("俯仰增量作用在屏幕水平轴", multiply(pitchMatrix(delta), before), matrix(view), EPS);
    }

    /** 两万次增量后仍是正交的单位旋转矩阵(无缩放、无剪切、无 NaN)。 */
    @Test
    public void staysOrthonormal() {
        CubeViewRotation view = new CubeViewRotation();
        long seed = 12345L;
        for (int i = 0; i < 20000; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float yaw = ((seed >> 33) % 200 - 100) / 100f * 0.05f;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float pitch = ((seed >> 33) % 200 - 100) / 100f * 0.05f;
            view.apply(yaw, pitch);
        }
        float[] m = matrix(view);
        for (int r = 0; r < 3; r++) {
            float[] row = { m[r * 3], m[r * 3 + 1], m[r * 3 + 2] };
            float len = (float) Math.sqrt(row[0] * row[0] + row[1] * row[1] + row[2] * row[2]);
            assertEquals("行向量保持单位长度", 1f, len, 1.0E-3f);
            assertTrue("矩阵无 NaN", !Float.isNaN(len));
        }
        float dot01 = m[0] * m[3] + m[1] * m[4] + m[2] * m[5];
        float dot02 = m[0] * m[6] + m[1] * m[7] + m[2] * m[8];
        float dot12 = m[3] * m[6] + m[4] * m[7] + m[5] * m[8];
        assertEquals("行向量互相垂直(01)", 0f, dot01, 1.0E-3f);
        assertEquals("行向量互相垂直(02)", 0f, dot02, 1.0E-3f);
        assertEquals("行向量互相垂直(12)", 0f, dot12, 1.0E-3f);
        float det = m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        assertEquals("行列式为 1(纯旋转)", 1f, det, 1.0E-3f);
    }

    /**
     * 反向拖回去应回到原姿态。
     * 单轴是精确可逆的;斜向拖拽绕两个轴、两者不可交换,所以会留下约 a·b 的
     * 换位子残差(0.2rad × 0.15rad ≈ 0.03rad ≈ 1.7°,肉眼不可见,任何
     * 非交换的 trackball 都有这一项)。
     */
    @Test
    public void reverseReturnsToStart() {
        CubeViewRotation yawOnly = new CubeViewRotation();
        yawOnly.apply(0.2f, 0f);
        yawOnly.apply(-0.2f, 0f);
        assertEquals("单轴偏航正反拖拽精确回位", 0f, rotationAngle(yawOnly), 1.0E-5f);

        CubeViewRotation pitchOnly = new CubeViewRotation();
        pitchOnly.apply(0f, 0.2f);
        pitchOnly.apply(0f, -0.2f);
        assertEquals("单轴俯仰正反拖拽精确回位", 0f, rotationAngle(pitchOnly), 1.0E-5f);

        CubeViewRotation diagonal = new CubeViewRotation();
        diagonal.apply(0.2f, 0.15f);
        diagonal.apply(-0.2f, -0.15f);
        float residual = rotationAngle(diagonal);
        assertTrue("斜向正反拖拽残差角 ≤ 换位子量级,实际 " + residual, residual < 0.032f);
    }

    /** 从旋转矩阵求等效转角。 */
    private static float rotationAngle(CubeViewRotation view) {
        float[] m = matrix(view);
        float trace = m[0] + m[4] + m[8];
        return (float) Math.acos(Math.max(-1f, Math.min(1f, (trace - 1f) / 2f)));
    }

    private static final float[][] PROBES = {
            { 1f, 0f, 0f },
            { 0f, 1f, 0f },
            { 0f, 0f, 1f },
            { -0.6f, 0.8f, 0.45f },
            { 0.3f, -0.9f, -0.7f },
    };

    private static void assertMatrixNear(String name, float[] expect, float[] actual, float eps) {
        for (int i = 0; i < 9; i++) {
            assertEquals(name + " 元素[" + i + "]", expect[i], actual[i], eps);
        }
    }
}
