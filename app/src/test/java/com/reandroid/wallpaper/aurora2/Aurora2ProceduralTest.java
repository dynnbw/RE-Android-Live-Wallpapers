package com.reandroid.wallpaper.aurora2;

import static org.junit.Assert.assertEquals;
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
}
