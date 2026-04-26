package com.reandroid.wallpaper.weatherwallpapers.windmill;

import com.reandroid.weather.WeatherCondition;

class GroundRenderer {
    interface Drawer {
        void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                     float scaleX, float scaleY, float rotation, float alpha);
    }

    static final class GroundTextures {
        final int farLand;
        final int nearLand;
        final int lawn;

        GroundTextures(int farLand, int nearLand, int lawn) {
            this.farLand = farLand;
            this.nearLand = nearLand;
            this.lawn = lawn;
        }
    }

    GroundTextures selectTextures(WeatherCondition condition,
                                  boolean isNight,
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
        int nearLand = selectNearLand(condition, isNight, land01, land03, land05, land06, land08);
        int farLand = selectFarLand(condition, isNight, land02, land04, land07, land09);
        int lawn = selectLawn(condition, isNight, lawn01, lawn02, lawn03, lawn04, lawn05);
        return new GroundTextures(farLand, nearLand, lawn);
    }

    void drawFarLand(Drawer drawer,
                     int texture,
                     float offset,
                     float landscape) {
        drawer.drawSpriteRectOneToFour(
                texture,
                (-1.5f) + ((1.5f - (offset * 0.5f)) * 5.0f),
                -5.2f,
                -24.0f,
                3.6f * landscape,
                1.8f,
                0.0f,
                1.0f
        );
    }

    void drawNearLand(Drawer drawer,
                      int texture,
                      float offset,
                      float landscape) {
        drawer.drawSpriteRectOneToFour(
                texture,
                (1.5f - (offset * 1.2f)) * 5.0f,
                -6.4f,
                -23.0f,
                3.5f * landscape,
                3.2f,
                0.0f,
                1.0f
        );
    }

    void drawLawn(Drawer drawer,
                  int texture,
                  float offset,
                  float landscape) {
        drawer.drawSpriteRectOneToFour(
                texture,
                (1.5f - (offset * 1.2f)) * 5.0f,
                -4.3f,
                -23.0f,
                3.5f * landscape,
                1.0f,
                0.0f,
                1.0f
        );
    }

    private int selectNearLand(WeatherCondition condition,
                               boolean isNight,
                               int land01,
                               int land03,
                               int land05,
                               int land06,
                               int land08) {
        switch (condition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return isNight ? land03 : land01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return isNight ? land08 : land06;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return isNight ? land03 : land05;
        }
    }

    private int selectFarLand(WeatherCondition condition,
                              boolean isNight,
                              int land02,
                              int land04,
                              int land07,
                              int land09) {
        switch (condition) {
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return isNight ? land09 : land07;
            default:
                return isNight ? land04 : land02;
        }
    }

    private int selectLawn(WeatherCondition condition,
                           boolean isNight,
                           int lawn01,
                           int lawn02,
                           int lawn03,
                           int lawn04,
                           int lawn05) {
        switch (condition) {
            case D1_CLEAR:
            case D5_RAIN_SHOWERS:
                return isNight ? lawn02 : lawn01;
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return isNight ? lawn05 : lawn04;
            case D2_CLOUDY:
            case D3_DREARY:
            case D4_FOG:
            case D6_THUNDERSTORMS:
            case D8_ICE_COLD:
            default:
                return isNight ? lawn02 : lawn03;
        }
    }
}
