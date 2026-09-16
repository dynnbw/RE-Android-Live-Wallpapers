package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

/**
 * 预览里的天气轮播。纯逻辑，无 GL/Android 依赖，可 JVM 测试。
 *
 * <p>预览没有联网天气可用，就按固定间隔把所有档位轮一遍，用来肉眼检查每种天气的表现。
 *
 * <p>抽出来单独放是因为它的错法很隐蔽：原来的实现在最后一档写
 * {@code previewWeatherActive = false}，于是**播完一遍就永久停在晴天**，而预览里
 * 看一眼前几档根本看不出问题。这里 {@link #advance} 的返回值是"该切到哪一档"，
 * 循环与否一眼可测。
 */
final class PreviewWeatherCycle {

    /** 每档停留多久。 */
    static final long INTERVAL_MS = 3000L;

    /** 轮流播放的档位，从晴天开始。 */
    static final WeatherCondition[] ORDER = {
            WeatherCondition.D1_CLEAR,
            WeatherCondition.D2_CLOUDY,
            WeatherCondition.D3_DREARY,
            WeatherCondition.D4_FOG,
            WeatherCondition.D5_RAIN_SHOWERS,
            WeatherCondition.D6_THUNDERSTORMS,
            WeatherCondition.D7_FLURRIES_SNOW,
            WeatherCondition.D8_ICE_COLD,
            WeatherCondition.D9_SLEET
    };

    private boolean active;
    private int index;
    /** 下一档的到期时刻；0 表示还没排过期，{@link #advance} 会立刻推进一档。 */
    private long nextMs;

    /** 当前档位。 */
    WeatherCondition current() {
        return ORDER[index];
    }

    boolean isActive() {
        return active;
    }

    /** 从头开始轮播。 */
    void reset() {
        active = true;
        index = 0;
        nextMs = 0L;
    }

    /** 停住（天气开关关掉、场景停止时调用）。 */
    void stop() {
        active = false;
        nextMs = 0L;
    }

    /**
     * 推进到下一档（如果到期）。
     *
     * <p>走到最后一档会绕回第一档 —— 轮播是**无限**的，不要在末尾停下。
     *
     * @return 该切换到的档位；还没到期、或已停住时返回 {@code null}
     */
    WeatherCondition advance(long timeMs) {
        if (!active) {
            return null;
        }
        if (nextMs != 0L && timeMs < nextMs) {
            return null;
        }
        /*
         * nextMs 为 0 表示"还没排过期"（刚 reset 或刚被开关打开），这时要**直接推进一档**，
         * 而不是只排期就返回 —— 否则开关打开后先停在 D1_CLEAR 整整一个间隔，
         * 画面与关闭时完全一样，看上去就像"天气没生效"。
         */
        index = (index + 1) % ORDER.length;
        nextMs = timeMs + INTERVAL_MS;
        return ORDER[index];
    }
}
