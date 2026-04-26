package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class FogIceRenderer {
    public interface Drawer {
        void drawSprite(int texture, float x, float y, float z,
                        float scaleX, float scaleY, float rotation, float alpha);
    }

    public enum Config {
        OCEAN,
        WINDMILL
    }

    public void drawFogIce(Drawer drawer,
                           WeatherCondition condition,
                           boolean isNight,
                           float landscape,
                           int fog01,
                           int fog02,
                           int ice,
                           Config config) {
        if (condition == WeatherCondition.D4_FOG) {
            int fogTexture = selectFogTexture(config, isNight, fog01, fog02);
            float fogAlpha = selectFogAlpha(config, isNight);
            drawer.drawSprite(fogTexture, 0.0f, -0.5f, -20.0f,
                    0.65f * landscape, 1.05f, 0.0f, fogAlpha);
        }

        if (condition == WeatherCondition.D8_ICE_COLD) {
            drawer.drawSprite(ice, 0.0f, -0.5f, -20.0f,
                    0.65f * landscape, 1.05f, 0.0f, 1.0f);
        }
    }

    private int selectFogTexture(Config config, boolean isNight, int fog01, int fog02) {
        if (config == Config.OCEAN) {
            return isNight ? fog02 : fog01;
        }
        return fog02;
    }

    private float selectFogAlpha(Config config, boolean isNight) {
        if (config == Config.WINDMILL && isNight) {
            return 0.4f;
        }
        return 1.0f;
    }
}
