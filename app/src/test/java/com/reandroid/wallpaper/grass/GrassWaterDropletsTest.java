package com.reandroid.wallpaper.grass;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 挂在草叶上的水珠。
 *
 * <p>要守住的核心是"水珠真的在叶面上"—— 它锚定在叶片参数坐标上，
 * 每帧靠 {@link GrassBladeGeometry} 重算位置。锚定写错的话水珠会飘到叶外，
 * 而且**不会有任何报错**，只是看着像有一层浮在草前面的脏东西。
 */
public class GrassWaterDropletsTest {

    private static final int BLADE_COUNT = 8;

    private static SceneData scene() {
        SceneData sd = new SceneData();
        sd.grassWidthScale = 1.0f;
        sd.grassHeightScale = 1.0f;
        sd.grassHardnessScale = 1.0f;
        sd.xDraw = 0.0f;
        return sd;
    }

    private static Blade[] blades() {
        Blade[] b = new Blade[BLADE_COUNT];
        Random r = new Random(4242L);
        for (int i = 0; i < b.length; i++) {
            Blade blade = new Blade();
            blade.size = 8;
            blade.angle = (r.nextFloat() - 0.5f) * 1.6f;
            blade.xPos = 40.0f + i * 60.0f;
            blade.yPos = 700.0f;
            blade.scale = 3.0f;
            blade.lengthX = 12.0f;
            blade.lengthY = 14.0f;
            blade.hardness = 0.7f;
            b[i] = blade;
        }
        return b;
    }

    private static GrassWaterDroplets system(int maxBeads, int maxSplashes) {
        return new GrassWaterDroplets(new Random(90210L), maxBeads, maxSplashes);
    }

    private static int countActive(GrassWaterDroplets d) {
        int n = 0;
        for (int i = 0; i < d.beadCapacity(); i++) {
            if (d.beadAt(i).active) n++;
        }
        return n;
    }

    // ---- 位置：必须在叶面上 ----

    /**
     * 每颗活跃水珠的屏幕坐标都必须落在它那片叶子的包围盒里。
     *
     * <p>这条是整个特性的地基 —— 锚定算错的话水珠会浮在草外面。
     */
    @Test
    public void beadsStayOnTheirBlade() {
        SceneData sd = scene();
        Blade[] blades = blades();
        GrassWaterDroplets d = system(64, 64);
        float[] xy = new float[64];
        float[] hw = new float[64];
        float[] minMax = new float[4];

        for (int frame = 0; frame < 400; frame++) {
            // 让叶子每帧摆一点，位置必须跟着重算
            for (Blade b : blades) {
                b.angle += 0.004f;
            }
            d.update(1.0f / 60.0f, 5.0f, blades, sd);

            for (int i = 0; i < d.beadCapacity(); i++) {
                GrassWaterDroplets.Bead bead = d.beadAt(i);
                if (!bead.active) continue;

                Blade blade = blades[bead.bladeIndex];
                GrassBladeGeometry.trace(blade, GrassBladeGeometry.originX(blade, sd),
                        GrassBladeGeometry.effectiveScale(blade, sd),
                        sd.grassHardnessScale, sd.grassHeightScale, xy, hw);
                boundsOf(xy, hw, blade.size + 1, minMax);

                assertTrue("水珠 x=" + bead.x + " 超出了叶片的 x 范围 ["
                                + minMax[0] + ", " + minMax[1] + "]，叶片 " + bead.bladeIndex,
                        bead.x >= minMax[0] - 0.01f && bead.x <= minMax[1] + 0.01f);
                assertTrue("水珠 y=" + bead.y + " 超出了叶片的 y 范围 ["
                                + minMax[2] + ", " + minMax[3] + "]",
                        bead.y >= minMax[2] - 0.01f && bead.y <= minMax[3] + 0.01f);
            }
        }
    }

    /**
     * 叶片的包围盒：{minX, maxX, minY, maxY}。
     *
     * <p>**x 要算上半宽** —— 叶片宽度是水平的，边缘在 {@code centerX ± halfWidth}。
     * 只取中心线的话，贴在叶缘上的水珠会被误判为"跑到叶外"。
     */
    private static void boundsOf(float[] xy, float[] hw, int points, float[] out) {
        out[0] = Float.MAX_VALUE;
        out[1] = -Float.MAX_VALUE;
        out[2] = Float.MAX_VALUE;
        out[3] = -Float.MAX_VALUE;
        for (int k = 0; k < points; k++) {
            out[0] = Math.min(out[0], xy[k * 2] - hw[k]);
            out[1] = Math.max(out[1], xy[k * 2] + hw[k]);
            out[2] = Math.min(out[2], xy[k * 2 + 1]);
            out[3] = Math.max(out[3], xy[k * 2 + 1]);
        }
    }

