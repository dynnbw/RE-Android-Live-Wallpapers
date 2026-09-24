package com.reandroid.wallpaper.grass;

import com.reandroid.utils.MathUtils;
import com.reandroid.weather.WeatherCondition;

/**
 * 草叶逆光的强度模型。
 *
 * <p>三件事都在这里算完：**总强度**、**高光门控**、**天气的压制**。三者交给着色器，
 * 于是"什么时候该出现""关掉是不是真的没了"这些都能在 JVM 里测，不用上机看。
 *
 * <p><b>两个量是分开的，因为它们驱动的东西不同：</b>
 * <ul>
 *   <li>{@link #strength} —— 驱动**高度渐变与阴影**。白天满、夜里一半、都没有就是 0。
 *       白天的观感就是靠它：上部亮、下部暗，均匀，没有高光。</li>
 *   <li>{@link #highlight} —— 驱动**透光与迎光边**。正午为 0（太阳在头顶，没有逆光可言），
 *       地平线处为 1（黄金时刻那套琥珀逆光），夜里月亮升起来也是 1。</li>
 * </ul>
 *
 * <p>早先只有一个量（峰值在地平线的那条曲线），于是逆光被限制在清晨黄昏。
 * 拆开之后白天有白天的样子、夜里有夜里的样子，昼夜用**连续量**过渡。
 *
 * <p>纯逻辑，无 GL / Android 依赖。
 */
final class GrassBacklight {

    private GrassBacklight() {
    }

    /** 地平线上下各这么多度内过渡。 */
    static final float FADE_DEG = 12.0f;

    /** 白天（太阳在地平线上）的总强度。 */
    static final float DAY_LEVEL = 1.0f;

    /** 夜里（月亮在地平线上）的总强度，取白天的一半。 */
    static final float NIGHT_LEVEL = 0.5f;

    /**
     * 总强度：驱动高度渐变与阴影。
     *
     * <p>太阳一升起来就满（{@link #FADE_DEG} 度内过渡），月亮按白天的一半算，
     * 两者取大。**两者都没有就是 0** —— "夜里没月亮就没光"由此自动成立，不用特判。
     */
    static float strength(float sunAltitudeDeg, float moonAltitudeDeg) {
        float sun = smoothstep(-FADE_DEG, 0.0f, sunAltitudeDeg);
        float moon = smoothstep(-FADE_DEG, 0.0f, moonAltitudeDeg);
        return Math.max(sun * DAY_LEVEL, moon * NIGHT_LEVEL);
    }

    /**
     * 高光门控：驱动透光与迎光边。
     *
     * <p>太阳那一项就是原来那条曲线 —— 峰值在地平线，正午和深夜都是 0。
     * **正午为 0 正是"白天不要高光"**：太阳在头顶，没有逆光可言，此时只剩
     * {@link #strength} 驱动的高度渐变与阴影。
     *
     * <p>月亮那一项按月亮高度角淡入，所以月亮升起时是渐显而不是"啪"地跳一下。
     */
    static float highlight(float sunAltitudeDeg, float moonAltitudeDeg) {
        float rise = smoothstep(-FADE_DEG, 0.0f, sunAltitudeDeg);
        float fall = 1.0f - smoothstep(0.0f, FADE_DEG, sunAltitudeDeg);
        float golden = rise * fall;
        // 月亮那一项**要被"太阳上来了多少"压住**：白天月亮也在天上（只是淡），
        // 那时不该由它把正午的高光点起来 —— 直接取 max 就会犯这个错
        // （测试 aDaytimeMoonDoesNotLightTheHighlight 就是为它写的）。
        float moon = smoothstep(-FADE_DEG, 0.0f, moonAltitudeDeg) * (1.0f - rise);
        return Math.max(golden, moon);
    }

    /**
     * 当前占优的光源是不是月亮。
     *
     * <p>比的是两者**各自贡献的强度**，不是简单地在太阳落山时切换 ——
     * 白天月亮也在天上（只是淡），那时不该让月亮抢走光源位置。
     */
    static boolean sourceIsMoon(float sunAltitudeDeg, float moonAltitudeDeg) {
        float sun = smoothstep(-FADE_DEG, 0.0f, sunAltitudeDeg) * DAY_LEVEL;
        float moon = smoothstep(-FADE_DEG, 0.0f, moonAltitudeDeg) * NIGHT_LEVEL;
        return moon > sun;
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
                                   float moonAltitudeDeg,
                                   WeatherCondition condition) {
        if (!enabled) {
            return 0.0f;
        }
        return strength(sunAltitudeDeg, moonAltitudeDeg) * weatherScale(condition);
    }

    /**
     * 选出光源的屏幕位置，写进 {@code out[0..1]}。
     *
     * <p>规则：谁贡献的强度大就用谁。太阳落山之后（或更低）就轮到月亮。
     *
     * <p><b>不再有"退回太阳位置"的兜底。</b> 原先那条是为了让新月那几天的黄昏仍有光晕 ——
     * 现在太阳在地平线下本来就是 0（{@link #strength}），兜底反而会造出"没有光源却在发光"。
     */
    static void lightPosition(boolean useMoon,
                              float sunX, float sunY, float moonX, float moonY, float[] out) {
        if (out.length < 2) {
            throw new IllegalArgumentException("数组至少要 2 个元素");
        }
        if (useMoon) {
            out[0] = moonX;
            out[1] = moonY;
            return;
        }
        out[0] = sunX;
        out[1] = sunY;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        return MathUtils.smoothStep(edge0, edge1, x);
    }
}
