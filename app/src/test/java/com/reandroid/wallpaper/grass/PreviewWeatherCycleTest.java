package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PreviewWeatherCycle 的回归测试。
 *
 * <p>主用例是"轮播必须无限循环"。原实现在最后一档把 active 置 false，
 * 于是播完一遍就永久停在晴天 —— 盯前几档是看不出来的，只能靠跑满一轮再往前推。
 */
public class PreviewWeatherCycleTest {

    /** 跑满三轮：档位必须一轮一轮绕回来，不能中途停住。 */
    @Test
    public void loopsForever() {
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
            assertNotNull("第 " + (i + 1) + " 档就没得切了(轮播停住)", next);
            seen[count++] = next.ordinal();
        }

        // 期望:一轮是 D2..D9, D1(D1 是 reset 时给的初值，之后从 D2 开始)…只要保证
        // "连续 n 档不重复、且第 i 档与第 i+n 档相同"
        for (int i = 0; i + n < count; i++) {
            assertEquals("第 " + i + " 档(" + seen[i] + ")与第 " + (i + n)
                    + " 档(" + seen[i + n] + ")不同 —— 没有按周期轮转", seen[i], seen[i + n]);
        }
        boolean allSeen = true;
        for (WeatherCondition want : PreviewWeatherCycle.ORDER) {
            boolean found = false;
            for (int i = 0; i < n; i++) {
                if (seen[i] == want.ordinal()) { found = true; break; }
            }
            if (!found) allSeen = false;
        }
        assertTrue("一轮里没有走遍所有档位", allSeen);
    }

    /** reset 之后第一次 advance 必须立刻给出一档，不能干等一个间隔。 */
    @Test
    public void firstStepIsImmediate() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        WeatherCondition first = c.advance(1000L);
        assertNotNull("reset 后第一次 advance 返回 null —— 会先空等一个间隔", first);
        // 同一时刻再问一次不该又切
        assertNull("同一时刻连问两次都给了新档位", c.advance(1000L));
    }

    /** 节奏:一个间隔内只切一档。 */
    @Test
    public void cadence() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        long t = 0L;
        c.advance(t);                                  // 立刻一档，下一次到期 = t + INTERVAL
        long due = t + PreviewWeatherCycle.INTERVAL_MS;
        assertNull("未到期就切了", c.advance(due - 1));
        assertNotNull("到期了却没切", c.advance(due));
    }

    /** stop 之后不再推进；reset 能重新开始。 */
    @Test
    public void stopAndReset() {
        PreviewWeatherCycle c = new PreviewWeatherCycle();
        c.reset();
        c.advance(0L);
        c.stop();
        assertFalse("stop 之后 isActive 仍为 true", c.isActive());
        assertNull("stop 之后还在推进", c.advance(999999L));
        c.reset();
        assertTrue("reset 之后 isActive 仍为 false", c.isActive());
        assertEquals("reset 之后当前档位不是第一档", PreviewWeatherCycle.ORDER[0], c.current());
        assertNotNull("reset 之后无法重新推进", c.advance(0L));
    }

    /** 档位表必须覆盖全部天气 —— 少一个就会在预览里永远看不到那种天气。 */
    @Test
    public void orderCoversEveryCondition() {
        for (WeatherCondition want : WeatherCondition.values()) {
            boolean found = false;
            for (WeatherCondition c : PreviewWeatherCycle.ORDER) {
                if (c == want) { found = true; break; }
            }
            assertTrue(want + " 不在轮播表里", found);
        }
    }
}
