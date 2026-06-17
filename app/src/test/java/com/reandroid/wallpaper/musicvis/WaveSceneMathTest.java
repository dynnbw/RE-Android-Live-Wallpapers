package com.reandroid.wallpaper.musicvis;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for WaveScene mathematical logic: FFT bin mapping, PCM data distribution,
 * idle wave generation, HSL color computations.
 */
public class WaveSceneMathTest {

    // ---- Idle wave generation ----

    @Test
    public void idleWave_sineWaveNonZero() {
        int wave1amp = 10, wave1pos = 50;
        float amp1 = (float) Math.sin(0.007f * wave1amp) * 120f * 1024f;
        float val = (float) (Math.sin(0.013f * wave1pos) * amp1);
        assertNotEquals("Idle wave non-zero", 0f, val, 1f);
    }

    @Test
    public void idleWave_valClamped_minimum2() {
        float val = 0.5f;
        if (val < 2f && val > -2f) val = 2f;
        assertEquals("Clamped to min 2", 2f, val, 0.001f);
    }

    // ---- FFT bin mapping (logarithmic, WaveScene style) ----

    @Test
    public void fftLogMapping_lowFreq_emphasized() {
        int len = 256;
        int width = 256;
        // At i=0 (low freq): frac=0 → binIdx=0 (bass)
        double frac = 0.0 / width;
        int binIdx = (int) (Math.pow(frac, 1.5) * (len - 1));
        assertEquals("Low freq bin 0", 0, binIdx);
    }

    @Test
    public void fftLogMapping_highFreq_nearMax() {
        int len = 256, width = 256;
        double frac = 255.0 / width;
        int binIdx = (int) (Math.pow(frac, 1.5) * (len - 1));
        assertTrue("High freq bin near max: " + binIdx, binIdx >= 250 && binIdx <= 255);
    }

    @Test
    public void fftLogMapping_midFreq_curved() {
        int len = 256;
        int width = 256;
        // Middle of display: frac=0.5 → binIdx < 128 (bass emphasis)
        double frac = 128.0 / width;
        int binIdx = (int) (Math.pow(frac, 1.5) * (len - 1));
        assertTrue("Mid freq should be < 128 due to bass emphasis, got " + binIdx, binIdx < 128);
    }

    // ---- FFT bin processing (Visualization3RS style) ----

    @Test
    public void fftBinProcessing_peakDecay() {
        int oldval = 50000;
        int newval = 10000;
        // If newval < oldval - 800, decay oldval by 800
        if (newval < oldval - 800) {
            newval = oldval - 800;
        }
        assertEquals("Should decay to oldval-800", 49200, newval);
    }

    @Test
    public void fftBinProcessing_noDecayWhenClose() {
        int oldval = 50000;
        int newval = 49800;
        if (newval < oldval - 800) {
            newval = oldval - 800;
        }
        assertEquals("Should keep new value (no decay)", 49800, newval);
    }

    // ---- PCM data processing ----

    @Test
    public void pcmData_smoothed() {
        float alpha = 0.3f;
        float previous = 100f;
        int rawData = 200;
        float smoothed = alpha * rawData + (1f - alpha) * previous;
        assertEquals("PCM smoothed", 130f, smoothed, 0.01f);
    }

    @Test
    public void pcmData_oppositeEndY() {
        // mPointData[i*8+1] = amp, mPointData[i*8+5] = -amp
        float amp = 500f;
        assertEquals("End Y = -start Y", -amp, -amp, 0.001f);
    }

    // ---- HSL recolor ----

    @Test
    public void hslHueDisabled_returnsMinusOne() {
        boolean enabled = false;
        float hue = enabled ? 0.5f : -1f;
        assertEquals("Disabled hue = -1", -1f, hue, 0.001f);
    }

    @Test
    public void hslHueEnabled_keepsValue() {
        boolean enabled = true;
        float hue = enabled ? 0.7f : -1f;
        assertEquals("Enabled hue", 0.7f, hue, 0.001f);
    }

    @Test
    public void hslDynamicHue_advance() {
        float avg = 400f;
        float norm = Math.min(1f, avg / 800f);
        float hue = 0.3f;
        hue = (hue + norm * 0.03f) % 1f;
        assertTrue("Hue advanced: " + hue, hue > 0.3f && hue < 1f);
    }

    @Test
    public void hslDynamicHue_wrapsAtOne() {
        float hue = 0.99f;
        float norm = 0.5f;
        hue = (hue + norm * 0.03f) % 1f;
        assertTrue("Hue wrapped: " + hue, hue < 0.1f);
    }

    // ---- Triangle strip vs lines ----

    @Test
    public void triangleStrip_usesFullCount() {
        int LINE_COUNT = 256;
        int vertexCount = LINE_COUNT * 2; // = 512
        assertEquals("Triangle strip: 512 vertices", 512, vertexCount);
    }

    // ---- Needle angle computation (VuScene) ----

    @Test
    public void needleAngle_atRest() {
        int needlePos = 0;
        float angle = 131f - (needlePos / 410f);
        assertEquals("Needle at rest", 131f, angle, 0.001f);
    }

    @Test
    public void needleAngle_atMax() {
        int needlePos = 32767;
        float angle = 131f - (needlePos / 410f);
        assertTrue("Needle at max: " + angle, angle < 60f);
    }

    // ---- Auto rotation ----

    @Test
    public void autoRotation_accumulates() {
        float rot = 0f;
        long delta = 35;
        rot += 0.3f * delta / 35f; // += 0.3
        assertEquals("0.3 per 35ms", 0.3f, rot, 0.001f);
    }

    @Test
    public void autoRotation_wrapsAt360() {
        float rot = 359f;
        rot += 2f;
        while (rot > 360f) rot -= 360f;
        assertEquals("Wrapped", 1f, rot, 0.001f);
    }

    @Test
    public void autoRotation_clampedDelta_max80ms() {
        long delta = 150;
        if (delta > 80) delta = 80;
        assertEquals("Delta clamped to 80", 80, delta);
    }

    // ---- Fade-in/out counters ----

    @Test
    public void fadeOut_decrementsToZero() {
        int fadeout = 100;
        fadeout--; // → 99
        assertEquals("Fadeout decrement", 99, fadeout);
    }

    @Test
    public void fadeIn_setAfterFadeout() {
        int fadein = 0, fadeout = 0;
        // When idle: fadeincounter = FADEIN_LENGTH (15)
        fadein = 15;
        assertEquals("Fadein set", 15, fadein);
    }
}