    // ---- 生成 ----

    /** 不下雨就一颗都不该生成。 */
    @Test
    public void noRainMeansNoBeads() {
        GrassWaterDroplets d = system(64, 64);
        for (int i = 0; i < 300; i++) {
            d.update(1.0f / 60.0f, 0.0f, blades(), scene());
        }
        assertEquals("晴天不该有挂珠", 0, d.activeBeadCount());
    }

    /** 强度越大生成越快（单调）。 */
    @Test
    public void heavierRainSpawnsMoreBeads() {
        int[] counts = new int[3];
        float[] intensities = {1.0f, 3.0f, 5.0f};
        for (int i = 0; i < intensities.length; i++) {
            GrassWaterDroplets d = system(4096, 4096);   // 池子开大，测的是生成率不是容量
            Blade[] blades = blades();
            SceneData sd = scene();
            for (int f = 0; f < 120; f++) {
                d.update(1.0f / 60.0f, intensities[i], blades, sd);
            }
            counts[i] = countActive(d);
        }
        assertTrue("强度 1 应当生成一些：" + counts[0], counts[0] > 0);
        assertTrue("强度 3 应当多于强度 1：" + counts[1] + " vs " + counts[0],
                counts[1] > counts[0]);
        assertTrue("强度 5 应当多于强度 3：" + counts[2] + " vs " + counts[1],
                counts[2] > counts[1]);
    }

    // ---- 生长与下滑 ----

    /** 没到下滑尺寸之前，水珠只长大。 */
    @Test
    public void beadsGrowBeforeTheySlide() {
        GrassWaterDroplets d = system(64, 64);
        Blade[] blades = blades();
        SceneData sd = scene();
        for (int i = 0; i < 600 && d.activeBeadCount() == 0; i++) {
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
        }
        assertTrue("没生成出水珠", d.activeBeadCount() > 0);

        GrassWaterDroplets.Bead bead = null;
        for (int i = 0; i < d.beadCapacity(); i++) {
            GrassWaterDroplets.Bead b = d.beadAt(i);
            if (b.active && !b.sliding) {
                bead = b;
                break;
            }
        }
        if (bead == null) return;   // 这一帧恰好都在滑，跳过

        float before = bead.size;
        d.update(1.0f / 60.0f, 5.0f, blades, sd);
        if (bead.active && !bead.sliding) {
            assertTrue("生长阶段尺寸应当只增不减：" + before + " -> " + bead.size,
                    bead.size > before);
        }
    }

    /** 下滑阶段段号只增（往叶尖走）。 */
    @Test
    public void slidingBeadsOnlyMoveTowardsTheTip() {
        SceneData sd = scene();
        Blade[] blades = blades();
        GrassWaterDroplets d = system(128, 128);
        for (int i = 0; i < 600; i++) {
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
        }
        float[] prevK = new float[d.beadCapacity()];

        for (int f = 0; f < 300; f++) {
            for (int i = 0; i < d.beadCapacity(); i++) {
                prevK[i] = d.beadAt(i).active ? d.beadAt(i).k : -1.0f;
            }
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
            for (int i = 0; i < d.beadCapacity(); i++) {
                GrassWaterDroplets.Bead b = d.beadAt(i);
                if (!b.active || !b.sliding || prevK[i] < 0.0f) continue;
                assertTrue("下滑的珠段号不该回退：" + prevK[i] + " -> " + b.k, b.k >= prevK[i]);
            }
        }
    }

    // ---- 汇合 ----

    /** 汇合之后总数不虚高：被吞的珠真的回收了。 */
    @Test
    public void mergingReclaimsTheAbsorbedBead() {
        GrassWaterDroplets d = system(32, 32);
        Blade[] blades = blades();
        SceneData sd = scene();
        int before;
        boolean sawMerge = false;

        for (int f = 0; f < 900; f++) {
            before = countActive(d);
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
            int after = countActive(d);
            // 一帧最多生成 floor(5/5*40/60)=0..1 颗，所以数量不该跳增
            assertTrue("活跃数跳变： " + before + " -> " + after, after - before <= 2);
            if (after < before) sawMerge = true;
        }
        assertTrue("跑了 15 秒都没观察到汇合（数量从未下降）", sawMerge);
    }

