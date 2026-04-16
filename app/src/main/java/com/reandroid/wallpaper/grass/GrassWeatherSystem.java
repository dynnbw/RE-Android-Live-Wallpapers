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
