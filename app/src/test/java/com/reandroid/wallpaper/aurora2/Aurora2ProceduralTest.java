package com.reandroid.wallpaper.aurora2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Aurora2ProceduralTest {

    @Test
    public void paletteIndexMapsKnownKeys() {
        assertEquals(0, Aurora2Procedural.paletteIndex("physical"));
        assertEquals(1, Aurora2Procedural.paletteIndex("teal"));
        assertEquals(2, Aurora2Procedural.paletteIndex("violet"));
        assertEquals(3, Aurora2Procedural.paletteIndex("warm"));
        assertEquals(0, Aurora2Procedural.paletteIndex("nonsense"));
        assertEquals(0, Aurora2Procedural.paletteIndex(null));
    }

    @Test
    public void envelopeStaysWithinUnitRangeAndItsPeriod() {
        for (long t = 0; t < 600_000L; t += 1000L) {
            float e = Aurora2Procedural.envelope(t);
            assertTrue("envelope=" + e, e >= 0f && e <= 1f);
        }
    }

    @Test
    public void envelopeReturnsToCalmBetweenBursts() {
        boolean calm = false;
        for (long t = 0; t < 240_000L; t += 500L) {
            if (Aurora2Procedural.envelope(t) < 0.01f) {
                calm = true;
                break;
            }
        }
        assertTrue("包络从不回到 0", calm);
    }

    @Test
    public void paramsCarryTimePaletteEnvelopeAndAspect() {
        float[] p = Aurora2Procedural.packParams(12.5f, 1080f, 2400f, 0.75f, 2, 1.0f);
        assertEquals(8, p.length);
        assertEquals(12.5f, p[0], 1e-4f); // time (s)
        assertEquals(1080f, p[1], 1e-4f); // width
        assertEquals(2400f, p[2], 1e-4f); // height
        assertEquals(0.75f, p[3], 1e-4f); // envelope
        assertEquals(2f, p[4], 1e-4f); // palette index
        assertEquals(1.0f, p[5], 1e-4f); // resolution scale
        assertEquals(1080f / 2400f, p[6], 1e-4f); // aspect
        assertEquals(0f, p[7], 1e-4f); // reserved
    }

    @Test
    public void paramsSurviveAZeroHeight() {
        float[] p = Aurora2Procedural.packParams(0f, 0f, 0f, 0f, 0, 1.0f);
        assertEquals(1.0f, p[6], 1e-4f);
    }

    @Test
    public void proceduralModeIsOffUnlessThePrefSaysOtherwise() {
        assertFalse(Aurora2Procedural.enabledFromPref(false));
        assertTrue(Aurora2Procedural.enabledFromPref(true));
    }
}
