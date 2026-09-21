package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.utils.MathUtils;
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
                           float nightWeight,
                           float landscape,
                           int fog01,
                           int fog02,
                           int ice,
                           Config config) {
        if (condition == WeatherCondition.D4_FOG) {
            float fogAlpha = selectFogAlpha(config, nightWeight);
            if (config == Config.OCEAN) {
                // 海洋的雾有白天/夜里两张，按权重交叉淡入；风车的只有一张
                drawFog(drawer, fog01, fog02, nightWeight, landscape, fogAlpha);
            } else {
                drawer.drawSprite(fog02, 0.0f, -0.5f, -20.0f,
                        0.65f * landscape, 1.05f, 0.0f, fogAlpha);
            }
        }

        if (condition == WeatherCondition.D8_ICE_COLD) {
            drawer.drawSprite(ice, 0.0f, -0.5f, -20.0f,
                    0.65f * landscape, 1.05f, 0.0f, 1.0f);
        }
    }

    /**
     * 两张雾按权重交叉淡入。
     *
     * <p>权重为 0 或 1 时只画其中一张 —— 白天和深夜里这个改动不花钱，
     * 只有晨昏那半小时会画两遍。
     */
    private static void drawFog(Drawer drawer, int dayTexture, int nightTexture,
                                float nightWeight, float landscape, float alpha) {
        float day = 1.0f - nightWeight;
        if (day > 0.0f) {
            drawer.drawSprite(dayTexture, 0.0f, -0.5f, -20.0f,
                    0.65f * landscape, 1.05f, 0.0f, alpha * day);
        }
        if (nightWeight > 0.0f) {
            drawer.drawSprite(nightTexture, 0.0f, -0.5f, -20.0f,
                    0.65f * landscape, 1.05f, 0.0f, alpha * nightWeight);
        }
    }

    /** 风车夜里的雾更淡；海洋的不分昼夜。 */
    private static float selectFogAlpha(Config config, float nightWeight) {
        return config == Config.WINDMILL ? MathUtils.mix(1.0f, 0.4f, nightWeight) : 1.0f;
    }
}
