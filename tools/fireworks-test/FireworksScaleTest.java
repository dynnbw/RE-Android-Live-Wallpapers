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

    /** 占位:Task 2 完成后填充重建/调小相关断言。 */
    private static void testRebuild() {
        // Task 2 Step 1 会替换本方法体
    }

    private static void assertEquals(String name, int expect, int actual) {
        if (expect != actual) {
            failures++;
            System.out.println("失败: " + name + " 期望 " + expect + " 实际 " + actual);
        }
    }
}
