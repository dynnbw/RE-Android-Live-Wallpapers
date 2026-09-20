package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MoonCalculator.parallacticAngleDeg 的约束测试。
 *
 * <p>这个角决定月面相对屏幕要转多少（整体旋转：纹理和终止线一起转）。
 * 公式本身只有一行，但它的**行为**有几个能自查的点，比记公式可靠：
 * 中天时月牙竖直、天体在天顶以北时整个倒过来、接近地平线时偏得最多、
 * 上升与下降关于 0 镜像。
 */
public class ParallacticAngleTest {

    /** 过中天（H=0）且天体在天顶以南 → 月牙竖直，不转。 */
    @Test
    public void culminationIsUpright() {
        double q = MoonCalculator.parallacticAngleDeg(45.0, 10.0, 0.0);
        assertEquals("北纬 45°、赤纬 10° 过中天", 0.0, q, 1.0E-9);

        // 南半球同理。注意赤纬要比纬度更靠"天顶那一侧"的反面才会竖直：
        // 南纬 33° 的天顶在 -33°，赤纬 -20° 比它更靠北 → 从天顶以北经过 → 倒过来（180°）。
        // 要竖直得取更南的赤纬。
        double qs = MoonCalculator.parallacticAngleDeg(-33.0, -45.0, 0.0);
        assertEquals("南纬 33°、赤纬 -45° 过中天", 0.0, qs, 1.0E-9);

        // 上面那条的镜像：南纬 33°、赤纬 -20° 应在天顶以北，整个倒过来
        double qn = MoonCalculator.parallacticAngleDeg(-33.0, -20.0, 0.0);
        assertEquals("南纬 33°、赤纬 -20° 过中天（在天顶以北）", 180.0, Math.abs(qn), 1.0E-9);
    }

    /** 天体从天顶以北经过（热带常见）→ 整个月亮倒过来。 */
    @Test
    public void northOfZenithFlips() {
        double q = MoonCalculator.parallacticAngleDeg(10.0, 20.0, 0.0);
        assertEquals("北纬 10°、赤纬 20° 过中天（在天顶以北）", 180.0, Math.abs(q), 1.0E-9);
    }

    /** 手算核对：φ=45°、δ=0°、H=90° → atan2(1, tan45·cos0 − sin0·cos90) = atan2(1,1) = 45°。 */
    @Test
    public void knownValue() {
        assertEquals("北纬 45°、赤纬 0°、时角 90°", 45.0,
                MoonCalculator.parallacticAngleDeg(45.0, 0.0, 90.0), 1.0E-9);
    }

    /** 上升（H<0）与下降（H>0）关于 0 镜像 —— 这是视觉上最该成立的一条。 */
    @Test
    public void riseSetMirror() {
        for (double lat : new double[]{10, 45, -33}) {
            for (double dec : new double[]{-20, 0, 15}) {
                for (double h : new double[]{10, 40, 75, 120, 170}) {
                    double rise = MoonCalculator.parallacticAngleDeg(lat, dec, -h);
                    double set = MoonCalculator.parallacticAngleDeg(lat, dec, h);
                    assertEquals("纬度 " + lat + " 赤纬 " + dec + " 时角 ±" + h,
                            -rise, set, 1.0E-9);
                }
            }
        }
    }

    /** 接近地平线时偏得最多；中天时最小（0）。 */
    @Test
    public void largestNearHorizon() {
        double atMeridian = Math.abs(MoonCalculator.parallacticAngleDeg(45.0, 10.0, 0.0));
        double nearHorizon = Math.abs(MoonCalculator.parallacticAngleDeg(45.0, 10.0, 88.0));
        assertTrue("近地平线应比中天偏得多（" + nearHorizon + " vs " + atMeridian + "）",
                nearHorizon > atMeridian + 1.0);
    }

    /** 值域落在 (−180, 180]，且始终是有限数。 */
    @Test
    public void range() {
        for (double lat = -80; lat <= 80; lat += 13) {
            for (double dec = -28; dec <= 28; dec += 7) {
                for (double h = -180; h <= 180; h += 17) {
                    double q = MoonCalculator.parallacticAngleDeg(lat, dec, h);
                    assertFalse("(" + lat + "," + dec + "," + h + ") 得到 " + q,
                            Double.isNaN(q) || Double.isInfinite(q));
                    // ±180 对旋转角是等价的（H=±180 时天体在地平线下，本来也不可见）
                    assertTrue("(" + lat + "," + dec + "," + h + ") 越界 " + q,
                            q >= -180.0 && q <= 180.0);
                }
            }
        }
    }
}
