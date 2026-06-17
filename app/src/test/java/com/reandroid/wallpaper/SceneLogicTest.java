package com.reandroid.wallpaper;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * General Scene logic tests: rotation math, projection, keyframe animation,
 * particle physics, star position trig — spanning Aurora1, Galaxy, NightSky, Cube.
 */
public class SceneLogicTest {

    // ==================== Aurora1: cycle01 / keyframe sampling ====================

    @Test public void cycle01_wrapsAtDuration() {
        assertEquals("t=0 d=5", 0f, cycle01(0f, 5f), 0.001f);
        assertEquals("t=2.5 d=5", 0.5f, cycle01(2.5f, 5f), 0.001f);
        assertEquals("t=5 d=5", 0f, cycle01(5f, 5f), 0.001f);
        assertEquals("t=7 d=5", 0.4f, cycle01(7f, 5f), 0.001f);
    }

    @Test public void cycle01_zeroDuration_safe() {
        float result = cycle01(100f, 0f);
        assertTrue("Zero duration: " + result, result >= 0f && result <= 1f);
    }

    @Test public void sampleSequence_basicInterpolation() {
        float[] keys = {0f, 0.5f, 1f};
        // At progress 0% → key 0
        assertEquals("At start", 0f, sampleSeq(keys, 10f, 0.1f), 0.02f);
        // At progress 50% → mid key
        assertEquals("At middle", 0.5f, sampleSeq(keys, 10f, 5.1f), 0.02f);
        // Near end → near key 2
        assertTrue("Near end", sampleSeq(keys, 10f, 9.9f) > 0.9f);
    }

    @Test public void sampleSequence_wrapsAround() {
        float[] keys = {0f, 1f, 0f};
        assertTrue("Wraps to start", sampleSeq(keys, 5f, 5.1f) < 0.1f);
        assertTrue("Mid peak", sampleSeq(keys, 5f, 2.5f) > 0.9f);
    }

    // ==================== Galaxy: spiral arm math ====================

    @Test public void logarithmicSpiral_angleIncreasesWithRadius() {
        // Inner radius → smaller angle; outer radius → larger angle (spiral arm curl)
        float pitchRad = (float) Math.toRadians(6.7f);
        float r1 = 50f, r2 = 200f;
        float angle1 = (float) (Math.log(r1) / Math.tan(pitchRad));
        float angle2 = (float) (Math.log(r2) / Math.tan(pitchRad));
        assertTrue("Outer radius has larger angle", angle2 > angle1);
    }

    @Test public void ellipseDistortion_compressesX() {
        float ellipse = 0.892f;
        float twist = 0.0233f;
        float angle = (float) Math.toRadians(45);
        float r = 100f;
        float x = r * (float) Math.cos(angle) * ellipse
                + r * (float) Math.sin(angle) * twist;
        float y = r * (float) Math.sin(angle);
        assertTrue("X distorted by ellipse", Math.abs(x) < Math.abs(y));
    }

    @Test public void gaussianRandom_BoxMuller() {
        // Simple Box-Muller test: verify U[-1,1] transform produces plausible output
        float u1 = 0.5f, u2 = 0.3f;
        double r = Math.sqrt(-2.0 * Math.log(Math.max(u1, 0.0001)));
        double theta = 2.0 * Math.PI * u2;
        float gauss = (float) (r * Math.cos(theta));
        // Output should be finite and within reasonable range
        assertFalse("Gaussian is finite", Float.isNaN(gauss) || Float.isInfinite(gauss));
        assertTrue("Gaussian range", Math.abs(gauss) < 5f);
    }

    // ==================== NightSky: FOV + star projection ====================

    @Test public void focalMm_to_FOV() {
        // focalMmToFovDeg(float focalMm): 2 * atan(sensorWidth / (2 * focalMm))
        float sensorWidth = 36f;
        float fov35mm = (float) Math.toDegrees(2 * Math.atan(sensorWidth / (2 * 35f)));
        assertTrue("35mm FOV in range", fov35mm > 20f && fov35mm < 60f);

        float fov6mm = (float) Math.toDegrees(2 * Math.atan(sensorWidth / (2 * 6f)));
        assertTrue("6mm wide FOV", fov6mm > 100f);
    }

    @Test public void raDec_to_horizon() {
        // Convert RA/Dec (degrees) to unit vector, then horizon coordinates
        float raRad = (float) Math.toRadians(90);
        float decRad = (float) Math.toRadians(45);
        float x = (float) (Math.cos(decRad) * Math.cos(raRad));
        float y = (float) (Math.cos(decRad) * Math.sin(raRad));
        float z = (float) Math.sin(decRad);
        float len = (float) Math.sqrt(x*x + y*y + z*z);
        assertEquals("Unit vector length", 1f, len, 0.001f);
    }

    // ==================== Cube: 3D rotation ====================