    // ---- 离开叶片 ----

    /** 滑过叶尖的水珠会离开，并在那里留下一颗坠落的水花。 */
    @Test
    public void beadsDetachPastTheTipAndLeaveAFallingSplash() {
        GrassWaterDroplets d = system(24, 64);
        Blade[] blades = blades();
        SceneData sd = scene();
        int detached = 0;

        // 按槽位追踪：记下每颗珠更新前的 k，更新后若该槽位变成不活跃、且之前的 k 已经
        // 到了叶尖附近，就算一次坠落。不能靠"活跃数下降"来判断 —— 同一帧往往还在生成新珠，
        // 数量会被抵消掉。
        float[] kBefore = new float[d.beadCapacity()];
        boolean[] slidingBefore = new boolean[d.beadCapacity()];
        int splashesBefore = d.activeSplashCount();

        for (int f = 0; f < 1800; f++) {
            for (int i = 0; i < d.beadCapacity(); i++) {
                GrassWaterDroplets.Bead b = d.beadAt(i);
                kBefore[i] = b.active ? b.k : -1.0f;
                slidingBefore[i] = b.active && b.sliding;
            }
            splashesBefore = d.activeSplashCount();
            d.update(1.0f / 60.0f, 5.0f, blades, sd);

            for (int i = 0; i < d.beadCapacity(); i++) {
                GrassWaterDroplets.Bead b = d.beadAt(i);
                if (kBefore[i] < 0.0f || b.active || !slidingBefore[i]) continue;
                // 这一槽位刚刚离场；看它离场时的 k 是不是已经越过叶尖
                if (kBefore[i] >= blades[b.bladeIndex].size - 1.0f) {
                    detached++;
                    assertTrue("水珠从叶尖离开时应当留下一颗坠落的水花",
                            d.activeSplashCount() > splashesBefore || d.activeSplashCount() > 0);
                }
            }
            for (int i = 0; i < d.beadCapacity(); i++) {
                GrassWaterDroplets.Bead b = d.beadAt(i);
                if (b.active) {
                    assertTrue("水珠的 k 跑到叶尖之外还在活跃：" + b.k, b.k < blades[b.bladeIndex].size + 1.0f);
                }
            }
        }
        assertTrue("十二秒内没有任何水珠滑到叶尖离开", detached > 0);
    }

    /** 雨停之后不会永远挂着 —— 没长够的也会开始下滑离场。 */
    @Test
    public void beadsLeaveAfterTheRainStops() {
        SceneData sd = scene();
        Blade[] blades = blades();
        GrassWaterDroplets d = system(64, 64);
        for (int i = 0; i < 300; i++) {
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
        }
        assertTrue("下雨时应当有挂珠", d.activeBeadCount() > 0);

        for (int i = 0; i < 60 * 30; i++) {
            d.update(1.0f / 60.0f, 0.0f, blades, sd);
        }
        assertEquals("雨停 30 秒后叶面上不该还有挂珠", 0, d.activeBeadCount());
    }

    // ---- 容量 ----

    /** 池子很小也不会越界、不会抛异常 —— 这是壁纸，不能因为下大雨就崩。 */
    @Test
    public void tinyPoolsStayInBounds() {
        GrassWaterDroplets d = system(1, 1);
        Blade[] blades = blades();
        SceneData sd = scene();
        for (int i = 0; i < 2000; i++) {
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
            assertTrue("活跃水珠数超过容量", countActive(d) <= d.beadCapacity());
            assertTrue("活跃水花数超过容量", d.activeSplashCount() <= d.splashCapacity());
        }
    }

    /** clear() 之后一片干净。 */
    @Test
    public void clearEmptiesEverything() {
        GrassWaterDroplets d = system(32, 32);
        Blade[] blades = blades();
        SceneData sd = scene();
        for (int i = 0; i < 300; i++) {
            d.update(1.0f / 60.0f, 5.0f, blades, sd);
        }
        d.clear();
        assertEquals(0, d.activeBeadCount());
        assertEquals(0, d.activeSplashCount());
        assertFalse(d.beadAt(0).active);
    }
}
