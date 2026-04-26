package com.reandroid.wallpaper.weatherwallpapers.ocean;

import com.reandroid.weather.WeatherCondition;

class OceanWaterSurfaceRenderer {
    interface Drawer {
        void drawSprite(int texture, float x, float y, float z,
                        float scaleX, float scaleY, float rotation, float alpha);
    }

    void drawWaterCover(Drawer drawer,
                        WeatherCondition condition,
                        boolean isNight,
                        float offset,
                        float landscape,
                        int watercover1,
                        int watercover2,
                        int watercover3,
                        int watercover4,
                        int nightcover,
                        int capCover) {
        if (condition == WeatherCondition.D1_CLEAR) {
            if (isNight) {
                drawer.drawSprite(watercover1, 0.0f + ((1.5f - offset) * 5.0f), -4.95f, -21.0f,
                        3.2f * landscape, 3.6f, 0.0f, 0.8f);
            }
            return;
        }

        if (condition == WeatherCondition.D2_CLOUDY
                || condition == WeatherCondition.D4_FOG
                || condition == WeatherCondition.D8_ICE_COLD) {
            if (!isNight) {
                drawer.drawSprite(watercover2, (-1.5f) + ((1.5f - offset) * 5.0f), -3.3f, -21.0f,
                        3.0f * landscape, 2.75f, 0.0f, 0.8f);
            } else {
                drawer.drawSprite(watercover1, 0.0f + ((1.5f - offset) * 5.0f), -4.95f, -21.0f,
                        3.2f * landscape, 3.6f, 0.0f, 0.8f);
                drawer.drawSprite(capCover, 0.0f, -0.5f, -20.3f, 0.63f * landscape, 1.05f, 0.0f, 0.2f);
            }
            return;
        }

        if (condition == WeatherCondition.D3_DREARY
                || condition == WeatherCondition.D5_RAIN_SHOWERS
                || condition == WeatherCondition.D6_THUNDERSTORMS
                || condition == WeatherCondition.D7_FLURRIES_SNOW
                || condition == WeatherCondition.D9_SLEET) {
            if (!isNight) {
                drawer.drawSprite(selectWatercoverStorm(condition, watercover3, watercover4),
                        (-1.5f) + ((1.5f - offset) * 5.0f),
                        -4.95f,
                        -21.0f,
                        3.0f * landscape,
                        3.6f,
                        0.0f,
                        0.8f);
            } else {
                drawer.drawSprite(watercover1, 0.0f + ((1.5f - offset) * 5.0f), -4.95f, -21.0f,
                        3.2f * landscape, 3.6f, 0.0f, 0.8f);
                int cover = (condition == WeatherCondition.D3_DREARY || condition == WeatherCondition.D6_THUNDERSTORMS)
                        ? capCover : nightcover;
                drawer.drawSprite(cover, 0.0f, -0.5f, -20.3f, 0.63f * landscape, 1.05f, 0.0f, 0.2f);
            }
        }
    }

    private int selectWatercoverStorm(WeatherCondition condition, int watercover3, int watercover4) {
        if (condition == WeatherCondition.D7_FLURRIES_SNOW || condition == WeatherCondition.D9_SLEET) {
            return watercover4;
        }
        return watercover3;
    }
}