package com.reandroid.wallpaper.musicvis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link BeatRotation} 的回归测试：半区交替、30° 量化、最短路径与 200ms 过渡。
 */
public class BeatRotationTest {

    private static final float EPS = 0.01f;
    private static final long T0 = 1000L;

    @Test
    public void firstBeatUsesTheLowHalf() {
        BeatRotation r = new BeatRotation();
        r.onBeat(0f, T0);
        assertEquals(0f, r.update(T0 + 200L), EPS);

        BeatRotation full = new BeatRotation();
        full.onBeat(1f, T0);
        assertEquals(180f, full.update(T0 + 200L), EPS);
    }

    @Test
    public void secondBeatUsesTheHighHalf() {
        BeatRotation r = new BeatRotation();
        r.onBeat(0f, T0);
        r.update(T0 + 100L);
        r.onBeat(0f, T0 + 200L);
        assertEquals(180f, r.update(T0 + 400L), EPS);

        BeatRotation full = new BeatRotation();
        full.onBeat(0f, T0);
        full.update(T0 + 100L);
        full.onBeat(1f, T0 + 200L);
        assertEquals(360f, full.update(T0 + 400L), EPS);
    }

    @Test
    public void thirdBeatReturnsToTheLowHalf() {
        BeatRotation r = new BeatRotation();
        r.onBeat(0f, T0);
        r.update(T0 + 100L);
        r.onBeat(0f, T0 + 200L); // 高半区：180°
        r.update(T0 + 300L);
        r.onBeat(0f, T0 + 400L); // 回到低半区：0°
        assertEquals(0f, r.update(T0 + 600L), EPS);
    }

    @Test
    public void anglesUseSixtyDegreeSteps() {
        BeatRotation weak = new BeatRotation();
        weak.onBeat(0.2f, T0); // 0.2 * 3 = 0.6 -> 1 档 = 60°
        assertEquals(60f, weak.update(T0 + 300L), EPS);

        BeatRotation mid = new BeatRotation();
        mid.onBeat(0.5f, T0); // 0.5 * 3 = 1.5 -> 2 档 = 120°
        assertEquals(120f, mid.update(T0 + 300L), EPS);

        BeatRotation high = new BeatRotation();
        high.onBeat(0f, T0);
        high.update(T0 + 100L);
        high.onBeat(0.5f, T0 + 200L); // 180 + 120
        assertEquals(300f, high.update(T0 + 500L), EPS);
    }

    @Test
    public void outputAnglesAreMultiplesOf60() {
        for (float strength = 0f; strength <= 1f; strength += 0.1f) {
            BeatRotation r = new BeatRotation();
            r.onBeat(strength, T0);
            assertEquals(0f, r.update(T0 + 300L) % 60f, EPS);
        }
    }

    @Test
    public void updateTweensOver200Ms() {
        BeatRotation r = new BeatRotation();
        r.onBeat(1f, T0); // 0 -> 180
        assertEquals(0f, r.update(T0), EPS);
        assertEquals(90f, r.update(T0 + 100L), EPS);
        assertEquals(180f, r.update(T0 + 200L), EPS);
        assertEquals(180f, r.update(T0 + 400L), EPS);
    }

    @Test
    public void shortestDeltaWrapsAt180() {
        assertEquals(20f, BeatRotation.shortestDelta(350f, 10f), EPS);
        assertEquals(-20f, BeatRotation.shortestDelta(10f, 350f), EPS);
        assertEquals(180f, BeatRotation.shortestDelta(0f, 180f), EPS);
        assertEquals(-120f, BeatRotation.shortestDelta(0f, 240f), EPS);
    }
}
