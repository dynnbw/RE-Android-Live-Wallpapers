package com.reandroid.wallpaper.grass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 雨粒子状态机与深度耦合的回归。
 *
 * <p>这里要守住的核心是**深度把速度/尺寸/透明度绑在一起** —— 那是"雨有纵深"的全部来源，
 * 也是移植时最容易被拆散的东西（拆散了代码照跑、测试照过，只是雨又变回一堵平墙）。
 */
public class GrassRainParticleSystemTest {

    private static GrassRainParticleSystem.Config config() {
        GrassRainParticleSystem.Config c = new GrassRainParticleSystem.Config();
        c.count = 64;
        c.lifeMin = 0.8f;
        c.lifeMax = 1.6f;
        c.frequency = 12.0f;      // 每秒发射个数
        c.startSizeMin = 0.3f;
        c.startSizeMax = 1.0f;
        c.speed = 900.0f;         // 像素/秒，scaleFactor 会再乘 0.12..1
        c.preWarmScale = 0.6f;    // > 0 才会预热；resetWithoutPrewarmStartsFromBorn 靠
                                  // reset(false) 而不是靠这个值为 0 来测"从 BORN 开始"
        // 线段发射器：屏幕上沿一条斜线撒粒子
        c.startX = 0.0f;    c.startY = -200.0f;
        c.endX = 1080.0f;   c.endY = 100.0f;
        c.baseWidth = 6.0f; c.baseHeight = 90.0f;
        return c;
    }

    // ---- 深度耦合：这一组是本计划存在的理由 ----

    /** 深度越大落得越快。**单调**，不是"有快有慢"。 */
    @Test
    public void depthMakesNearDropsFallFaster() {
        assertTrue(GrassRainParticleSystem.speedScale(0.0f) < GrassRainParticleSystem.speedScale(1.0f));
        for (float d = 0.0f; d < 1.0f; d += 0.05f) {
            assertTrue("深度 " + d + " 处速度不再随深度递增",
                    GrassRainParticleSystem.speedScale(d)
                            < GrassRainParticleSystem.speedScale(d + 0.05f));
        }
        assertEquals("最远", 0.12f, GrassRainParticleSystem.speedScale(0.0f), 1.0E-4f);
        assertEquals("最近", 1.00f, GrassRainParticleSystem.speedScale(1.0f), 1.0E-4f);
    }

    /** 深度越大越不透明（参考是 mix(0.3, 2.0, d)）。 */
    @Test
    public void depthMakesNearDropsMoreOpaque() {
        assertEquals(0.3f, GrassRainParticleSystem.alphaScale(0.0f), 1.0E-4f);
        assertEquals(2.0f, GrassRainParticleSystem.alphaScale(1.0f), 1.0E-4f);
        assertTrue(GrassRainParticleSystem.alphaScale(0.2f)
                < GrassRainParticleSystem.alphaScale(0.8f));
    }

    /** 深度越大越粗（参考是 mix(3.0, 1.0, d)，**远的反而更宽**）。 */
    @Test
    public void depthMakesFarDropsRelativelyWider() {
        assertEquals(3.0f, GrassRainParticleSystem.widthScale(0.0f), 1.0E-4f);
        assertEquals(1.0f, GrassRainParticleSystem.widthScale(1.0f), 1.0E-4f);
        assertTrue("远处应比近处宽", GrassRainParticleSystem.widthScale(0.1f)
                > GrassRainParticleSystem.widthScale(0.9f));
    }

