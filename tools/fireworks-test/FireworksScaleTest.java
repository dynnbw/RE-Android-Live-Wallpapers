/*
 * 烟花数量/尾迹 回归测试 —— 纯 JVM,不需要设备:
 *
 *   javac -d /tmp/fwtest \
 *         tools/fireworks-test/android/os/SystemClock.java \
 *         app/src/main/java/com/reandroid/wallpaper/fireworks/FireworksScene.java \
 *         tools/fireworks-test/FireworksScaleTest.java
 *   java -cp /tmp/fwtest com.reandroid.wallpaper.fireworks.FireworksScaleTest
 *
 * 覆盖:数量→组数→槽位 的映射;越界夹取;重建后数组长度;调小不越界;
 *       组首判定(替代 indexOf 全表扫描后行为不变)。
 */
package com.reandroid.wallpaper.fireworks;

public final class FireworksScaleTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testSlotMapping();
        testBounds();
        testRebuild();
        testLeaderFlagTailLife();
        if (failures == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println(failures + " 个用例失败");
            System.exit(1);
        }
    }

    /** N=1…30:上升组数 = N,炸开组数 = round(N×1.5),槽位 = 和 × STRIDE。 */
    private static void testSlotMapping() {
        for (int n = FireworksScene.MIN_COUNT; n <= FireworksScene.MAX_COUNT; n++) {
            int normal = FireworksScene.normalGroups(n);
            int extras = FireworksScene.extraGroups(n);
            assertEquals("N=" + n + " 上升组数", n, normal);
            assertEquals("N=" + n + " 炸开组数", Math.round(n * 1.5f), extras);
            assertEquals("N=" + n + " 槽位",
                    (normal + extras) * FireworksScene.STRIDE, FireworksScene.slots(n));
        }
        // 原版基线:2 上升 / 3 炸开
        assertEquals("N=2 上升组数", 2, FireworksScene.normalGroups(2));
        assertEquals("N=2 炸开组数", 3, FireworksScene.extraGroups(2));
        assertEquals("N=2 槽位", 375, FireworksScene.slots(2));
        // 上限
        assertEquals("N=30 上升组数", 30, FireworksScene.normalGroups(30));
        assertEquals("N=30 炸开组数", 45, FireworksScene.extraGroups(30));
        assertEquals("N=30 槽位", 5625, FireworksScene.slots(30));
        // 下限:N=1 → round(1.5) = 2
        assertEquals("N=1 炸开组数", 2, FireworksScene.extraGroups(1));
    }

    /** 越界输入被夹到 [MIN_COUNT, MAX_COUNT]。 */
    private static void testBounds() {
        assertEquals("0 被夹到下限", FireworksScene.MIN_COUNT, FireworksScene.normalGroups(0));
        assertEquals("负数被夹到下限", FireworksScene.MIN_COUNT, FireworksScene.normalGroups(-5));
        assertEquals("超上限被夹住", FireworksScene.MAX_COUNT, FireworksScene.normalGroups(999));
    }

    /** 重建后数组长度与组数一致;调小后不越界;相同设置不重建。 */
    private static void testRebuild() {
        FireworksScene scene = new FireworksScene(1080, 1920);
        scene.initialize();
        assertEquals("默认上升组数 = 原版", 2, scene.mNormalGroups);
        assertEquals("默认炸开组数 = 原版", 3, scene.mExtraGroups);
        assertEquals("默认上升槽位", 2 * FireworksScene.STRIDE, scene.mNormal.length);
        assertEquals("默认炸开槽位", 3 * FireworksScene.STRIDE, scene.mExtras.length);
        assertEquals("尾迹池大小不变", FireworksScene.MAX_TAILS, scene.mTails.length);

        // 放到上限:数组随组数放大
        assertTrue("首次变更应重建", scene.applySettings(FireworksScene.MAX_COUNT, false, false, false));
        assertEquals("上限上升槽位", 2250, scene.mNormal.length);
        assertEquals("上限炸开槽位", 3375, scene.mExtras.length);
        assertEquals("上限总槽位", 5625, FireworksScene.slots(FireworksScene.MAX_COUNT));
        // 满配置跑一段时间:上升 → 爆炸 → 重生 三条路径都不越界
        for (int i = 0; i < 300; i++) {
            scene.mNow += 16;
            scene.update();
        }

        // 调小:数组收缩,遍历上界跟着收缩,不越界
        scene.applySettings(1, false, false, false);
        assertEquals("调小后上升槽位", 1 * FireworksScene.STRIDE, scene.mNormal.length);
        assertEquals("调小后炸开槽位", 2 * FireworksScene.STRIDE, scene.mExtras.length);
        for (int i = 0; i < 60; i++) {
            scene.mNow += 16;
            scene.update();
        }
        scene.addTap(100, 200);
        scene.update();

        // 无变化时不应重建
        assertFalse("相同设置不重建", scene.applySettings(1, false, false, false));

        // 数组已按新组数重新填满(没有 null 槽位)
        for (int i = 0; i < scene.mNormal.length; i++) {
            assertTrue("mNormal[" + i + "] 非空", scene.mNormal[i] != null);
        }
        for (int i = 0; i < scene.mExtras.length; i++) {
            assertTrue("mExtras[" + i + "] 非空", scene.mExtras[i] != null);
        }
    }

    /**
     * 组首判定改为参数传入后,行为必须与原来的 indexOf + index % STRIDE == 0 一致。
     * 可观测的差异:组首的尾迹生命值会被乘 0.3(原版行为)。
     */
    private static void testLeaderFlagTailLife() {
        float leader = tailLifeFor(true);
        float child = tailLifeFor(false);
        assertEquals("组首尾迹生命值 = 普通 × 0.3", child * 0.3f, leader, 1.0E-4f);
    }

    /** 用同一颗粒子按"组首 / 普通"两种身份生成尾迹,返回落池后的 life。 */
    private static float tailLifeFor(boolean isLeader) {
        FireworksScene scene = new FireworksScene(1080, 1920);
        scene.initialize();
        FireworkParticle p = new FireworkParticle();
        p.dx = 0.0f;
        p.dy = -0.8f;
        p.life = 1.0f;
        p.ds = 1000;          // 满足"已移动足够距离"的生成条件
        p.hasTails = true;

        int index = scene.genTails(p, isLeader);
        assertTrue("尾迹池应有空位", index >= 0 && index < FireworksScene.MAX_TAILS);
        return scene.mTails[index].life;
    }

    private static void assertEquals(String name, float expect, float actual, float eps) {
        if (Math.abs(expect - actual) > eps) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void assertEquals(String name, int expect, int actual) {
        if (expect != actual) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void assertTrue(String name, boolean cond) {
        if (!cond) {
            failures++;
            System.out.println("失败: " + name);
        }
    }

    private static void assertFalse(String name, boolean cond) {
        assertTrue(name, !cond);
    }
}
