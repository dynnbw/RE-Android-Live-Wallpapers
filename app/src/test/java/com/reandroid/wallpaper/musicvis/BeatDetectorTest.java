package com.reandroid.wallpaper.musicvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * {@link BeatDetector} 的回归测试：自适应平均、一秒最多两拍、随机间隔、静音与强度。
 *
 * <p>纯逻辑，不依赖 Android；随机间隔用一个固定返回值的 Random 桩来保持确定性。
 */
public class BeatDetectorTest {

    private static final long T0 = 1000L;
    private static final float EPS = 0.001f;

    private static boolean beat(float strength) {
        return strength >= 0f;
    }

    /** 让随机间隔固定落在最小值（0）或最大值（1）。 */
    private static BeatDetector detector(float randomValue) {
        return new BeatDetector(new Random() {
            @Override
            public float nextFloat() {
                return randomValue;
            }
        });
    }

    private static int countBeats(
            BeatDetector d, long startMs, int count, long stepMs, float energy) {
        int beats = 0;
        for (int i = 1; i <= count; i++) {
            if (beat(d.onEnergy(energy, startMs + i * stepMs))) beats++;
        }
        return beats;
    }

    @Test
    public void firstFrameOnlySetsTheBaseline() {
        BeatDetector d = detector(0f);
        assertEquals(-1f, d.onEnergy(50f, T0), EPS);
    }

    @Test
    public void spikeAboveTheAverageIsABeat() {
        BeatDetector d = detector(0f);
        d.onEnergy(10f, T0);
        assertTrue(beat(d.onEnergy(20f, T0 + 500L)));
    }

    @Test
    public void steadyEnergyProducesNoBeats() {
        BeatDetector d = detector(0f);
        for (int i = 0; i < 20; i++) {
            float s = d.onEnergy(10f, T0 + i * 16L);
            assertTrue("steady frame " + i, s < 0f);
        }
    }

    @Test
    public void silenceProducesNoBeats() {
        BeatDetector d = detector(0f);
        d.onEnergy(10f, T0);
        for (int i = 1; i < 20; i++) {
            assertTrue(d.onEnergy(0f, T0 + i * 16L) < 0f);
        }
    }

    @Test
    public void windowBlocksAThirdBeatWithinASecond() {
        BeatDetector d = detector(0f);
        d.onEnergy(10f, T0);
        assertTrue(beat(d.onEnergy(30f, T0 + 100L)));
        assertTrue(beat(d.onEnergy(40f, T0 + 400L)));
        assertTrue("一秒内的第三拍应被挡住", d.onEnergy(60f, T0 + 700L) < 0f);
        assertTrue("距第一拍满一秒后可以再跳", beat(d.onEnergy(80f, T0 + 1100L)));
    }

    @Test
    public void atMostTwoBeatsPerSecond() {
        BeatDetector d = detector(0f);
        d.onEnergy(10f, T0);
        // 每 150ms 一次强脉冲，持续 1.05 秒
        assertEquals(2, countBeats(d, T0, 7, 150L, 100f));
    }

    @Test
    public void randomGapChangesTheSpacing() {
        // 500ms 的节拍：短间隔能跳 4 次，长间隔只能跳 2 次
        BeatDetector shortGap = detector(0f);
        BeatDetector longGap = detector(1f);
        shortGap.onEnergy(10f, T0);
        longGap.onEnergy(10f, T0);
        assertEquals(4, countBeats(shortGap, T0, 4, 500L, 100f));
        assertEquals(2, countBeats(longGap, T0, 4, 500L, 100f));
    }

    @Test
    public void strongerSpikesGiveStrongerBeats() {
        BeatDetector weak = detector(0f);
        BeatDetector strong = detector(0f);
        weak.onEnergy(10f, T0);
        strong.onEnergy(10f, T0);
        float weakStrength = weak.onEnergy(16f, T0 + 500L);
        float strongStrength = strong.onEnergy(40f, T0 + 500L);
        assertTrue(beat(weakStrength));
        assertTrue(beat(strongStrength));
        assertTrue(strongStrength > weakStrength);
    }
}
