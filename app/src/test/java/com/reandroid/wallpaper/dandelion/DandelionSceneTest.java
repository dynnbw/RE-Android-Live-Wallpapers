package com.reandroid.wallpaper.dandelion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** 构图（设计坐标 → 屏幕）与摆动相位：全部是纯逻辑，单测直接跑。 */
public class DandelionSceneTest {

    private static DandelionScene scene() {
        return new DandelionScene(new Random(7L));
    }

    /** 电量满时花头最完整（0 帧），电量空时只剩绒芯（10 帧）。 */
    @Test
    public void headFrameFollowsBattery() {
        DandelionScene scene = scene();
        scene.setBatteryLevel(100);
        assertEquals(0, scene.headFrame());
        scene.setBatteryLevel(50);
        assertEquals(5, scene.headFrame());
        scene.setBatteryLevel(0);
        assertEquals(10, scene.headFrame());
        scene.setBatteryLevel(120);
        assertEquals(0, scene.headFrame());
        scene.setBatteryLevel(-5);
        assertEquals(10, scene.headFrame());
    }

    /** 风力设置整体缩放摆幅：0 时静止，200 时是参考幅度的两倍。 */
    @Test
    public void windPreferenceScalesSway() {
        DandelionScene scene = scene();
        scene.update(0L);
        scene.update(5000L); // i = 180°，B 摆到负侧
        float reference = scene.swayDegrees(1);
        assertTrue(reference < -1f);

        scene.applyPrefs(0f, 1f, true, 3);
        assertEquals(0f, scene.swayDegrees(1), 0.001f);
        scene.applyPrefs(2f, 1f, true, 3);
        assertEquals(reference * 2f, scene.swayDegrees(1), 0.01f);
    }

    /** 关掉「随电量枯荣」后，低电量也保持完整的花头。 */
    @Test
    public void batteryFadingCanBeDisabled() {
        DandelionScene scene = scene();
        scene.setBatteryLevel(0);
        assertEquals(10, scene.headFrame());
        scene.applyPrefs(1f, 1f, false, 3);
        assertEquals(0, scene.headFrame());
    }

    /** 数量设置：1/2/3 株按参考实现的取舍，4 株起才补背景小株。 */
    @Test
    public void flowerCountPicksSlots() {
        DandelionScene scene = scene();

        scene.applyPrefs(1f, 1f, true, 1);
        assertTrue(scene.isFlowerVisible(1));
        assertFalse(scene.isFlowerVisible(0));
        assertFalse(scene.isFlowerVisible(2));
        assertFalse(scene.isFlowerVisible(3));

        scene.applyPrefs(1f, 1f, true, 2);
        assertTrue(scene.isFlowerVisible(1));
        assertTrue(scene.isFlowerVisible(2));
        assertFalse(scene.isFlowerVisible(0));

        scene.applyPrefs(1f, 1f, true, 3);
        assertTrue(scene.isFlowerVisible(0));
        assertTrue(scene.isFlowerVisible(1));
        assertTrue(scene.isFlowerVisible(2));
        assertFalse(scene.isFlowerVisible(3));

        scene.applyPrefs(1f, 1f, true, 7);
        assertTrue(scene.isFlowerVisible(6));
        assertFalse(scene.isFlowerVisible(7));

        scene.applyPrefs(1f, 1f, true, 10);
        for (int i = 0; i < 10; i++) {
            assertTrue(scene.isFlowerVisible(i));
        }
    }

    /** 没画出来的花头点不到。 */
    @Test
    public void hiddenFlowersCannotBeTapped() {
        DandelionScene scene = scene();
        scene.resize(480, 800);
        scene.update(0L);
        scene.update(20000L);
        scene.applyPrefs(1f, 1f, true, 1);
        assertEquals(-1, scene.flowerAt(77f, 311f)); // A 已被隐藏
        assertEquals(1, scene.flowerAt(181f, 155f)); // B 还在
    }

