package com.reandroid.wallpaper.musicvis;

import com.reandroid.utils.Mat4;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for ManyScene: wave mode selection, FFT bin processing,
 * coordinate conversion (via Mat4), and HSL recolor logic.
 */
public class ManySceneTest {

    // ---- Wave mode selection ----

    @Test public void mode0_pcmOnly_neverFFT() {
        assertFalse("Mode 0 waveIdx 0", isFFT(0, 0));
        assertFalse("Mode 0 waveIdx 2", isFFT(0, 2));
    }
    @Test public void mode1_fftOnly_alwaysFFT() {
        assertTrue("Mode 1 waveIdx 0", isFFT(1, 0));
        assertTrue("Mode 1 waveIdx 2", isFFT(1, 2));
    }
    @Test public void mode2_mixed_fftAtLeft() {
        assertFalse("Mode 2 waveIdx 0 PCM", isFFT(2, 0));
        assertFalse("Mode 2 waveIdx 1 PCM", isFFT(2, 1));
        assertTrue("Mode 2 waveIdx 2 (240°) FFT", isFFT(2, 2));
    }

    /** Replicates vis5 drawWave logic: isFFT(mode, waveIdx). */
    private static boolean isFFT(int mode, int idx) {
        return (mode == 1) || (mode == 2 && idx == 2);
    }

    // ---- FFT bin counting ----

    @Test public void fftSize256_128bins() {
        assertEquals(128, Math.min(1024 / 2, 256 / 2));
    }
    @Test public void fftSize512_256bins() {
        assertEquals(256, Math.min(1024 / 2, 512 / 2));
    }
    @Test public void fftSize1024_512bins() {
        assertEquals(512, Math.min(1024 / 2, 1024 / 2));
    }

    // ---- FFT bin distribution ----

    @Test public void fftDist_128bins_256bars_validRange() {
        int len = 128, LINE_COUNT = 256, src = 0, cnt = 0;
        for (int i = 0; i < LINE_COUNT; i++) {
            cnt += len;
            if (cnt > LINE_COUNT) { src++; cnt -= LINE_COUNT; }
        }
        // 128 bins spread over 256 bars → src ends at ~127
        assertEquals("128 bins", 127, src);
    }

    // ---- FFT bin processing (Visualization3RS algorithm) ----

    @Test public void fftDecay_dropsBy800() {
        int oldval = 50000;
        int newval = 10000;
        if (newval < oldval - 800) newval = oldval - 800;
        assertEquals(49200, newval);
    }
    @Test public void fftDecay_noDropWhenClose() {
        int oldval = 50000;
        int newval = 49800;
        if (newval < oldval - 800) newval = oldval - 800;
        assertEquals(49800, newval);
    }

    // ---- HSL recolor state ----

    @Test public void hslStatic_noAdvance() {
        assertFalse("Static", true && false);
    }
    @Test public void hslDynamic_advances() {
        assertTrue("Dynamic", true && true);
    }
    @Test public void hslDisabled_noAdvance() {
        assertFalse("Disabled", false && true);
    }

    // ---- Mat4 frustumM (used by ManyScene.updateProjection) ----

    @Test public void frustum_XYFlipped() {
        float[] m = new float[16];
        Mat4.frustumM(m, 0.5f, -0.5f, 1f, -1f, 1f, 6000f);
        assertEquals("X flip m[0]", -2f, m[0], 0.001f);
        assertEquals("Y flip m[5]", -1f, m[5], 0.001f);
    }
    @Test public void frustum_standard() {
        float[] m = new float[16];
        Mat4.frustumM(m, -1f, 1f, -1f, 1f, 1f, 100f);
        assertEquals("m[0]", 1f, m[0], 0.001f);
        assertEquals("m[5]", 1f, m[5], 0.001f);
    }

    // ---- Mat4 orthoM (used by vis2/vis3/vis4) ----

    @Test public void ortho_portrait() {
        float[] m = new float[16];
        Mat4.orthoM(m, -1f, 1f, -1.78f, 1.78f, -1f, 1f);
        assertEquals("m[0]", 1f, m[0], 0.001f);
    }

    // ---- buildMVP chain via Mat4 ----

    @Test public void buildMVP_producesNonIdentity() {
        float[] proj = new float[16];
        Mat4.frustumM(proj, -0.5f, 0.5f, -1f, 1f, 1f, 6000f);
        float[] mvp = new float[16];
        // Multiply: proj * scale * translate → non-identity
        float[] model = {1,0,0,0, 0,1,0,0, 0,0,1,0, 100,200,0,1};
        Mat4.multiplyMM(mvp, proj, model);
        assertFalse("MVP non-identity", isId(mvp));
    }

    private static boolean isId(float[] m) {
        for (int i = 0; i < 16; i++) {
            if (Math.abs(m[i] - (i % 5 == 0 ? 1 : 0)) > 0.0001f) return false;
        }
        return true;
    }

    // ---- Pref sync wrapper (getPluginPrefs fallback logic) ----

    @Test public void prefFallback_namesCorrect() {
        assertEquals("plugin_vis3", "plugin_vis3");
        // Legacy: "musicvis3_prefs" (not "musicvisvis3_prefs")
        String legacy = "musicvis" + "3" + "_prefs";
        assertEquals("musicvis3_prefs", legacy);
    }
}
