package com.reandroid.wallpaper.fireworks;

import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 烟花增强模式(形态库 / 实时单位物理 / 目标点发射)回归测试。
 *
 * 覆盖:形态函数与粒子数无关(u = i/n);各形态的速度特征;螺旋色相单调;
 *       增强积分器帧率无关;增强模式不影响原版路径。
 */
public class FireworksEnhancedTest {

    /**
     * 基准时刻。测试用 {@code initialize(T0)} 而不是无参重载 —— 后者读
     * {@code SystemClock}，在 JVM 单测里会抛 not mocked。取 1000000 是为了与迁移前
     * {@code tools/fireworks-test/android/os/SystemClock.java} 那个常量替身的行为一致。
     */
    private static final int T0 = 1000000;

    /** 结构化形态:只依赖 u = i/n,因此点数翻倍(同一 u)必须给出同一速度。 */
    private static final int[] STRUCTURED = {
            FireworksShapes.CHRYSANTHEMUM,
            FireworksShapes.RING,
            FireworksShapes.DOUBLE_RING,
            FireworksShapes.HEART,
            FireworksShapes.STAR,
            FireworksShapes.PALM,
            FireworksShapes.SPIRAL,
    };

    /** 形态函数只依赖 u = i/n:点数翻倍时同一个 u 必须给出同一个速度。 */
    @Test
    public void velocityIsIndependentOfPointCount() {
        for (int shape : STRUCTURED) {
            for (int i = 0; i < 74; i++) {
                float[] a = new float[2];
                float[] b = new float[2];
                // 同一个种子 → 抖动项可复现;u 相同,除抖动外的几何必须一致
                FireworksShapes.fillVelocity(shape, i, 74, new Random(12345), a);
                FireworksShapes.fillVelocity(shape, i * 2, 148, new Random(12345), b);
                float d = (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
                assertEquals("形态 " + shape + " i=" + i + " 点数无关", 0.0f, d, 1.0E-3f);
            }
        }
    }

    /** 环形:速度恒定,才是一圈清晰的环而不是一团雾。 */
    @Test
    public void ringSpeedIsConstant() {
        for (int i = 0; i < 74; i++) {
            float[] v = new float[2];
            FireworksShapes.fillVelocity(FireworksShapes.RING, i, 74, new Random(1), v);
            assertEquals("环形速度恒定 i=" + i, 260.0f,
                    (float) Math.hypot(v[0], v[1]), 1.0E-3f);
        }
    }

    /** 双色双环:前 44% 一个速度,其余另一个。 */
    @Test
    public void doubleRingSplitsAtFortyFourPercent() {
        for (int i = 0; i < 74; i++) {
            float[] v = new float[2];
            FireworksShapes.fillVelocity(FireworksShapes.DOUBLE_RING, i, 74, new Random(1), v);
            boolean inner = (i / 74.0f) < 0.44f;
            float expect = inner ? 155.0f : 300.0f;
            assertEquals("双环 i=" + i + (inner ? " 内环" : " 外环"), expect,
                    (float) Math.hypot(v[0], v[1]), 1.0E-3f);
        }
    }

    /** 心形/五角星:速度幅值有界,且不是所有点都挤在一起。 */
    @Test
    public void heartAndStarAreBounded() {
        assertBounded("心形", FireworksShapes.HEART, 290.0f);
        assertBounded("五角星", FireworksShapes.STAR, 300.0f);
    }

    private void assertBounded(String name, int shape, float maxSpeed) {
        float min = Float.MAX_VALUE;
        float max = 0.0f;
        for (int i = 0; i < 74; i++) {
            float[] v = new float[2];
            FireworksShapes.fillVelocity(shape, i, 74, new Random(1), v);
            float m = (float) Math.hypot(v[0], v[1]);
            assertTrue(name + " i=" + i + " 未超上限", m <= maxSpeed + 1.0E-3f);
            min = Math.min(min, m);
            max = Math.max(max, m);
        }
        // 若所有点速度相同就是个圆,不是心形/五角星
        assertTrue(name + " 幅值应有变化(实际 " + min + "~" + max + ")", max - min > maxSpeed * 0.15f);
    }

    /** 螺旋:逐粒子色相单调递增 → 彩虹。 */
    @Test
    public void spiralHueIsMonotonic() {
        float prev = -1.0f;
        for (int i = 0; i < 74; i++) {
            float h = FireworksShapes.hueStep(FireworksShapes.SPIRAL, i);
            assertTrue("螺旋色相递增 i=" + i, h > prev);
            prev = h;
        }
        // 非螺旋形态不偏移色相
        assertEquals("非螺旋不加色相", 0.0f, FireworksShapes.hueStep(FireworksShapes.RING, 40), 1.0E-6f);
    }

    /** 关掉开关必须是原版行为:不会建火箭,也不会把 life 当成秒。 */
    @Test
    public void enhancedOffKeepsLegacyPath() {
        FireworksScene scene = new FireworksScene(1080, 2400);
        scene.initialize(T0);
        scene.applySettings(2, false, false, false);
        assertFalse("默认不是增强模式", scene.mEnhanced);
        FireworkParticle leader = scene.mNormal[0];
        leader.time = scene.mNow - 33;
        for (int i = 0; i < 120; i++) {
            scene.mNow += 33;
            scene.update();
        }
        // 原版路径:火箭上升阶段 life 仍等于 |dy|(0~1 量级),不是秒
        assertTrue("原版 life 仍是 0~1 量级", Math.abs(leader.life) <= 1.5f);
    }

    /**
     * 帧率无关性:同样跑 1.2 秒,33ms 与 16ms 两种步长下爆开的平均尺度应当相近。
     * 形态每次随机,所以用多次试验的均值来抵消抖动。
     */
    @Test
    public void burstScaleIsFrameRateIndependent() {
        float r33 = meanBurstRadius(33);
        float r16 = meanBurstRadius(16);
        float diff = Math.abs(r33 - r16) / r33;
        assertTrue("帧率无关 33ms=" + r33 + " 16ms=" + r16 + " 偏差 " + diff,
                diff < 0.15f);
    }

    /** 反复发一炮,量爆开后 1.0 秒时子粒子到爆心的平均距离。 */
    private float meanBurstRadius(int dtMs) {
        double sum = 0.0;
        int trials = 40;
        for (int t = 0; t < trials; t++) {
            FireworksScene scene = new FireworksScene(1080, 2400);
            scene.initialize(T0);
            scene.applySettings(2, false, true, false);
            FireworkParticle leader = scene.mNormal[0];
            leader.time = scene.mNow;      // 无延迟,立刻发射
            // 推进到爆炸发生
            int guard = 0;
            while (leader.active && guard++ < 2000) {
                scene.mNow += dtMs;
                scene.update();
            }
            float cx = leader.posX;
            float cy = leader.posY;
            // 再跑 1.0 秒
            int steps = Math.round(1000.0f / dtMs);
            for (int i = 0; i < steps; i++) {
                scene.mNow += dtMs;
                scene.update();
            }
            double s = 0.0;
            int n = 0;
            for (int i = 1; i < FireworksScene.STRIDE; i++) {
                FireworkParticle e = scene.mNormal[i];
                if (!e.active) continue;
                s += Math.hypot(e.posX - cx, e.posY - cy);
                n++;
            }
            if (n > 0) sum += s / n;
        }
        return (float) (sum / trials);
    }
}