    /** 三株的摆幅与相位照参考实现：A sin(i+d)，B sin(i+d+60)×3.5，C sin(i+d+30)×2.5。 */
    @Test
    public void swayUsesPerFlowerPhase() {
        DandelionScene scene = scene();
        scene.update(0L);
        scene.update(20000L); // i 回到 0
        assertEquals(0f, scene.swayDegrees(0), 0.001f);
        assertEquals(3.5f * (float) Math.sin(Math.toRadians(60)), scene.swayDegrees(1), 0.001f);
        assertEquals(2.5f * (float) Math.sin(Math.toRadians(30)), scene.swayDegrees(2), 0.001f);
    }

    /** 阵风在 4 秒里整加 360°，风停时相位收回基值，画面不跳。 */
    @Test
    public void gustAddsOneFullTurn() {
        DandelionScene scene = scene();
        scene.update(0L);
        scene.update(10000L); // i = 0, d = 0
        scene.gust();
        scene.update(12000L); // 一半：+90°，i = 72
        assertEquals(5f * (float) Math.sin(Math.toRadians(162)), scene.swayDegrees(0), 0.01f);
        scene.update(14000L); // 走完：+360° 收回，i = 144
        assertEquals(5f * (float) Math.sin(Math.toRadians(144)), scene.swayDegrees(0), 0.01f);
    }

    /** 花头中心的构图：A 在 i=0 时正对茎顶，B/C 在各自相位归零的时刻对齐参考稿。 */
    @Test
    public void headCentersMatchReferenceLayout() {
        float[] center = new float[2];

        DandelionScene scene = scene();
        scene.update(0L);
        scene.update(20000L); // i = 0，A 的摆动正好为 0
        scene.headCenter(0, center);
        assertEquals(77.39f, center[0], 0.1f);
        assertEquals(311.38f, center[1], 0.1f);

        scene.update(23333L); // i = 119.99°，B 的摆动为 0
        scene.headCenter(1, center);
        assertEquals(181f, center[0], 0.3f);
        assertEquals(155.5f, center[1], 0.3f);

        scene.update(24167L); // i = 150°，C 的摆动为 0
        scene.headCenter(2, center);
        assertEquals(363.3f, center[0], 0.3f);
        assertEquals(572.82f, center[1], 0.3f);
    }

    /** 设计稿按短边等比缩放：竖屏多出的高度给天空，横屏多出的宽度分到两侧。 */
    @Test
    public void viewportFitsAndAnchorsToBottom() {
        DandelionScene portrait = scene();
        portrait.resize(1080, 2400);
        assertEquals(2.25f, portrait.scale(), 0.0001f);
        assertEquals(0f, portrait.offsetX(), 0.001f);
        assertEquals(600f, portrait.offsetY(), 0.001f);

        DandelionScene landscape = scene();
        landscape.resize(2400, 1080);
        assertEquals(1.35f, landscape.scale(), 0.0001f);
        assertEquals(876f, landscape.offsetX(), 0.01f);
        assertEquals(0f, landscape.offsetY(), 0.01f);
    }

    /** 点到花头返回那一株，点空返回 -1。 */
    @Test
    public void tapsHitTheFlowerHead() {
        DandelionScene scene = scene();
        scene.resize(480, 800);
        scene.update(0L);
        scene.update(20000L);
        assertEquals(0, scene.flowerAt(77f, 311f));
        assertEquals(-1, scene.flowerAt(10f, 790f));
    }

    /** 点击会真的吐种子；粒子总数有上限，且会按寿命自然消失。 */
    @Test
    public void puffsEmitSporesAndParticlesExpire() {
        DandelionScene scene = scene();
        scene.resize(480, 800);
        scene.update(0L);
        long now = 20000L;
        scene.update(now);
        scene.puffAll();
        for (int i = 0; i < 60; i++) {
            now += 33L;
            scene.update(now);
        }
        assertTrue("吹过之后应该看得到种子", scene.spores().size() > 0);

        for (int i = 0; i < 30 * 120; i++) {
            now += 33L;
            scene.puffAll();
            scene.update(now);
        }
        assertTrue("粒子数不能无限涨", scene.spores().size() <= 160);
    }

    /** 背景浮尘开局就该有几颗，不然第一屏是空的。 */
    @Test
    public void dustIsPrewarmed() {
        DandelionScene scene = scene();
        scene.resize(480, 800);
        scene.update(100000L);
        assertTrue(scene.dust().size() > 0);
    }
}