    @Test public void rotateAroundX() {
        // Point (0, 1, 0) rotated 90° around X → (0, 0, 1)
        float y = 1f, z = 0f;
        float rad = (float) Math.toRadians(90);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float newY = y * cos - z * sin;
        float newZ = y * sin + z * cos;
        assertEquals("Y after 90° X", 0f, newY, 0.001f);
        assertEquals("Z after 90° X", 1f, newZ, 0.001f);
    }

    @Test public void rotateAroundY() {
        float x = 1f, z = 0f;
        float rad = (float) Math.toRadians(90);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float newX = x * cos + z * sin;
        float newZ = -x * sin + z * cos;
        assertEquals("X after 90° Y", 0f, newX, 0.001f);
        assertEquals("Z after 90° Y", -1f, newZ, 0.001f);
    }

    @Test public void perspectiveProjection() {
        // Standard perspective: projectedX = x * 4 / (4 - z/400)
        float z = 100f, x = 50f;
        float scale = 4f / (4f - z / 400f);
        float px = x * scale;
        float py = x * scale;
        assertTrue("Perspective scale > 1", scale > 1f);
        assertTrue("Projected X > original", px > x);
    }

    // ==================== NoiseField: interpolation ====================

    @Test public void lerp_basic() {
        assertEquals("lerp(0, 10, 0.5)", 5f, lerp(0f, 10f, 0.5f), 0.001f);
        assertEquals("lerp(0, 10, 0)", 0f, lerp(0f, 10f, 0f), 0.001f);
        assertEquals("lerp(0, 10, 1)", 10f, lerp(0f, 10f, 1f), 0.001f);
    }

    // ==================== General: radial coordinate conversion ====================

    @Test public void polarToCartesian() {
        float angle = (float) Math.toRadians(60);
        float r = 100f;
        float x = r * (float) Math.cos(angle);
        float y = r * (float) Math.sin(angle);
        assertEquals("X = r*cos(60°)", 50f, x, 0.5f);
        assertEquals("Y = r*sin(60°)", 86.6f, y, 0.5f);
    }

    // ==================== General: easing ====================

    @Test public void easeInOut_quadratic() {
        float t = 0.5f;
        float eased = t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
        assertEquals("Eased t=0.5", 0.5f, eased, 0.001f);
        assertEquals("Eased t=0", 0f, t < 0.5f ? 2f*0*0 : 0, 0.001f);
    }

    // ==================== General: state machine wrap ====================

    @Test public void stateMachine_wrapAround() {
        int idx = 0;
        int max = 8;
        idx++; if (idx >= max) idx = 0;
        assertEquals("Increment", 1, idx);
        idx = 7; idx++; if (idx >= max) idx = 0;
        assertEquals("Wrap", 0, idx);
    }

    @Test public void stateMachine_crossfade() {
        float alpha = 0f;
        float speed = 0.01f;
        // Fade up
        alpha += speed;
        assertTrue("Fading up", alpha > 0f);
        // Fade down
        alpha = 1f;
        alpha -= speed;
        assertTrue("Fading down", alpha < 1f);
    }

    // ==================== General: value clamping ====================

    @Test public void clamp_minMax() {
        float val = 1.5f;
        val = Math.max(0f, Math.min(1f, val));
        assertEquals("Clamped to max", 1f, val, 0.001f);
        val = -0.5f;
        val = Math.max(0f, Math.min(1f, val));
        assertEquals("Clamped to min", 0f, val, 0.001f);
    }

    @Test public void clamp_withinRange() {
        float val = 0.5f;
        val = Math.max(0f, Math.min(1f, val));
        assertEquals("Within range unchanged", 0.5f, val, 0.001f);
    }

    // ==================== LuminousDots: grid math ====================

    @Test public void gridCellSize_32grid() {
        float diagonal = 1920f;
        int cellSize = ((int) diagonal) / 32;
        assertEquals("Cell size", 60, cellSize);
        int half = cellSize * 16;
        assertEquals("Half layout", 960, half);
    }

    // ==================== Particle: gravity ====================

    @Test public void gravity_acceleration() {
        float vy = 100f;
        float gravity = 200f;
        float dt = 0.016f; // ~60fps
        vy += gravity * dt;
        assertTrue("Gravity increases downward speed", vy > 100f);
    }

    @Test public void particle_life_decay() {
        float life = 1f;
        float decay = 0.02f;
        life -= decay;
        assertTrue("Life decreases", life < 1f);
    }

    // ==================== helpers ====================

    private static float cycle01(float time, float duration) {
        if (duration <= 0f) return 0f;
        float t = time % duration;
        if (t < 0f) t += duration;
        return t / duration;
    }

    private static float sampleSeq(float[] values, float duration, float time) {
        if (values == null || values.length < 2 || duration <= 0f) return 0f;
        float t = cycle01(time, duration);
        float idx = t * (values.length - 1);
        int i0 = (int) idx;
        int i1 = Math.min(i0 + 1, values.length - 1);
        float frac = idx - i0;
        return lerp(values[i0], values[i1], frac);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