    /**
     * 尺寸必须**跟着深度走**：按深度升序排完之后，高度必须单调不减。
     *
     * <p>这是"三者同源"的可测形式。如果尺寸改用另一个独立随机数（也就是原来那堵平墙的
     * 做法），这个排序关系立刻就不成立了 —— 而代码照样跑、上面三条 depth→表现 的测试
     * 也照样过。
     */
    @Test
    public void sizeIsMonotoneInDepth() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(true);
        for (int i = 0; i < 200; i++) {
            sys.advance(1.0f / 60.0f, i / 60.0f);
        }
        int n = sys.count();
        final GrassRainParticleSystem s = sys;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(s.depth(a), s.depth(b)));

        float prevDepth = -1.0f;
        float prevHeight = -1.0f;
        int seen = 0;
        for (int k = 0; k < n; k++) {
            int p = order[k];
            float h = sys.quadHeightPx(p);
            if (h <= 0.0f) continue;      // 还没发射过的槽位
            assertTrue("深度递增时高度却变小了：depth " + prevDepth + " -> " + sys.depth(p)
                    + "，height " + prevHeight + " -> " + h, h >= prevHeight - 1.0E-3f);
            prevDepth = sys.depth(p);
            prevHeight = h;
            seen++;
        }
        assertTrue("一个已发射的粒子都没有，状态机没转起来", seen > 0);
    }

    // ---- 状态机 ----

    /** 初始 reset 之后粒子应当散布在各个状态里，而不是全挤在 BORN。 */
    @Test
    public void resetSpreadsParticlesAcrossStates() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(true);
        boolean[] seen = new boolean[4];
        for (int i = 0; i < sys.count(); i++) {
            seen[(int) sys.state(i)] = true;
        }
        assertTrue("没有 RUN 状态的粒子", seen[(int) GrassRainParticleSystem.STATE_RUN]);
    }

    /** 不用预热时，reset 之后全部从 BORN 开始。 */
    @Test
    public void resetWithoutPrewarmStartsFromBorn() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(false);
        for (int i = 0; i < sys.count(); i++) {
            assertEquals("粒子 " + i, GrassRainParticleSystem.STATE_BORN, sys.state(i), 0.0f);
        }
    }

    /** 粒子会死也会重生：跑够久之后，同一个槽位不会永远停在 DIE。 */
    @Test
    public void particlesRecycleAfterDying() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(true);
        for (int i = 0; i < 600; i++) {
            sys.advance(1.0f / 60.0f, i / 60.0f);
        }
        int running = 0;
        for (int i = 0; i < sys.count(); i++) {
            if (sys.state(i) == GrassRainParticleSystem.STATE_RUN) running++;
        }
        assertTrue("跑了十秒只剩 " + running + " 个 RUN，粒子没有重生", running > sys.count() / 4);
    }

    // ---- 位置与尺寸 ----

    /** 粒子必须落在发射线段上/下落的纵向延长线上 —— x 一旦飘出去，雨会从屏幕外出现。 */
    @Test
    public void particlesStayWithinTheEmitterLineHorizontally() {
        GrassRainParticleSystem.Config c = config();
        GrassRainParticleSystem sys = new GrassRainParticleSystem(c);
        sys.reset(true);
        for (int i = 0; i < 300; i++) {
            sys.advance(1.0f / 60.0f, i / 60.0f);
            for (int p = 0; p < sys.count(); p++) {
                float x = sys.x(p);
                assertTrue("粒子 " + p + " 的 x 越界：" + x,
                        x >= Math.min(c.startX, c.endX) - 1.0f && x <= Math.max(c.startX, c.endX) + 1.0f);
            }
        }
    }

    /** y 只增不减 —— 雨往下掉。这是 y 向下坐标系的直接后果。 */
    @Test
    public void particlesOnlyFallDownwards() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(true);
        for (int i = 0; i < 60; i++) {
            sys.advance(1.0f / 60.0f, i / 60.0f);
        }
        float[] before = new float[sys.count()];
        int[] stateBefore = new int[sys.count()];
        for (int p = 0; p < sys.count(); p++) {
            before[p] = sys.y(p);
            stateBefore[p] = (int) sys.state(p);
        }
        sys.advance(1.0f / 60.0f, 1.0f);
        for (int p = 0; p < sys.count(); p++) {
            if (stateBefore[p] != (int) GrassRainParticleSystem.STATE_RUN) continue;
            if ((int) sys.state(p) != (int) GrassRainParticleSystem.STATE_RUN) continue;
            assertTrue("粒子 " + p + " 没有往下掉：" + before[p] + " -> " + sys.y(p),
                    sys.y(p) > before[p]);
        }
    }

    /** 生命周期落在 [lifeMin, lifeMax]。 */
    @Test
    public void lifetimesStayInRange() {
        GrassRainParticleSystem.Config c = config();
        GrassRainParticleSystem sys = new GrassRainParticleSystem(c);
        sys.reset(true);
        for (int i = 0; i < 600; i++) {
            sys.advance(1.0f / 60.0f, i / 60.0f);
            for (int p = 0; p < sys.count(); p++) {
                float life = sys.lifeTime(p);
                if (life <= 0.0f) continue;
                assertTrue("粒子 " + p + " 寿命越界：" + life,
                        life >= c.lifeMin - 1.0E-3f && life <= c.lifeMax + 1.0E-3f);
            }
        }
    }

    /**
     * RUN 中的粒子每帧位移**必须恰好**是 {@code speed · dt · speedScale(depth)}。
     *
     * <p>这条专门盯 {@code preWarmFactor}。参考实现把它当作"本帧额外位移"，只在转进 RUN
     * 的那一帧非零；忘了归零的话每帧会多出一个恒定距离 —— 看起来像"越掉越快"，
     * 而上面那条"y 只增不减"的弱断言照样过。
     *
     * <p>（原来这里写的是一条"30fps 与 120fps 跑同样时长结果接近"的测试。那个不成立：
     * {@code emit} 的随机种子含 {@code fract(time)}，两种帧率喂进去的 time 序列根本不是
     * 同一条随机流，比较的是两份不同的雨。换成这条精确断言更强也更稳。）
     */
    @Test
    public void runningParticlesFallAtExactlyTheDepthScaledSpeed() {
        GrassRainParticleSystem.Config c = config();
        GrassRainParticleSystem sys = new GrassRainParticleSystem(c);
        sys.reset(true);

        float dt = 1.0f / 60.0f;
        // 找一个"已经跑过一会儿"的粒子：alpha < 1 说明淡出已经开始，
        // 也就意味着它转进 RUN 时那次预跑位移早被消耗掉了。
        int target = -1;
        for (int i = 0; i < 600 && target < 0; i++) {
            sys.advance(dt, i / 60.0f);
            for (int p = 0; p < sys.count(); p++) {
                if (sys.state(p) == GrassRainParticleSystem.STATE_RUN && sys.alpha(p) < 0.999f) {
                    target = p;
                    break;
                }
            }
        }
        assertTrue("跑不出一个稳定处于 RUN 的粒子", target >= 0);

        float depth = sys.depth(target);
        float before = sys.y(target);
        sys.advance(dt, 99.0f);
        if (sys.state(target) != GrassRainParticleSystem.STATE_RUN) {
            return;   // 它这一帧退场了，没什么可测的
        }
        float expected = c.speed * dt * GrassRainParticleSystem.speedScale(depth);
        assertEquals("粒子 " + target + " 的每帧位移不等于 speed·dt·speedScale(depth)",
                expected, sys.y(target) - before, Math.abs(expected) * 0.02f + 1.0E-3f);
    }

    /** 不同槽位的深度不能全一样 —— 全一样就没有纵深。 */
    @Test
    public void depthsAreSpreadNotUniform() {
        GrassRainParticleSystem sys = new GrassRainParticleSystem(config());
        sys.reset(true);
        float min = 2.0f, max = -1.0f;
        for (int p = 0; p < sys.count(); p++) {
            float d = sys.scaleFactor(p);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        assertTrue("深度没有铺开：min=" + min + " max=" + max, max - min > 0.3f);
        assertNotEquals(0.0f, max, 0.0f);
    }
}
