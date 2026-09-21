package com.reandroid.wallpaper.grass;

import com.reandroid.weather.WeatherCondition;

final class GrassWeatherSystem {
    private GrassWeatherSystem() {
    }

    static float brightnessMultiplier(WeatherCondition condition) {
        switch (condition) {
            case D2_CLOUDY: return 0.92f;
            case D3_DREARY: return 0.76f;
            case D4_FOG: return 0.72f;
            case D5_RAIN_SHOWERS: return 0.66f;
            case D6_THUNDERSTORMS: return 0.58f;
            case D7_FLURRIES_SNOW: return 0.82f;
            case D8_ICE_COLD: return 0.84f;
            case D9_SLEET: return 0.64f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    static float windTimeScale(WeatherCondition condition) {
        switch (condition) {
            case D5_RAIN_SHOWERS: return 1.55f;
            case D6_THUNDERSTORMS: return 1.9f;
            case D9_SLEET: return 1.6f;
            case D3_DREARY: return 1.2f;
            case D2_CLOUDY: return 1.1f;
            case D4_FOG: return 0.85f;
            case D7_FLURRIES_SNOW:
            case D8_ICE_COLD:
                return 0.9f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    static float windAmplitudeScale(WeatherCondition condition) {
        switch (condition) {
            case D5_RAIN_SHOWERS: return 1.45f;
            case D6_THUNDERSTORMS: return 1.7f;
            case D9_SLEET: return 1.5f;
            case D3_DREARY: return 1.2f;
            case D2_CLOUDY: return 1.05f;
            case D4_FOG: return 0.75f;
            case D7_FLURRIES_SNOW:
            case D8_ICE_COLD:
                return 0.85f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    static float windDayNightScale(WeatherCondition condition, boolean isNight) {
        if (!isNight) {
            return 1.0f;
        }
        switch (condition) {
            case D6_THUNDERSTORMS: return 0.92f;
            case D5_RAIN_SHOWERS:
            case D9_SLEET:
                return 0.88f;
            case D7_FLURRIES_SNOW:
            case D8_ICE_COLD:
                return 0.82f;
            case D4_FOG:
                return 0.78f;
            case D3_DREARY:
            case D2_CLOUDY:
                return 0.90f;
            case D1_CLEAR:
            default:
                return 0.94f;
        }
    }

    static boolean allowsDandelion(WeatherCondition condition) {
        switch (condition) {
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            case D7_FLURRIES_SNOW:
            case D8_ICE_COLD:
            case D9_SLEET:
            case D3_DREARY:
            case D4_FOG:
                return false;
            case D1_CLEAR:
            case D2_CLOUDY:
            default:
                return true;
        }
    }

    static boolean allowsFirefly(WeatherCondition condition) {
        switch (condition) {
            case D1_CLEAR:
            case D2_CLOUDY:
                return true;
            default:
                return false;
        }
    }

    /**
     * 天气放行的淡入淡出。
     *
     * <p>{@link #allowsDandelion} / {@link #allowsFirefly} 是 0/1 的硬开关。直接把它们
     * 乘进可见度，粒子就会在切到阵雨/雷暴的那一帧凭空消失（切回来又凭空出现）——
     * 与风相位瞬移是同一类毛病。这里改成按固定时长线性逼近。
     *
     * <p>步长按 {@code dt} 折算，所以淡入淡出时长与帧率无关。
     *
     * @param current     当前值
     * @param allowed     目标是否为 1（否则为 0）
     * @param dt          本帧秒数
     * @param fadeSeconds 从 0 走到 1 所需的时长；非正数表示不淡、直接到位
     * @return 新值，落在 [0,1]
     */
    static float fadeGate(float current, boolean allowed, float dt, float fadeSeconds) {
        float target = allowed ? 1.0f : 0.0f;
        if (fadeSeconds <= 0.0f) {
            return target;
        }
        float step = Math.max(0.0f, dt) / fadeSeconds;
        if (current < target) {
            return Math.min(target, current + step);
        }
        if (current > target) {
            return Math.max(target, current - step);
        }
        return current;
    }

    /**
     * 雨量强度，0..5。
     *
     * <p>范围取自参考实现 {@code rain_screen_fragment_shader.glsl} 的 {@code uIntensity}
     * —— 那里到处是 {@code /5.}（如 {@code mix(1.3, 1.0, uIntensity1)}）。具体取值在混淆过的
     * Java 里**没能提取出来**，这里按档位给，上机调。
     */
    static float rainIntensity(WeatherCondition condition) {
        switch (condition) {
            case D6_THUNDERSTORMS: return 5.0f;
            case D5_RAIN_SHOWERS: return 2.5f;
            case D9_SLEET: return 2.0f;
            default: return 0.0f;
        }
    }

    static float sunAlphaScale(WeatherCondition condition) {
        switch (condition) {
            case D3_DREARY: return 0.72f;
            case D4_FOG: return 0.55f;
            case D5_RAIN_SHOWERS: return 0.45f;
            case D6_THUNDERSTORMS: return 0.30f;
            case D7_FLURRIES_SNOW: return 0.62f;
            case D8_ICE_COLD: return 0.66f;
            case D9_SLEET: return 0.40f;
            case D2_CLOUDY: return 0.86f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    static float moonAlphaScale(WeatherCondition condition) {
        switch (condition) {
            case D3_DREARY: return 0.78f;
            case D4_FOG: return 0.62f;
            case D5_RAIN_SHOWERS: return 0.52f;
            case D6_THUNDERSTORMS: return 0.35f;
            case D7_FLURRIES_SNOW: return 0.70f;
            case D8_ICE_COLD: return 0.74f;
            case D9_SLEET: return 0.48f;
            case D2_CLOUDY: return 0.90f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    /**
     * 星空被天气遮蔽的程度。
     *
     * <p>太阳和月亮早就有对应的 scale，星星这一层漏了 —— 于是阴雨夜里星星照旧满天，
     * 与"云层挡天"这件事自相矛盾。
     *
     * <p>数值是眼调的。原先按"渲染层画几片云"排序，但多云(D2)调高到 0.60 之后那条规则不再成立
     * —— 多云只画 8 片却比只画 2 片的飘雪留得多，这是刻意的：多云的天本身还是晴的。
     * 仍然成立的是：阴沉比多云更遮天，雷暴最遮天，且都比日月压得狠（星星比日月暗得多，
     * 所以这里的衰减比 {@link #sunAlphaScale} 陡）。
     *
     * <p>原版是画完星星再无条件把云盖上去（{@code bwlw.drawFrame}：夜里
     * {@code setShader(mShaderNight)} → {@code drawStars} → {@code drawClouds}），
     * 靠云的覆盖度挡星星。我们这里改成直接按天气压可见度，覆盖度不全时也挡得住。
     */
    /**
     * 这种天气要不要叠那层灰蓝色的天空色调（{@code texWeatherTone}）。
     *
     * <p>晴天不叠是多云也一样 —— 多云只是天上多几朵云，天本身还是蓝的，压一层灰蓝反而发闷。
     * 从阴沉往下才有"整个天被盖住"的意思。
     *
     * <p>场景层（算淡入淡出）和渲染层（决定画不画）都用这一个判断，别各写一份。
     */
    static boolean hasSkyTone(WeatherCondition condition) {
        switch (condition) {
            case D1_CLEAR:
            case D2_CLOUDY:
                return false;
            default:
                return true;
        }
    }

    static float starVisibilityScale(WeatherCondition condition) {
        switch (condition) {
            case D2_CLOUDY: return 0.60f;
            case D7_FLURRIES_SNOW: return 0.45f;
            case D8_ICE_COLD: return 0.20f;
            case D3_DREARY: return 0.12f;
            case D4_FOG: return 0.10f;
            case D5_RAIN_SHOWERS: return 0.10f;
            case D9_SLEET: return 0.10f;
            case D6_THUNDERSTORMS: return 0.04f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }

    /**
     * 夜里那些**反光**的天气精灵被压到的颜色（白天是纯白）：云、雾、雨、雪。
     *
     * <p>它们的贴图都是亮色（云 215,217,223；雾和雪 255,255,255；雨 187,219,255），而夜里
     * 没有光源 —— 那就该是暗的。偏蓝一点点，好和夜空(#000A20 → #1956B0)同调。
     *
     * <p><b>闪电不参与</b>：它是自发光，夜里照样亮（见 GrassWeatherRenderer 里的单独处理）。
     *
     * <p><b>不要</b>退回成"降透明度假装看不见"。那正是原来的做法（夜里云乘 0.30 alpha），
     * 结果是云几乎消失、星空直接透出来，什么天气的夜空都长一样。要完整画出来，只是变暗。
     *
     * <p>也不要压到 0：纯黑的云在天空上是个洞。实测这些值下云比夜空稍暗，读起来是剪影。
     */
    static final float NIGHT_WEATHER_R = 0.10f;
    static final float NIGHT_WEATHER_G = 0.11f;
    static final float NIGHT_WEATHER_B = 0.14f;

    /**
     * 天气精灵染色的单通道取值：夜里用 {@link #NIGHT_WEATHER_R} 等，白天回到 1（纯白）。
     *
     * @param nightValue 夜间的通道值
     * @param dayWeight  白天权重，0 是深夜、1 是白天（取自 SceneData.dayWeight）
     */
    static float weatherSpriteTint(float nightValue, float dayWeight) {
        float t = dayWeight < 0.0f ? 0.0f : (dayWeight > 1.0f ? 1.0f : dayWeight);
        return nightValue + (1.0f - nightValue) * t;
    }

    static float moonBrightnessScale(WeatherCondition condition) {
        switch (condition) {
            case D3_DREARY: return 0.84f;
            case D4_FOG: return 0.75f;
            case D5_RAIN_SHOWERS: return 0.65f;
            case D6_THUNDERSTORMS: return 0.52f;
            case D7_FLURRIES_SNOW: return 0.82f;
            case D8_ICE_COLD: return 0.85f;
            case D9_SLEET: return 0.62f;
            case D2_CLOUDY: return 0.92f;
            case D1_CLEAR:
            default:
                return 1.0f;
        }
    }
}
