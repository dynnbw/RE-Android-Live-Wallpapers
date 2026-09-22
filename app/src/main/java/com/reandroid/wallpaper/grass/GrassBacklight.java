package com.reandroid.wallpaper.grass;

import com.reandroid.utils.MathUtils;
import com.reandroid.weather.WeatherCondition;

/**
 * 草叶逆光（晨昏的透光与迎光边）的强度模型。
 *
 * <p>三件事都在这里算完：**强度随太阳高度角的曲线**、**天气的压制**、**光源取哪个**。
 * 三者相乘得到一个 0..1 的数交给着色器，着色器只认这一个总开关 ——
 * 于是"什么时候该出现""关掉是不是真的没了"这些都能在 JVM 里测，不用上机看。
 *
 * <p>纯逻辑，无 GL / Android 依赖。
 */
final class GrassBacklight {

    private GrassBacklight() {
    }

    /** 强度在地平线上下各这么多度内渐隐 —— 也就是"黄金时刻"那条带。 */
    static final float FADE_DEG = 12.0f;

    /**
     * 强度随太阳高度角，峰值在地平线。
     *
     * <p>两条 smoothstep 相乘而不是 {@code 1 - |alt| / FADE_DEG}：后者在峰值处是个**尖角**，
     * 太阳穿过地平线时强度会有一个折点；相乘在峰值附近是平的，过渡没有可察觉的拐角。
     *
     * @param sunAltitudeDeg 太阳高度角（度），见 {@code SceneData.lastSunAltitude}
     */
    static float strength(float sunAltitudeDeg) {
        float rise = MathUtils.smoothStep(-FADE_DEG, 0.0f, sunAltitudeDeg);
        float fall = 1.0f - MathUtils.smoothStep(0.0f, FADE_DEG, sunAltitudeDeg);
        return rise * fall;
    }

    /**
     * 天气对逆光的压制。
     *
     * <p>**刻意不复用 {@link GrassWeatherSystem#sunAlphaScale}**：那个是给太阳本体用的
     * （雷暴里是 0.30），而逆光在雷暴里应该接近 0 —— 云把光挡住了就没有背光可言。
     *
     * <p>取值是上机的起点，不是标准答案。
     */
    static float weatherScale(WeatherCondition condition) {
        switch (condition) {
            case D1_CLEAR: return 1.00f;
            case D2_CLOUDY: return 0.70f;
            case D3_DREARY: return 0.35f;
            case D4_FOG: return 0.25f;
            case D5_RAIN_SHOWERS: return 0.12f;
            case D6_THUNDERSTORMS: return 0.05f;
            case D7_FLURRIES_SNOW: return 0.45f;
            case D8_ICE_COLD: return 0.50f;
            case D9_SLEET: return 0.15f;
            default: return 1.00f;
        }
    }

    /**
     * 总强度 = 开关 × 高度角曲线 × 天气。
     *
     * <p>开关关掉时**恰好返回 0** —— 这是"关掉就等于今天"的可测形式。
     */
    static float effectiveStrength(boolean enabled, float sunAltitudeDeg,
                                   WeatherCondition condition) {
        if (!enabled) {
            return 0.0f;
        }
        return strength(sunAltitudeDeg) * weatherScale(condition);
    }

    /**
     * 选出光源的屏幕位置，写进 {@code out[0..1]}。
     *
     * <p>规则：太阳在地平线上就用太阳；太阳落了、月亮可见就用月亮（黄昏目标图里光晕正好
     * 在月亮上）；两者都不在天上时**退回太阳位置** —— 它那时在屏幕下缘之外，
     * 正好代表"地平线上的余晖"，而且位置与高度角无关地一直在算，不花额外代价。
     */
    static void lightPosition(float sunAltitudeDeg, float sunX, float sunY,
                              boolean moonVisible, float moonX, float moonY, float[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("数组至少要 2 个元素");
        }
        if (sunAltitudeDeg <= 0.0f && moonVisible) {
            out[0] = moonX;
            out[1] = moonY;
            return;
        }
        out[0] = sunX;
        out[1] = sunY;
    }
}
