package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class FrostRenderer {
    public interface Drawer {
        void drawSprite(int texture, float x, float y, float z,
                        float scaleX, float scaleY, float rotation, float alpha);
    }

    public void drawFrost(Drawer drawer,
                   WeatherCondition condition,
                   float landscape,
                   int frostC,
                   int frostE,
                   int frostF) {
        if (condition == WeatherCondition.D5_RAIN_SHOWERS
                || condition == WeatherCondition.D7_FLURRIES_SNOW
                || condition == WeatherCondition.D9_SLEET) {
            if (condition == WeatherCondition.D9_SLEET) {
                drawer.drawSprite(frostF, -0.1f, -0.5f, -20.3f, 0.65f * landscape, 1.05f, 0.0f, 1.0f);
            } else if (condition == WeatherCondition.D5_RAIN_SHOWERS) {
                drawer.drawSprite(frostC, 0.0f, -0.5f, -20.3f, 0.63f * landscape, 1.05f, 0.0f, 1.0f);
            } else {
                drawer.drawSprite(frostE, 0.0f, -0.5f, -20.3f, 0.63f * landscape, 1.05f, 0.0f, 1.0f);
            }
        }
    }
}