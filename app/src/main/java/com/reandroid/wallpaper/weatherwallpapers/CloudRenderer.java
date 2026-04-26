package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class CloudRenderer {
    public static final class Config {
        public static final Config OCEAN = new Config(false);
        public static final Config WINDMILL = new Config(true);

        private final boolean mWindmill;

        private Config(boolean windmill) {
            mWindmill = windmill;
        }
    }

    public interface Drawer {
        void drawSpriteRectOneToTwo(int texture, float x, float y, float z,
                                    float scaleX, float scaleY, float rotation, float alpha);

        void drawSpriteColoredRectOneToTwo(int texture, float x, float y, float z,
                                           float scaleX, float scaleY, float rotation,
                                           float r, float g, float b, float alpha);
    }

    private int[] mCloudLightStart;
    private int[] mCloudLightNum;
    private int[] mCloudLightPos;
    private int[] mCloudLightDuration;

    private float mCloudAX1 = 0.0f;
    private float mCloudAX2 = 0.0f;
    private float mCloudAX3 = 0.0f;
    private float mCloudAX4 = 0.0f;
    private float mCloudBX1 = 0.0f;
    private float mCloudBX2 = 0.0f;
    private float mCloudBX3 = 0.0f;
    private float mCloudBX4 = 0.0f;
    private float mCloudBX5 = 0.0f;
    private float mCloudBX6 = 0.0f;
    private float mCloudBX7 = 0.0f;

    public void initMemory() {
        mCloudLightStart = new int[20];
        mCloudLightNum = new int[20];
        mCloudLightPos = new int[20];
        mCloudLightDuration = new int[20];
    }

    public boolean drawClouds(Drawer drawer,
                       WeatherCondition condition,
                       boolean isNight,
                       int frameCnt,
                       float offset,
                       float landscape,
                       int cloudA01,
                       int cloudA02,
                       int cloudA03,
                       int cloudB01,
                       int cloudB02,
                       int cloudB03,
                       int cloudLightA1,
                       int cloudLightA2,
                       int cloudLightA3,
                       int cloudLightB1,
                       int cloudLightB2,
                       int cloudLightB3,
                       boolean thunderOn) {
                return drawClouds(
                    drawer,
                    condition,
                    isNight,
                    frameCnt,
                    offset,
                    landscape,
                    cloudA01,
                    cloudA02,
                    cloudA03,
                    cloudB01,
                    cloudB02,
                    cloudB03,
                    cloudLightA1,
                    cloudLightA2,
                    cloudLightA3,
                    cloudLightB1,
                    cloudLightB2,
                    cloudLightB3,
                    thunderOn,
                    Config.OCEAN
                );
                }

                public boolean drawClouds(Drawer drawer,
                              WeatherCondition condition,
                              boolean isNight,
                              int frameCnt,
                              float offset,
                              float landscape,
                              int cloudA01,
                              int cloudA02,
                              int cloudA03,
                              int cloudB01,
                              int cloudB02,
                              int cloudB03,
                              int cloudLightA1,
                              int cloudLightA2,
                              int cloudLightA3,
                              int cloudLightB1,
                              int cloudLightB2,
                              int cloudLightB3,
                              boolean thunderOn,
                              Config config) {
                if (config != null && config.mWindmill) {
                    return drawWindmillClouds(
                        drawer,
                        condition,
                        isNight,
                        frameCnt,
                        offset,
                        landscape,
                        cloudA01,
                        cloudA02,
                        cloudA03,
                        cloudB01,
                        cloudB02,
                        cloudB03,
                        cloudLightA1,
                        cloudLightA2,
                        cloudLightA3,
                        cloudLightB1,
                        cloudLightB2,
                        cloudLightB3,
                        thunderOn
                    );
                }

                return drawOceanClouds(
                    drawer,
                    condition,
                    isNight,
                    frameCnt,
                    offset,
                    landscape,
                    cloudA01,
                    cloudA02,
                    cloudA03,
                    cloudB01,
                    cloudB02,
                    cloudB03,
                    cloudLightA1,
                    cloudLightA2,
                    cloudLightA3,
                    cloudLightB1,
                    cloudLightB2,
                    cloudLightB3,
                    thunderOn
                );
                }

                private boolean drawOceanClouds(Drawer drawer,
                                WeatherCondition condition,
                                boolean isNight,
                                int frameCnt,
                                float offset,
                                float landscape,
                                int cloudA01,
                                int cloudA02,
                                int cloudA03,
                                int cloudB01,
                                int cloudB02,
                                int cloudB03,
                                int cloudLightA1,
                                int cloudLightA2,
                                int cloudLightA3,
                                int cloudLightB1,
                                int cloudLightB2,
                                int cloudLightB3,
                                boolean thunderOn) {
        int cloudA = selectCloudATexture(condition, cloudA01, cloudA02, cloudA03);
        int cloudB = selectCloudBTexture(condition, cloudB01, cloudB02, cloudB03);

        if ((condition == WeatherCondition.D1_CLEAR || condition == WeatherCondition.D8_ICE_COLD) && !isNight) {
            if (frameCnt < 300) {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) - 13.0d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 27.0d);
            }
            if (frameCnt < 360) {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) - 11.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) + 29.0d);
            }
            if (frameCnt < 300) {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) - 9.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) + 31.0d);
            }
            if (frameCnt < 900) {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 2.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 42.0d);
            }
            if (frameCnt < 940) {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 8.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 48.0d);
            }
            if (frameCnt < 900) {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 6.0d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 46.0d);
            }
            drawer.drawSpriteRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - offset) * 5.0f), 6.0f, -27.0f,
                    1.3f * landscape, 1.3f, 0.0f, 0.6f);
            drawer.drawSpriteRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - offset) * 5.0f), 1.0f, -27.0f,
                    2.0f * landscape, 2.0f, 0.0f, 0.6f);
            drawer.drawSpriteRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - offset) * 5.0f), 3.8f, -26.0f,
                    1.3f * landscape, 1.3f, 0.0f, 0.7f);
            drawer.drawSpriteRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - offset) * 5.0f), 3.8f, -26.0f,
                    1.2f * landscape, 1.2f, 0.0f, 0.9f);
            drawer.drawSpriteRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - offset) * 5.0f), -0.2f, -26.0f,
                    1.6f * landscape, 1.6f, 0.0f, 0.3f);
            drawer.drawSpriteRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - offset) * 5.0f), 4.7f, -27.0f,
                    1.1f * landscape, 1.1f, 0.0f, 0.8f);
            return thunderOn;
        }

        if (condition == WeatherCondition.D2_CLOUDY || condition == WeatherCondition.D3_DREARY
                || condition == WeatherCondition.D4_FOG || condition == WeatherCondition.D5_RAIN_SHOWERS
                || condition == WeatherCondition.D6_THUNDERSTORMS || condition == WeatherCondition.D7_FLURRIES_SNOW
                || condition == WeatherCondition.D9_SLEET) {
            boolean darkCloud = condition == WeatherCondition.D3_DREARY
                    || condition == WeatherCondition.D5_RAIN_SHOWERS
                    || condition == WeatherCondition.D6_THUNDERSTORMS
                    || condition == WeatherCondition.D7_FLURRIES_SNOW
                    || condition == WeatherCondition.D9_SLEET;

            if (frameCnt < 360) {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) - 14.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) + 26.0d);
            }
            if (frameCnt < 1100) {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 7.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 47.0d);
            }
            if (frameCnt < 400) {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) - 10.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 30.0d);
            }
            if (frameCnt < 600) {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) - 3.5d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 36.5d);
            }
            if (frameCnt < 850) {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 2.5d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 42.5d);
            }
            if (frameCnt < 650) {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) - 4.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) + 36.0d);
            }
            if (frameCnt < 800) {
                mCloudBX4 = (float) (((-0.025d) * frameCnt) - 5.0d);
            } else {
                mCloudBX4 = (float) (((-0.025d) * frameCnt) + 35.0d);
            }
            if (frameCnt < 300) {
                mCloudBX5 = (float) (((-0.025d) * frameCnt) - 15.0d);
            } else {
                mCloudBX5 = (float) (((-0.025d) * frameCnt) + 25.0d);
            }
            if (frameCnt < 110) {
                mCloudBX6 = (float) (((-0.025d) * frameCnt) - 20.0d);
            } else {
                mCloudBX6 = (float) (((-0.025d) * frameCnt) + 20.0d);
            }
            if (frameCnt < 1000) {
                mCloudBX7 = (float) (((-0.025d) * frameCnt) + 5.0d);
            } else {
                mCloudBX7 = (float) (((-0.025d) * frameCnt) + 45.0d);
            }

            float tint = darkCloud ? 0.2f : 0.0f;
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - offset) * 5.0f), 5.5f, -27.0f,
                    2.0f * landscape, 2.2f, 0.0f, 0.9f - tint, 0.9f - tint, 0.9f - tint, 0.9f);
            float alphaA2 = isNight ? 0.9f : 0.8f;
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - offset) * 5.0f), 4.8f, -27.3f,
                    1.8f * landscape, 1.8f, 0.0f, alphaA2 - tint, alphaA2 - tint, alphaA2 - tint, alphaA2);
            float alphaA3 = isNight ? 1.0f : 0.8f;
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - offset) * 5.0f), 2.0f, -27.5f,
                    1.4f * landscape, 1.4f, 0.0f, alphaA3 - tint, alphaA3 - tint, alphaA3 - tint, alphaA3);
            float alphaB3 = isNight ? 0.9f : 0.7f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - offset) * 5.0f), 3.2f, -27.4f,
                    1.4f * landscape, 1.6f, 0.0f, alphaB3 - tint, alphaB3 - tint, alphaB3 - tint, alphaB3);
            float alphaB1 = isNight ? 1.0f : 0.9f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - offset) * 5.0f), 6.2f, -26.9f,
                    2.7f * landscape, 2.5f, 0.0f, alphaB1 - tint, alphaB1 - tint, alphaB1 - tint, alphaB1);
            float alphaB2 = isNight ? 0.9f : 0.9f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - offset) * 5.0f), 7.2f, -27.1f,
                    1.7f * landscape, 1.7f, 0.0f, alphaB2 - tint, alphaB2 - tint, alphaB2 - tint, alphaB2);
            float alphaB4 = isNight ? 0.9f : 0.4f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX4 + ((1.5f - offset) * 5.0f), 0.2f, -27.6f,
                    1.6f * landscape, 1.6f, 0.0f, alphaB4 - tint, alphaB4 - tint, alphaB4 - tint, alphaB4);
            float alphaB5 = isNight ? 0.8f : 0.4f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX5 + ((1.5f - offset) * 5.0f), 0.3f, -27.7f,
                    1.9f * landscape, 1.9f, 0.0f, alphaB5 - tint, alphaB5 - tint, alphaB5 - tint, alphaB5);
            float alphaB6 = isNight ? 0.9f : 0.7f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX6 + ((1.5f - offset) * 5.0f), -0.2f, -27.8f,
                    2.2f * landscape, 2.2f, 0.0f, alphaB6 - tint, alphaB6 - tint, alphaB6 - tint, alphaB6);
            float alphaB7 = isNight ? 0.8f : 0.6f;
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX7 + ((1.5f - offset) * 5.0f), 0.4f, -27.9f,
                    1.8f * landscape, 1.8f, 0.0f, alphaB7 - tint, alphaB7 - tint, alphaB7 - tint, alphaB7);

            if (condition == WeatherCondition.D6_THUNDERSTORMS) {
                thunderOn = drawCloudLights(
                        drawer,
                        frameCnt,
                        offset,
                        landscape,
                        cloudLightA1,
                        cloudLightA2,
                        cloudLightA3,
                        cloudLightB1,
                        cloudLightB2,
                        cloudLightB3,
                        thunderOn
                );
            }
        }

        return thunderOn;
    }

    private boolean drawWindmillClouds(Drawer drawer,
                                       WeatherCondition condition,
                                       boolean isNight,
                                       int frameCnt,
                                       float offset,
                                       float landscape,
                                       int cloudA01,
                                       int cloudA02,
                                       int cloudA03,
                                       int cloudB01,
                                       int cloudB02,
                                       int cloudB03,
                                       int cloudLightA1,
                                       int cloudLightA2,
                                       int cloudLightA3,
                                       int cloudLightB1,
                                       int cloudLightB2,
                                       int cloudLightB3,
                                       boolean thunderOn) {
        if ((condition == WeatherCondition.D1_CLEAR || condition == WeatherCondition.D8_ICE_COLD) && !isNight) {
            if (frameCnt < 100) {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) - 18.0d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 32.0d);
            }
            if (frameCnt < 530) {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) - 11.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) + 39.0d);
            }
            if (frameCnt < 400) {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) - 9.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) + 41.0d);
            }
            if (frameCnt < 850) {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 2.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 52.0d);
            }
            if (frameCnt < 1000) {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 5.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 55.0d);
            }
            if (frameCnt < 1080) {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 8.0d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 58.0d);
            }
            if (frameCnt < 1450) {
                mCloudAX4 = (float) (((-0.025d) * frameCnt) + 13.0d);
            } else {
                mCloudAX4 = (float) (((-0.025d) * frameCnt) + 63.0d);
            }

            float clearAlphaScale = isNight ? 1.0f : 0.7f;
            float clearTint = 0.05f;
            float alphaA3 = 0.4f * clearAlphaScale;
            float colorA3 = Math.max(0.0f, alphaA3 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA01, mCloudAX3 + ((1.5f - offset) * 5.0f), 4.5f, -27.0f,
                    3.0f * landscape, 3.0f, 0.0f, colorA3, colorA3, colorA3, alphaA3);
            float alphaA1 = 0.9f * clearAlphaScale;
            float colorA1 = Math.max(0.0f, alphaA1 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA01, mCloudAX1 + ((1.5f - offset) * 5.0f), -3.0f, -27.0f,
                    4.3f * landscape, 4.3f, 0.0f, colorA1, colorA1, colorA1, alphaA1);
            float alphaB3 = 0.3f * clearAlphaScale;
            float colorB3 = Math.max(0.0f, alphaB3 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB01, mCloudBX3 + ((1.5f - offset) * 5.0f), 3.8f, -26.0f,
                    2.7f * landscape, 2.7f, 0.0f, colorB3, colorB3, colorB3, alphaB3);
            float alphaB1 = 0.75f * clearAlphaScale;
            float colorB1 = Math.max(0.0f, alphaB1 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB01, mCloudBX1 + ((1.5f - offset) * 5.0f), -3.2f, -26.0f,
                    3.0f * landscape, 3.0f, 0.0f, colorB1, colorB1, colorB1, alphaB1);
            float alphaA4 = 0.95f * clearAlphaScale;
            float colorA4 = Math.max(0.0f, alphaA4 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA01, mCloudAX4 + ((1.5f - offset) * 5.0f), 4.0f, -27.0f,
                    4.3f * landscape, 4.3f, 0.0f, colorA4, colorA4, colorA4, alphaA4);
            float alphaA2 = 0.8f * clearAlphaScale;
            float colorA2 = Math.max(0.0f, alphaA2 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA01, mCloudAX2 + ((1.5f - offset) * 5.0f), 4.0f, -27.0f,
                    2.4f * landscape, 2.4f, 0.0f, colorA2, colorA2, colorA2, alphaA2);
            float alphaB2 = 0.9f * clearAlphaScale;
            float colorB2 = Math.max(0.0f, alphaB2 - clearTint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB01, mCloudBX2 + ((1.5f - offset) * 5.0f), 3.8f, -26.0f,
                    2.5f * landscape, 2.5f, 0.0f, colorB2, colorB2, colorB2, alphaB2);
            return thunderOn;
        }

        if (condition == WeatherCondition.D2_CLOUDY || condition == WeatherCondition.D3_DREARY
                || condition == WeatherCondition.D4_FOG || condition == WeatherCondition.D5_RAIN_SHOWERS
                || condition == WeatherCondition.D6_THUNDERSTORMS || condition == WeatherCondition.D7_FLURRIES_SNOW
                || condition == WeatherCondition.D8_ICE_COLD || condition == WeatherCondition.D9_SLEET) {
            if (frameCnt < 360) {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) - 14.0d);
            } else {
                mCloudAX1 = (float) (((-0.025d) * frameCnt) + 26.0d);
            }
            if (frameCnt < 1100) {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 7.0d);
            } else {
                mCloudBX1 = (float) (((-0.025d) * frameCnt) + 47.0d);
            }
            if (frameCnt < 400) {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) - 10.0d);
            } else {
                mCloudBX2 = (float) (((-0.025d) * frameCnt) + 30.0d);
            }
            if (frameCnt < 600) {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) - 3.5d);
            } else {
                mCloudAX2 = (float) (((-0.025d) * frameCnt) + 36.5d);
            }
            if (frameCnt < 850) {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 2.5d);
            } else {
                mCloudAX3 = (float) (((-0.025d) * frameCnt) + 42.5d);
            }
            if (frameCnt < 650) {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) - 4.0d);
            } else {
                mCloudBX3 = (float) (((-0.025d) * frameCnt) + 36.0d);
            }
            if (frameCnt < 800) {
                mCloudBX4 = (float) (((-0.025d) * frameCnt) - 5.0d);
            } else {
                mCloudBX4 = (float) (((-0.025d) * frameCnt) + 35.0d);
            }
            if (frameCnt < 300) {
                mCloudBX5 = (float) (((-0.025d) * frameCnt) - 15.0d);
            } else {
                mCloudBX5 = (float) (((-0.025d) * frameCnt) + 25.0d);
            }
            if (frameCnt < 110) {
                mCloudBX6 = (float) (((-0.025d) * frameCnt) - 20.0d);
            } else {
                mCloudBX6 = (float) (((-0.025d) * frameCnt) + 20.0d);
            }
            if (frameCnt < 1000) {
                mCloudBX7 = (float) (((-0.025d) * frameCnt) + 5.0d);
            } else {
                mCloudBX7 = (float) (((-0.025d) * frameCnt) + 45.0d);
            }

            boolean useDarkClouds = condition == WeatherCondition.D3_DREARY || condition == WeatherCondition.D4_FOG;
            int cloudA = useDarkClouds ? cloudA03 : cloudA02;
            int cloudB = useDarkClouds ? cloudB03 : cloudB02;
            float dayAlphaScale = isNight ? 1.0f : 0.8f;
            float tint = useDarkClouds ? 0.1f : 0.0f;
            float alphaA1 = (isNight ? 0.25f : 0.9f) * dayAlphaScale;
            float colorA1 = Math.max(0.0f, alphaA1 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX1 + ((1.5f - offset) * 5.0f), 5.5f, -27.0f,
                    4.0f * landscape, 4.4f, 0.0f, colorA1, colorA1, colorA1, alphaA1);
            float alphaA2 = 0.2f * dayAlphaScale;
            float colorA2 = Math.max(0.0f, alphaA2 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX2 + ((1.5f - offset) * 5.0f), 4.8f, -27.3f,
                    3.6f * landscape, 3.6f, 0.0f, colorA2, colorA2, colorA2, alphaA2);
            float alphaA3 = 0.2f * dayAlphaScale;
            float colorA3 = Math.max(0.0f, alphaA3 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudA, mCloudAX3 + ((1.5f - offset) * 5.0f), 2.0f, -27.5f,
                    2.8f * landscape, 2.8f, 0.0f, colorA3, colorA3, colorA3, alphaA3);
            float alphaB3 = (isNight ? 0.25f : 0.5f) * dayAlphaScale;
            float colorB3 = Math.max(0.0f, alphaB3 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX3 + ((1.5f - offset) * 5.0f), 3.2f, -27.4f,
                    2.8f * landscape, 3.2f, 0.0f, colorB3, colorB3, colorB3, alphaB3);
            float alphaB1 = (isNight ? 0.25f : 0.9f) * dayAlphaScale;
            float colorB1 = Math.max(0.0f, alphaB1 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX1 + ((1.5f - offset) * 5.0f), 6.2f, -26.9f,
                    4.4f * landscape, 5.0f, 0.0f, colorB1, colorB1, colorB1, alphaB1);
            float alphaB2 = (isNight ? 0.25f : 0.3f) * dayAlphaScale;
            float colorB2 = Math.max(0.0f, alphaB2 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX2 + ((1.5f - offset) * 5.0f), 7.2f, -27.1f,
                    3.4f * landscape, 3.4f, 0.0f, colorB2, colorB2, colorB2, alphaB2);
            float alphaB4 = (isNight ? 0.2f : 0.4f) * dayAlphaScale;
            float colorB4 = Math.max(0.0f, alphaB4 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX4 + ((1.5f - offset) * 5.0f), 0.2f, -27.6f,
                    3.2f * landscape, 3.2f, 0.0f, colorB4, colorB4, colorB4, alphaB4);
            float alphaB5 = (isNight ? 0.25f : 0.4f) * dayAlphaScale;
            float colorB5 = Math.max(0.0f, alphaB5 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX5 + ((1.5f - offset) * 5.0f), 0.3f, -27.7f,
                    3.8f * landscape, 3.8f, 0.0f, colorB5, colorB5, colorB5, alphaB5);
            float alphaB6 = 0.2f * dayAlphaScale;
            float colorB6 = Math.max(0.0f, alphaB6 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, mCloudBX6 + ((1.5f - offset) * 5.0f), -0.2f, -27.8f,
                    4.4f * landscape, 4.4f, 0.0f, colorB6, colorB6, colorB6, alphaB6);
            float alphaB7 = (isNight ? 0.15f : 0.47f) * dayAlphaScale;
            float colorB7 = Math.max(0.0f, alphaB7 - tint);
            drawer.drawSpriteColoredRectOneToTwo(cloudB, 0.0f, 0.0f, -27.6f,
                    8.0f * landscape, 8.0f, 0.0f, colorB7, colorB7, colorB7, alphaB7);

            if (condition == WeatherCondition.D6_THUNDERSTORMS) {
                thunderOn = drawCloudLights(
                        drawer,
                        frameCnt,
                        offset,
                        landscape,
                        cloudLightA1,
                        cloudLightA2,
                        cloudLightA3,
                        cloudLightB1,
                        cloudLightB2,
                        cloudLightB3,
                        thunderOn
                );
            }
        }

        return thunderOn;
    }

    private int selectCloudATexture(WeatherCondition condition, int cloudA01, int cloudA02, int cloudA03) {
        switch (condition) {
            case D1_CLEAR:
                return cloudA01;
            case D3_DREARY:
                return cloudA03;
            default:
                return cloudA02;
        }
    }

    private int selectCloudBTexture(WeatherCondition condition, int cloudB01, int cloudB02, int cloudB03) {
        switch (condition) {
            case D1_CLEAR:
                return cloudB01;
            case D3_DREARY:
                return cloudB03;
            default:
                return cloudB02;
        }
    }

    private boolean drawCloudLights(Drawer drawer,
                                    int frameCnt,
                                    float offset,
                                    float landscape,
                                    int cloudLightA1,
                                    int cloudLightA2,
                                    int cloudLightA3,
                                    int cloudLightB1,
                                    int cloudLightB2,
                                    int cloudLightB3,
                                    boolean thunderOn) {
        if (mCloudLightStart == null || mCloudLightNum == null || mCloudLightPos == null || mCloudLightDuration == null) {
            return thunderOn;
        }

        if (thunderOn || frameCnt % 400 == 0) {
            for (int i = 0; i < 20; i++) {
                mCloudLightStart[i] = (int) (Math.random() * 390.0d);
                mCloudLightDuration[i] = (int) (Math.random() * 20.0d);
                mCloudLightNum[i] = (int) (Math.random() * 9.0d);
                mCloudLightPos[i] = (int) (Math.random() * 3.0d);
            }
            if (thunderOn) {
                mCloudLightStart[0] = 5;
                mCloudLightDuration[0] = 10;
                mCloudLightNum[0] = 5;
                mCloudLightPos[0] = 2;
                mCloudLightStart[1] = 15;
                mCloudLightDuration[1] = 15;
                mCloudLightNum[1] = 6;
                mCloudLightPos[1] = 1;
                mCloudLightStart[2] = 20;
                mCloudLightDuration[2] = 20;
                mCloudLightNum[2] = 7;
                mCloudLightPos[2] = 3;
            }
            return false;
        }

        for (int i = 0; i < 20; i++) {
            if (frameCnt % 400 > mCloudLightStart[i]
                    && frameCnt % 400 < mCloudLightStart[i] + mCloudLightDuration[i]) {
                float lightX = 0.0f;
                float lightY = 0.0f;
                float scale = 0.0f;
                switch (mCloudLightNum[i]) {
                    case 0:
                        lightX = mCloudAX1;
                        lightY = 5.5f;
                        scale = 2.2f;
                        break;
                    case 1:
                        lightX = mCloudAX2;
                        lightY = 6.0f;
                        scale = 1.5f;
                        break;
                    case 2:
                        lightX = mCloudAX3;
                        lightY = 3.5f;
                        scale = 1.2f;
                        break;
                    case 3:
                        lightX = mCloudBX1;
                        lightY = 6.5f;
                        scale = 2.0f;
                        break;
                    case 4:
                        lightX = mCloudBX2;
                        lightY = 8.0f;
                        scale = 1.2f;
                        break;
                    case 5:
                        lightX = mCloudBX3;
                        lightY = 5.8f;
                        scale = 1.0f;
                        break;
                    case 6:
                        lightX = mCloudBX4;
                        lightY = 1.8f;
                        scale = 1.6f;
                        break;
                    case 7:
                        lightX = mCloudBX5;
                        lightY = 0.8f;
                        scale = 2.2f;
                        break;
                    case 8:
                        lightX = mCloudBX6;
                        lightY = 0.5f;
                        scale = 1.2f;
                        break;
                    default:
                        break;
                }
                float alpha = (frameCnt % 400 < mCloudLightStart[i] + (mCloudLightDuration[i] * 0.5f))
                        ? 0.6f + (((float) Math.random()) * 0.4f)
                        : 0.0f + (((float) Math.random()) * 0.4f);
                if (mCloudLightPos[i] < 0 || mCloudLightPos[i] > 2) {
                    continue;
                }
                int tex;
                if (mCloudLightNum[i] < 3) {
                    if (mCloudLightPos[i] == 0) {
                        tex = cloudLightA1;
                    } else if (mCloudLightPos[i] == 1) {
                        tex = cloudLightA2;
                    } else {
                        tex = cloudLightA3;
                    }
                } else {
                    if (mCloudLightPos[i] == 0) {
                        tex = cloudLightB1;
                    } else if (mCloudLightPos[i] == 1) {
                        tex = cloudLightB2;
                    } else {
                        tex = cloudLightB3;
                    }
                }
                drawer.drawSpriteRectOneToTwo(tex, ((1.5f - offset) * 5.0f) + lightX, lightY, -26.0f,
                        landscape * scale, scale, 0.0f, alpha);
            }
        }

        return thunderOn;
    }
}