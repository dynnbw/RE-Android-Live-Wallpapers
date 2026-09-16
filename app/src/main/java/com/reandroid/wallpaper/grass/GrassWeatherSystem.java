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
