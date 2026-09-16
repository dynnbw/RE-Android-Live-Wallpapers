/*
 * PreviewWeatherCycle 的回归测试 —— 纯 JVM:
 *
 *   javac -d /tmp/cycletest \
 *         app/src/main/java/com/reandroid/weather/WeatherCondition.java \
 *         app/src/main/java/com/reandroid/wallpaper/grass/PreviewWeatherCycle.java \
 *         tools/grass-test/PreviewWeatherCycleTest.java
 *   java -cp /tmp/cycletest com.reandroid.wallpaper.grass.PreviewWeatherCycleTest
 *
 * 主用例是"轮播必须无限循环"。原实现在最后一档把 active 置 false，
 * 于是播完一遍就永久停在晴天 —— 盯前几档是看不出来的，只能靠跑满一轮再往前推。
 */
package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

public final class PreviewWeatherCycleTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testLoopsForever();
        testFirstStepIsImmediate();
        testCadence();
        testStopAndReset();
        testOrderCoversEveryCondition();

        System.out.println(failures == 0 ? "全部通过" : failures + " 个用例失败");
        if (failures != 0) System.exit(1);
    }

    /** 跑满三轮：档位必须一轮一轮绕回来，不能中途停住。 */
    private static void testLoopsForever() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();

        int n = PreviewWeatherCycle.ORDER.length;
        long t = 100000L;
        int[] seen = new int[n * 3];
        int count = 0;
        // 每次跨过一个间隔再问一次
        for (int i = 0; i < n * 3; i++) {
            t += PreviewWeatherCycle.INTERVAL_MS;
            WeatherCondition next = c.advance(t);
            if (next == null) {
                failures++;
                System.out.println("失败: 第 " + (i + 1) + " 档就没得切了(轮播停住)");
                return;
            }
            seen[count++] = next.ordinal();
        }

        // 期望:一轮是 D2..D9, D1(D1 是 reset 时给的初值，之后从 D2 开始)…只要保证
        // "连续 n 档不重复、且第 i 档与第 i+n 档相同"
        for (int i = 0; i + n < count; i++) {
            if (seen[i] != seen[i + n]) {
                failures++;
                System.out.println("失败: 第 " + i + " 档(" + seen[i] + ")与第 " + (i + n)
                        + " 档(" + seen[i + n] + ")不同 —— 没有按周期轮转");
                return;
            }
        }
        boolean allSeen = true;
        for (WeatherCondition want : PreviewWeatherCycle.ORDER) {
            boolean found = false;
            for (int i = 0; i < n; i++) {
                if (seen[i] == want.ordinal()) { found = true; break; }
            }
            if (!found) allSeen = false;
        }
        if (!allSeen) {
            failures++;
            System.out.println("失败: 一轮里没有走遍所有档位");
        } else {
            System.out.println("轮播三轮共 " + count + " 档，按周期 " + n + " 循环且覆盖全部档位");
        }
    }

    /** reset 之后第一次 advance 必须立刻给出一档，不能干等一个间隔。 */
    private static void testFirstStepIsImmediate() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        WeatherCondition first = c.advance(1000L);
        if (first == null) {
            failures++;
            System.out.println("失败: reset 后第一次 advance 返回 null —— 会先空等一个间隔");
        }
        // 同一时刻再问一次不该又切
        if (c.advance(1000L) != null) {
            failures++;
            System.out.println("失败: 同一时刻连问两次都给了新档位");
        }
    }

    /** 节奏:一个间隔内只切一档。 */
    private static void testCadence() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        long t = 0L;
        c.advance(t);                                  // 立刻一档，下一次到期 = t + INTERVAL
        long due = t + PreviewWeatherCycle.INTERVAL_MS;
        if (c.advance(due - 1) != null) {
            failures++;
            System.out.println("失败: 未到期就切了");
        }
        if (c.advance(due) == null) {
            failures++;
            System.out.println("失败: 到期了却没切");
        }
    }

    /** stop 之后不再推进；reset 能重新开始。 */
    private static void testStopAndReset() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        c.advance(0L);
        c.stop();
        if (c.isActive()) {
            failures++;
            System.out.println("失败: stop 之后 isActive 仍为 true");
        }
        if (c.advance(999999L) != null) {
            failures++;
            System.out.println("失败: stop 之后还在推进");
        }
        c.reset();
        if (!c.isActive()) {
            failures++;
            System.out.println("失败: reset 之后 isActive 仍为 false");
        }
        if (c.current() != PreviewWeatherCycle.ORDER[0]) {
            failures++;
            System.out.println("失败: reset 之后当前档位不是第一档");
        }
        if (c.advance(0L) == null) {
            failures++;
            System.out.println("失败: reset 之后无法重新推进");
        }
    }

    /** 档位表必须覆盖全部天气 —— 少一个就会在预览里永远看不到那种天气。 */
    private static void testOrderCoversEveryCondition() {
        for (WeatherCondition want : WeatherCondition.values()) {
            boolean found = false;
            for (WeatherCondition c : PreviewWeatherCycle.ORDER) {
                if (c == want) { found = true; break; }
            }
            if (!found) {
                failures++;
                System.out.println("失败: " + want + " 不在轮播表里");
            }
        }
    }
}
