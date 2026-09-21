package com.reandroid.wallpaper.weatherwallpapers.windmill;

import com.reandroid.weather.WeatherCondition;

class GroundRenderer {
    interface Drawer {
        void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                     float scaleX, float scaleY, float rotation, float alpha);
    }

    /**
     * 每层地皮都带白天、夜里两张。
     *
     * <p>以前只带一张 —— 选哪张在 {@link #selectTextures} 里就定死了。要渐变就得
     * 把两张都带出来，绘制时按权重叠。
     */
    static final class GroundTextures {
        final int farLand;
        final int farLandNight;
        final int nearLand;
        final int nearLandNight;
        final int lawn;
        final int lawnNight;

        GroundTextures(int farLand, int farLandNight,
                       int nearLand, int nearLandNight,
                       int lawn, int lawnNight) {
            this.farLand = farLand;
            this.farLandNight = farLandNight;
            this.nearLand = nearLand;
            this.nearLandNight = nearLandNight;
            this.lawn = lawn;
            this.lawnNight = lawnNight;
        }
    }

    GroundTextures selectTextures(WeatherCondition condition,
                                  int land01,
                                  int land02,
                                  int land03,
                                  int land04,
                                  int land05,
                                  int land06,
                                  int land07,
                                  int land08,
                                  int land09,
                                  int lawn01,
                                  int lawn02,
                                  int lawn03,
                                  int lawn04,
                                  int lawn05) {
        return new GroundTextures(
                selectFarLand(condition, false, land02, land04, land07, land09),
                selectFarLand(condition, true, land02, land04, land07, land09),
                selectNearLand(condition, false, land01, land03, land05, land06, land08),
                selectNearLand(condition, true, land01, land03, land05, land06, land08),
                selectLawn(condition, false, lawn01, lawn02, lawn03, lawn04, lawn05),
                selectLawn(condition, true, lawn01, lawn02, lawn03, lawn04, lawn05));
    }

    void drawFarLand(Drawer drawer,
                     int dayTexture,
                     int nightTexture,
                     float offset,
                     float landscape,
                     float nightWeight) {
        drawGround(drawer, dayTexture, nightTexture, nightWeight,
                (-1.5f) + ((1.5f - (offset * 0.5f)) * 5.0f),
                -5.2f,
                -24.0f,
                3.6f * landscape,
                1.8f);
    }

    void drawNearLand(Drawer drawer,
                      int dayTexture,
                      int nightTexture,
                      float offset,
                      float landscape,
                      float nightWeight) {
        drawGround(drawer, dayTexture, nightTexture, nightWeight,
                (1.5f - (offset * 1.2f)) * 5.0f,
                -6.4f,
                -23.0f,
                3.5f * landscape,
                3.2f);
    }

    void drawLawn(Drawer drawer,
                  int dayTexture,
                  int nightTexture,
                  float offset,
                  float landscape,
                  float nightWeight) {
        drawGround(drawer, dayTexture, nightTexture, nightWeight,
                (1.5f - (offset * 1.2f)) * 5.0f,
                -4.3f,
                -23.0f,
                3.5f * landscape,
                1.0f);
    }

    /**
     * 白天那张整块铺上，夜里那张按权重盖在上面。
     *
     * <p>两张都是不透明的，所以这样叠出来的就是**精确**的交叉淡入
     * （{@code N·w + D·(1−w)}）—— 若改成两张各乘自己的权重，背景会从缝里透出来。
     * 权重为 0、或昼夜本来就是同一张时，第二遍直接跳过。
     */
    private static void drawGround(Drawer drawer, int dayTexture, int nightTexture,
                                   float nightWeight, float x, float y, float z,
                                   float scaleX, float scaleY) {
        drawer.drawSpriteRectOneToFour(dayTexture, x, y, z, scaleX, scaleY, 0.0f, 1.0f);
        if (nightTexture != dayTexture && nightWeight > 0.0f) {
            drawer.drawSpriteRectOneToFour(nightTexture, x, y, z, scaleX, scaleY, 0.0f, nightWeight);
        }
    }

    private static int selectNearLand(WeatherCondition condition,
                                      boolean night,
                                      int land01,
                                      int land03,
                                      int land05,
                                      int land06,
                                      int land08) {
        switch (condition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return night ? land03 : land01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return night ? land08 : land06;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return night ? land03 : land05;
        }
    }

    private static int selectFarLand(WeatherCondition condition,
                                     boolean night,
                                     int land02,
                                     int land04,
                                     int land07,
                                     int land09) {
        switch (condition) {
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return night ? land09 : land07;
            default:
                return night ? land04 : land02;
        }
    }

    private static int selectLawn(WeatherCondition condition,
                                  boolean night,
                                  int lawn01,
                                  int lawn02,
                                  int lawn03,
                                  int lawn04,
                                  int lawn05) {
        switch (condition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return night ? lawn02 : lawn01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return night ? lawn05 : lawn04;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return night ? lawn02 : lawn03;
        }
    }
}
