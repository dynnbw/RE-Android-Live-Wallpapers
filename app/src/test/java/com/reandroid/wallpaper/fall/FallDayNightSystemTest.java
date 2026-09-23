package com.reandroid.wallpaper.fall;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 天空四档权重的算式（{@link FallDayNightSystem#computeWeights}）。
 *
 * <p>纯 JVM：算式不碰位置、时间、IO，所以这里能直接按高度角扫一遍。
 *
 * <p>守的两件事：
 * <ul>
 *   <li><b>恒和为 1</b> —— 四条色带加权求和，和不为 1 就会整体变亮或变暗，
 *       表现是"白天比原版亮一截"这种说不清来源的偏差。</li>
 *   <li><b>处处连续</b> —— 这条是日夜变换最容易栽的地方（grass 上栽过两次：
 *       天气色调用 {@code if (isNight)}、月亮用二选一 {@code setBlendFunc}，
 *       都在切换那一刻整帧跳变）。硬切在扫表里就是一步跨掉半个权重。</li>
 * </ul>
 */
public class FallDayNightSystemTest {

    private static final int NIGHT = 0;
    private static final int MORNING = 1;
    private static final int DUSK = 2;
    private static final int DAY = 3;

    /** 太阳高度角的扫描步长（度）。0.25° 小于任何一个过渡带，够看出台阶。 */
    private static final double STEP_DEG = 0.25;

    private static float[] weightsAt(double altitudeDeg, boolean rising) {
        float[] out = new float[4];
        FallDayNightSystem.computeWeights(altitudeDeg, rising, out);
        return out;
    }

    private static float sum(float[] w) {
        return w[0] + w[1] + w[2] + w[3];
    }

    @Test
    public void weightsAlwaysSumToOne() {
        for (double alt = -90.0; alt <= 90.0; alt += STEP_DEG) {
            for (boolean rising : new boolean[]{true, false}) {
                float[] w = weightsAt(alt, rising);
                assertEquals("高度角 " + alt + "°、rising=" + rising + " 的权重和",
                        1.0f, sum(w), 1e-4f);
            }
        }
    }

    @Test
    public void weightsAreNeverNegative() {
        for (double alt = -90.0; alt <= 90.0; alt += STEP_DEG) {
            for (boolean rising : new boolean[]{true, false}) {
                float[] w = weightsAt(alt, rising);
                for (int i = 0; i < w.length; i++) {
                    assertTrue("高度角 " + alt + "° 的第 " + i + " 档为负：" + w[i], w[i] >= 0.0f);
                }
            }
        }
    }

    @Test
    public void sunHighInTheSkyIsAllDay() {
        float[] w = weightsAt(40.0, false);
        assertEquals("太阳高照时应当只有白日", 1.0f, w[DAY], 1e-6f);
        assertEquals(0.0f, w[NIGHT], 1e-6f);
        assertEquals(0.0f, w[MORNING], 1e-6f);
        assertEquals(0.0f, w[DUSK], 1e-6f);
    }

    @Test
    public void deepNightIsAllNight() {
        float[] w = weightsAt(-40.0, false);
        assertEquals("太阳落到地平线下很深时应当只有夜晚", 1.0f, w[NIGHT], 1e-6f);
        assertEquals(0.0f, w[MORNING], 1e-6f);
        assertEquals(0.0f, w[DUSK], 1e-6f);
        assertEquals(0.0f, w[DAY], 1e-6f);
    }

    @Test
    public void daytimeBandReachesFullDayAtItsEdge() {
        float[] w = weightsAt(12.0, true);
        assertEquals("到达白日带上沿就该全是白日", 1.0f, w[DAY], 1e-6f);
    }

    @Test
    public void nightBandReachesFullNightAtItsEdge() {
        float[] w = weightsAt(-10.0, true);
        assertEquals("到达夜晚带下沿就该全是夜晚", 1.0f, w[NIGHT], 1e-6f);
    }

    @Test
    public void horizonIsFullyTwilight() {
        float[] rising = weightsAt(0.0, true);
        assertEquals("太阳在地平线上时不该有白日成分", 0.0f, rising[DAY], 1e-6f);
        assertEquals("太阳在地平线上时不该有夜晚成分", 0.0f, rising[NIGHT], 1e-6f);
        assertEquals(1.0f, rising[MORNING], 1e-6f);

        float[] setting = weightsAt(0.0, false);
        assertEquals(1.0f, setting[DUSK], 1e-6f);
    }

    @Test
    public void morningAndDuskAreNeverBothActive() {
        for (double alt = -90.0; alt <= 90.0; alt += STEP_DEG) {
            for (boolean rising : new boolean[]{true, false}) {
                float[] w = weightsAt(alt, rising);
                assertTrue("晨与昏同时非零：高度角 " + alt + "°、rising=" + rising,
                        w[MORNING] == 0.0f || w[DUSK] == 0.0f);
            }
        }
    }

    /**
     * 扫一遍全天，任何一步都不许跳。
     *
     * <p>0.25° 一步时，最陡的那一档每步也走不到 0.07；硬切是 0.5~1.0。
     * 阈值取 0.1 两头都留了余量。
     */
    @Test
    public void weightsNeverJump() {
        for (boolean rising : new boolean[]{true, false}) {
            float[] prev = weightsAt(-90.0, rising);
            for (double alt = -90.0 + STEP_DEG; alt <= 90.0; alt += STEP_DEG) {
                float[] cur = weightsAt(alt, rising);
                for (int i = 0; i < cur.length; i++) {
                    float delta = Math.abs(cur[i] - prev[i]);
                    assertTrue("第 " + i + " 档在高度角 " + alt + "° 处跳了 " + delta,
                            delta < 0.1f);
                }
                prev = cur;
            }
        }
    }
}
