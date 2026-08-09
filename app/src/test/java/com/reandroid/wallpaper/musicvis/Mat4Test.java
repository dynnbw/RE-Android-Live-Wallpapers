package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.Mat4;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for shared Mat4 utility (frustumM, orthoM, multiplyMM, rotateM, scaleM, translateM).
 */
public class Mat4Test {

    // ---- frustumM ----

    @Test
    public void frustumM_symmetricProjection() {
        float[] m = new float[16];
        Mat4.frustumM(m, -1f, 1f, -1f, 1f, 1f, 100f);
        assertEquals("m[0]", 1f, m[0], 0.001f);  // 2n/(r-l) = 2/2 = 1
        assertEquals("m[5]", 1f, m[5], 0.001f);  // 2n/(t-b) = 2/2 = 1
        assertEquals("m[8]", 0f, m[8], 0.001f);  // (r+l)/(r-l) = 0
        assertEquals("m[9]", 0f, m[9], 0.001f);  // (t+b)/(t-b) = 0
        assertEquals("m[11]", -1f, m[11], 0.001f);
    }

    @Test
    public void frustumM_aspectRatio() {
        float[] m = new float[16];
        Mat4.frustumM(m, -1.78f, 1.78f, -1f, 1f, 1f, 6000f);
        assertEquals("m[0] with aspect", 2f * 1f / (1.78f + 1.78f), m[0], 0.01f);
        assertEquals("m[5]", 1f, m[5], 0.001f);
    }

    @Test
    public void frustumM_XYflipped() {
        float[] m = new float[16];
        // X flipped: left=0.5, right=-0.5. Y flipped: bottom=1, top=-1
        Mat4.frustumM(m, 0.5f, -0.5f, 1f, -1f, 1f, 6000f);
        // m[0] = 2n/(right-left) with right=-0.5, left=0.5: 2/(-1) = -2
        assertEquals("m[0] X flipped", -2f, m[0], 0.001f);
        // m[5] = 2n/(top-bottom) with top=-1, bottom=1: 2/(-2) = -1
        assertEquals("m[5] Y flipped", -1f, m[5], 0.001f);
    }

    @Test
    public void frustumM_farPlane() {
        float[] m1 = new float[16];
        Mat4.frustumM(m1, -1f, 1f, -1f, 1f, 1f, 100f);
        float[] m2 = new float[16];
        Mat4.frustumM(m2, -1f, 1f, -1f, 1f, 1f, 6000f);
        // Different far planes → different m[10] and m[14]
        assertNotEquals("Far plane affects m[10]", m1[10], m2[10], 0.001f);
    }

    // ---- orthoM ----

    @Test
    public void orthoM_portrait() {
        float[] m = new float[16];
        Mat4.orthoM(m, -1f, 1f, -1.78f, 1.78f, -1f, 1f);
        assertEquals("m[0]", 1f, m[0], 0.001f);
        assertEquals("m[5]", 2f / (2 * 1.78f), m[5], 0.01f);
        assertEquals("m[10]", -1f, m[10], 0.001f);
        assertEquals("m[15]", 1f, m[15], 0.001f);
    }

    @Test
    public void orthoM_landscape() {
        float[] m = new float[16];
        Mat4.orthoM(m, -1.78f, 1.78f, -1f, 1f, -1f, 1f);
        assertEquals("m[0]", 1f / 1.78f, m[0], 0.01f);
        assertEquals("m[5]", 1f, m[5], 0.001f);
    }

    // ---- multiplyMM ----

    @Test
    public void multiplyMM_identity() {
        float[] id = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        float[] result = new float[16];
        Mat4.multiplyMM(result, id, id);
        assertArrayEquals("I * I = I", id, result, 0.001f);
    }

    @Test
    public void multiplyMM_identityByM() {
        float[] id = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        float[] m = {1,2,3,4, 5,6,7,8, 9,10,11,12, 13,14,15,16};
        float[] result = new float[16];
        Mat4.multiplyMM(result, id, m);
        assertArrayEquals("I * M = M", m, result, 0.001f);
    }

    @Test
    public void multiplyMM_nonIdentity() {
        float[] a = {2,0,0,0, 0,3,0,0, 0,0,4,0, 0,0,0,1};
        float[] b = {1,0,0,0, 0,1,0,0, 0,0,1,0, 5,6,7,1};
        float[] result = new float[16];
        Mat4.multiplyMM(result, a, b);
        // a * b: X scaled by 2, Y by 3, Z by 4, then translate (5,6,7)
        assertEquals("result[12]", 10f, result[12], 0.001f); // 5*2=10
        assertEquals("result[13]", 18f, result[13], 0.001f); // 6*3=18
        assertEquals("result[14]", 28f, result[14], 0.001f); // 7*4=28
    }

    // ---- rotateM ----

    @Test
    public void rotateM_90degreesZ() {
        float[] m = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Mat4.rotateM(m, 90f, 0f, 0f, 1f);
        // 90° CCW around Z: X→Y, Y→-X
        assertTrue("m[0] ≈ 0", Math.abs(m[0]) < 0.001f);
        assertTrue("m[1] ≈ 1", Math.abs(m[1] - 1f) < 0.001f);
        assertTrue("m[4] ≈ -1", Math.abs(m[4] + 1f) < 0.001f);
        assertTrue("m[5] ≈ 0", Math.abs(m[5]) < 0.001f);
    }

    @Test
    public void rotateM_180degreesY() {
        float[] m = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Mat4.rotateM(m, 180f, 0f, 1f, 0f);
        // 180° around Y: X→-X, Z→-Z
        assertTrue("m[0] ≈ -1", Math.abs(m[0] + 1f) < 0.001f);
        assertTrue("m[5] ≈ 1", Math.abs(m[5] - 1f) < 0.001f);
        assertTrue("m[10] ≈ -1", Math.abs(m[10] + 1f) < 0.001f);
    }

    // ---- translateM ----

    @Test
    public void translateM_identity() {
        float[] m = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Mat4.translateM(m, 5f, 10f, 15f);
        assertEquals("m[12] += 5", 5f, m[12], 0.001f);
        assertEquals("m[13] += 10", 10f, m[13], 0.001f);
        assertEquals("m[14] += 15", 15f, m[14], 0.001f);
    }

    // ---- scaleM ----

    @Test
    public void scaleM_identity() {
        float[] m = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        Mat4.scaleM(m, 2f, 3f, 4f);
        assertEquals("m[0] *= 2", 2f, m[0], 0.001f);
        assertEquals("m[5] *= 3", 3f, m[5], 0.001f);
        assertEquals("m[10] *= 4", 4f, m[10], 0.001f);
    }
}
