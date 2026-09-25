package com.reandroid.weather;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * "缓存过期了吗"这一条判据。
 *
 * <p>它决定了两件事的时机：壁纸重新可见、主程序打开时要不要立刻取一次。
 * 判错的方向有两个，都比"多取一次"糟：判成没过期 → 用户看到的是几小时前的天气；
 * 判成过期 → 只是白跑一个请求。所以边界取 {@code >=}。
 */
public class WeatherManagerStaleTest {

    private static final long NOW = 1_800_000_000L;

    @Test
    public void noCacheIsAlwaysStale() {
        assertTrue("没有缓存就该取", WeatherManager.isStale(0L, NOW, 30));
        assertTrue("负数（脏数据）也算没有", WeatherManager.isStale(-1L, NOW, 30));
    }

    /** 刚好到点算过期 —— 到点了就该取，不该再等一个周期。 */
    @Test
    public void exactlyAtTheIntervalIsStale() {
        assertTrue(WeatherManager.isStale(NOW - 30 * 60L, NOW, 30));
    }

    @Test
    public void oneSecondBeforeIsFresh() {
        assertFalse(WeatherManager.isStale(NOW - 30 * 60L + 1L, NOW, 30));
    }

    @Test
    public void clockGoingBackwardsIsNotStale() {
        // 用户手动改时间/时区，缓存时间落在"未来"——不该判成过期然后疯取
        assertFalse(WeatherManager.isStale(NOW + 3600L, NOW, 30));
    }

    @Test
    public void intervalIsClampedToOneMinute() {
        // 设置里最小是 15 分钟，但读取路径上有个 Math.max(10, ...) 的兜底；
        // 这里再挡一层：间隔传 0 或负数时不至于每次都判过期
        assertFalse(WeatherManager.isStale(NOW - 30L, NOW, 0));
        assertTrue(WeatherManager.isStale(NOW - 60L, NOW, 0));
        assertFalse(WeatherManager.isStale(NOW - 30L, NOW, -5));
    }

    @Test
    public void longerIntervalsWaitLonger() {
        long updatedThreeHoursAgo = NOW - 3 * 3600L;
        assertTrue("三小时前，15 分钟间隔当然过期", WeatherManager.isStale(updatedThreeHoursAgo, NOW, 15));
        assertFalse("三小时前，4 小时间隔还没到", WeatherManager.isStale(updatedThreeHoursAgo, NOW, 240));
        // 三小时整**正好**是 180 分钟 —— 按"到点即过期"算过期（这条一开始被我写成相反，留个备忘）
        assertTrue("整 180 分钟到点即过期", WeatherManager.isStale(NOW - 180 * 60L, NOW, 180));
    }
}
