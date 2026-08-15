package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class PrecipitationRenderer {
    public static final class Config {
        public static final Config OCEAN = new Config(false);
        public static final Config WINDMILL = new Config(true);

        private final boolean mWindmill;

        private Config(boolean windmill) {
            mWindmill = windmill;
        }
    }

    public interface Drawer {
        void drawSprite(int texture, float x, float y, float z,
                        float scaleX, float scaleY, float rotation, float alpha);
    }

    private int mSnowCount1 = 10;
    private int mSnowCount2 = 150;
    private int mSnowCount3 = 400;

    private int[] mRaindrop1Start;
    private float[] mRaindrop1X;
    private float[] mRaindrop1Y;
    private float[] mRaindrop1Scale;
    private int[] mRaindrop2Start;
    private float[] mRaindrop2X;
    private float[] mRaindrop2Y;
    private float[] mRaindrop2Scale;

    private float[] mSnow1X;
    private float[] mSnow1Y;
    private float[] mSnow1Scale;
    private float[] mSnow2X;
    private float[] mSnow2Y;
    private float[] mSnow2Scale;
    private float[] mSnow3X;
    private float[] mSnow3Y;
    private float[] mSnow3Scale;

    public void initMemory() {
        initMemory(Config.OCEAN);
    }

    public void initMemory(Config config) {
        if (config != null && config.mWindmill) {
            mSnowCount1 = 5;
            mSnowCount2 = 100;
            mSnowCount3 = 200;
        } else {
            mSnowCount1 = 10;
            mSnowCount2 = 150;
            mSnowCount3 = 400;
        }

        mRaindrop1Start = new int[8];
        mRaindrop1X = new float[8];
        mRaindrop1Y = new float[8];
        mRaindrop1Scale = new float[8];
        mRaindrop2Start = new int[8];
        mRaindrop2X = new float[8];
        mRaindrop2Y = new float[8];
        mRaindrop2Scale = new float[8];

        mSnow1X = new float[mSnowCount1];
        mSnow1Y = new float[mSnowCount1];
        mSnow1Scale = new float[mSnowCount1];
        mSnow2X = new float[mSnowCount2];
        mSnow2Y = new float[mSnowCount2];
        mSnow2Scale = new float[mSnowCount2];
        mSnow3X = new float[mSnowCount3];
        mSnow3Y = new float[mSnowCount3];
        mSnow3Scale = new float[mSnowCount3];
    }

    public boolean drawRain(Drawer drawer,
                     WeatherCondition condition,
                     int frameCnt,
                     float landscape,
                     float fillScaleY,
                     int rain1,
                     int rain2,
                     int rain3,
                     int rain4,
                     int cloudcover,
                     int waterdrop,
                     int[] raindrop1,
                     int[] raindrop2,
                     boolean rainOn) {
                return drawRain(
                    drawer,
                    condition,
                    frameCnt,
                    landscape,
                    fillScaleY,
                    rain1,
                    rain2,
                    rain3,
                    rain4,
                    cloudcover,
                    waterdrop,
                    raindrop1,
                    raindrop2,
                    rainOn,
                    Config.OCEAN
                );
                }

                public boolean drawRain(Drawer drawer,
                            WeatherCondition condition,
                            int frameCnt,
                            float landscape,
                            float fillScaleY,
                            int rain1,
                            int rain2,
                            int rain3,
                            int rain4,
                            int cloudcover,
                            int waterdrop,
                            int[] raindrop1,
                            int[] raindrop2,
                            boolean rainOn,
                            Config config) {
                if (config != null && config.mWindmill) {
                    return drawWindmillRain(
                        drawer,
                        condition,
                        frameCnt,
                        landscape,
                        fillScaleY,
                        rain1,
                        rain2,
                        rain3,
                        rain4,
                        waterdrop,
                        raindrop1,
                        raindrop2,
                        rainOn
                    );
                }

                return drawOceanRain(
                    drawer,
                    condition,
                    frameCnt,
                    landscape,
                    fillScaleY,
                    rain1,
                    rain2,
                    rain3,
                    rain4,
                    cloudcover,
                    waterdrop,
                    raindrop1,
                    raindrop2,
                    rainOn
                );
                }

                private boolean drawOceanRain(Drawer drawer,
                              WeatherCondition condition,
                              int frameCnt,
                              float landscape,
                              float fillScaleY,
                              int rain1,
                              int rain2,
                              int rain3,
                              int rain4,
                              int cloudcover,
                              int waterdrop,
                              int[] raindrop1,
                              int[] raindrop2,
                              boolean rainOn) {
        if (condition != WeatherCondition.D5_RAIN_SHOWERS
                && condition != WeatherCondition.D6_THUNDERSTORMS
                && condition != WeatherCondition.D9_SLEET) {
            return rainOn;
        }

        int rainTex = rain1;
        switch (frameCnt % 4) {
            case 1:
                rainTex = rain2;
                break;
            case 2:
                rainTex = rain3;
                break;
            case 3:
                rainTex = rain4;
                break;
            default:
                break;
        }
        drawer.drawSprite(rainTex, 0.0f, 0.0f, -20.5f, 1.5f * landscape, 1.5f, 0.0f, 1.0f);
        drawer.drawSprite(cloudcover, 0.0f, 7.2f, -25.0f, 2.0f * landscape, 2.0f, 0.0f, 1.0f);

        if (condition == WeatherCondition.D5_RAIN_SHOWERS || condition == WeatherCondition.D9_SLEET) {
            drawer.drawSprite(waterdrop, -0.15f, -0.3f, -20.0f, 0.6f * landscape, 1.1f * fillScaleY, 0.0f, 1.0f);
            if (rainOn || frameCnt % 400 == 0) {
                for (int i = 1; i < 8; i++) {
                    mRaindrop1Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                    mRaindrop2Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                    mRaindrop1X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                    mRaindrop1Y[i] = (float) (Math.random() * 3.0d);
                    mRaindrop2X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                    mRaindrop2Y[i] = (float) (Math.random() * 3.0d);
                    mRaindrop1Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                    mRaindrop2Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                }
                mRaindrop1Start[2] = frameCnt + 20;
                mRaindrop2Start[2] = frameCnt + 55;
                mRaindrop1X[2] = -1.5f;
                mRaindrop1Y[2] = -1.0f;
                mRaindrop2X[2] = 3.0f;
                mRaindrop2Y[2] = -0.5f;
                mRaindrop1Scale[2] = 1.0f;
                mRaindrop2Scale[2] = 1.0f;
                rainOn = false;
            }
            // 图集播放速度对齐原版：窗口 25 帧、每渲染帧推进一帧。
            // 之前窗口 100 帧 + 索引 /4，水滴下滑被人为放慢 4 倍，观感卡顿。
            for (int i = 0; i < 8; i++) {
                if (frameCnt % 400 > mRaindrop1Start[i] && frameCnt % 400 < mRaindrop1Start[i] + 25) {
                    int idx = ((frameCnt % 400) - mRaindrop1Start[i]);
                    drawer.drawSprite(raindrop1[idx], mRaindrop1X[i], mRaindrop1Y[i], -19.5f,
                            mRaindrop1Scale[i] * 0.11f * landscape, mRaindrop1Scale[i] * 0.45f, 0.0f, 1.0f);
                }
                if (frameCnt % 400 > mRaindrop2Start[i] && frameCnt % 400 < mRaindrop2Start[i] + 25) {
                    int idx = ((frameCnt % 400) - mRaindrop2Start[i]);
                    drawer.drawSprite(raindrop2[idx], mRaindrop2X[i], mRaindrop2Y[i], -19.5f,
                            mRaindrop2Scale[i] * 0.11f * landscape, mRaindrop2Scale[i] * 0.9f, 0.0f, 1.0f);
                }
            }
        }

        return rainOn;
    }

    private boolean drawWindmillRain(Drawer drawer,
                                     WeatherCondition condition,
                                     int frameCnt,
                                     float landscape,
                                     float fillScaleY,
                                     int rain1,
                                     int rain2,
                                     int rain3,
                                     int rain4,
                                     int waterdrop,
                                     int[] raindrop1,
                                     int[] raindrop2,
                                     boolean rainOn) {
        if (condition != WeatherCondition.D5_RAIN_SHOWERS
                && condition != WeatherCondition.D6_THUNDERSTORMS
                && condition != WeatherCondition.D9_SLEET) {
            return rainOn;
        }

        int rainTex = (frameCnt % 4 == 0) ? rain1
                : (frameCnt % 4 == 1) ? rain2
                : (frameCnt % 4 == 2) ? rain3 : rain4;
        drawer.drawSprite(rainTex, 0.0f, 0.0f, -20.5f, 0.75f * landscape, 1.5f, 0.0f, 1.0f);

        if (condition == WeatherCondition.D5_RAIN_SHOWERS || condition == WeatherCondition.D9_SLEET) {
            drawer.drawSprite(waterdrop, -0.15f, -0.3f, -20.0f, 0.6f * landscape, 1.1f * fillScaleY, 0.0f, 1.0f);
            if (rainOn || frameCnt % 400 == 0) {
                for (int i = 1; i < 8; i++) {
                    mRaindrop1Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                    mRaindrop2Start[i] = (int) ((Math.random() * 300.0d) + 50.0d);
                    mRaindrop1X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                    mRaindrop1Y[i] = (float) (Math.random() * 3.0d);
                    mRaindrop2X[i] = (float) ((Math.random() * 8.0d) - 4.0d);
                    mRaindrop2Y[i] = (float) (Math.random() * 3.0d);
                    mRaindrop1Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                    mRaindrop2Scale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                }
                mRaindrop1Start[2] = frameCnt + 20;
                mRaindrop2Start[2] = frameCnt + 55;
                mRaindrop1X[2] = -1.5f;
                mRaindrop1Y[2] = -1.0f;
                mRaindrop2X[2] = 3.0f;
                mRaindrop2Y[2] = -0.5f;
                mRaindrop1Scale[2] = 1.0f;
                mRaindrop2Scale[2] = 1.0f;
                rainOn = false;
            }
            for (int i = 0; i < 8; i++) {
                if (frameCnt % 400 > mRaindrop1Start[i] && frameCnt % 400 < mRaindrop1Start[i] + 100) {
                    int idx = ((frameCnt % 400) - mRaindrop1Start[i]) / 4;
                    if (idx >= 0 && idx < raindrop1.length) {
                        drawer.drawSprite(raindrop1[idx], mRaindrop1X[i], mRaindrop1Y[i], -19.5f,
                                mRaindrop1Scale[i] * 0.11f * landscape, mRaindrop1Scale[i] * 0.45f, 0.0f, 1.0f);
                    }
                }
                if (frameCnt % 400 > mRaindrop2Start[i] && frameCnt % 400 < mRaindrop2Start[i] + 100) {
                    int idx = ((frameCnt % 400) - mRaindrop2Start[i]) / 4;
                    if (idx >= 0 && idx < raindrop2.length) {
                        drawer.drawSprite(raindrop2[idx], mRaindrop2X[i], mRaindrop2Y[i], -19.5f,
                                mRaindrop2Scale[i] * 0.11f * landscape, mRaindrop2Scale[i] * 0.9f, 0.0f, 1.0f);
                    }
                }
            }
        }

        return rainOn;
    }

    public boolean drawSnow(Drawer drawer,
                     WeatherCondition condition,
                     int frameCnt,
                     float offset,
                     float landscape,
                     int snow1,
                     int snow2,
                     int snow3,
                     int snow4,
                     boolean snowOn) {
        return drawSnow(
                drawer,
                condition,
                frameCnt,
                offset,
                landscape,
                snow1,
                snow2,
                snow3,
                snow4,
                snowOn,
                Config.OCEAN
        );
    }

    public boolean drawSnow(Drawer drawer,
                            WeatherCondition condition,
                            int frameCnt,
                            float offset,
                            float landscape,
                            int snow1,
                            int snow2,
                            int snow3,
                            int snow4,
                            boolean snowOn,
                            Config config) {
        if (config != null && config.mWindmill) {
            return drawWindmillSnow(drawer, condition, frameCnt, offset, landscape, snow1, snow2, snow3, snow4, snowOn);
        }

        return drawOceanSnow(drawer, condition, frameCnt, offset, landscape, snow1, snow2, snow3, snow4, snowOn);
    }

    private boolean drawOceanSnow(Drawer drawer,
                                  WeatherCondition condition,
                                  int frameCnt,
                                  float offset,
                                  float landscape,
                                  int snow1,
                                  int snow2,
                                  int snow3,
                                  int snow4,
                                  boolean snowOn) {
        if (condition != WeatherCondition.D7_FLURRIES_SNOW
                && condition != WeatherCondition.D9_SLEET) {
            return snowOn;
        }

        if (snowOn) {
            for (int i = 0; i < mSnowCount1; i++) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale == 0 ? 1.0f : 0.5f;
            }
            for (int i = 0; i < mSnowCount2; i++) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 3;
                if (scale == 0) {
                    mSnow2Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow2Scale[i] = 0.7f;
                } else {
                    mSnow2Scale[i] = 0.5f;
                }
            }
            for (int i = 0; i < mSnowCount3; i++) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 4;
                if (scale == 0) {
                    mSnow3Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow3Scale[i] = 0.5f;
                } else if (scale == 2) {
                    mSnow3Scale[i] = 0.3f;
                } else {
                    mSnow3Scale[i] = 0.2f;
                }
            }
            return false;
        }

        for (int i = 0; i < mSnowCount1; i++) {
            if (mSnow1Y[i] < -8.0f) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale == 0 ? 1.0f : 0.5f;
            } else {
                mSnow1Y[i] -= 0.04f;
            }
            float z = condition == WeatherCondition.D9_SLEET ? -22.0f : -20.0f;
            float alpha = condition == WeatherCondition.D9_SLEET ? mSnow1Scale[i] / 2.0f : mSnow1Scale[i];
            drawer.drawSprite(snow1, (mSnow1X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow1Y[i], z,
                    mSnow1Scale[i] * 0.1f * landscape, mSnow1Scale[i] * 0.1f, frameCnt * 0.225f, alpha);
        }

        for (int i = 0; i < mSnowCount2; i++) {
            if (mSnow2Y[i] < -8.0f) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 3;
                if (scale == 0) {
                    mSnow2Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow2Scale[i] = 0.7f;
                } else {
                    mSnow2Scale[i] = 0.5f;
                }
            } else {
                mSnow2Y[i] -= 0.02f;
            }
            float z = condition == WeatherCondition.D9_SLEET ? -22.5f : -20.0f;
            float alpha = condition == WeatherCondition.D9_SLEET ? mSnow2Scale[i] / 2.0f : mSnow2Scale[i];
            drawer.drawSprite(snow2, (mSnow2X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow2Y[i], z,
                    mSnow2Scale[i] * 0.02f * landscape, mSnow2Scale[i] * 0.02f, 0.0f, alpha);
            if ((i & 273) == 1 && condition != WeatherCondition.D9_SLEET) {
                drawer.drawSprite(snow4, (mSnow2X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow2Y[i] + 1.0f, -21.0f,
                        0.35f, 0.35f, 0.0f, 0.8f);
            }
        }

        for (int i = 0; i < mSnowCount3; i++) {
            if (mSnow3Y[i] < -8.0f) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = 9.0f;
                int scale = ((int) (Math.random() * 100.0d)) % 4;
                if (scale == 0) {
                    mSnow3Scale[i] = 1.0f;
                } else if (scale == 1) {
                    mSnow3Scale[i] = 0.5f;
                } else if (scale == 2) {
                    mSnow3Scale[i] = 0.3f;
                } else {
                    mSnow3Scale[i] = 0.2f;
                }
            } else {
                mSnow3Y[i] -= 0.01f;
            }
            float z = condition == WeatherCondition.D9_SLEET ? -23.0f : -20.0f;
            float alpha = condition == WeatherCondition.D9_SLEET ? mSnow3Scale[i] / 2.0f : mSnow3Scale[i];
            drawer.drawSprite(snow3, (mSnow3X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow3Y[i], z,
                    mSnow3Scale[i] * 0.01f * landscape, mSnow3Scale[i] * 0.01f, 0.0f, alpha);
        }

        return snowOn;
    }

    private boolean drawWindmillSnow(Drawer drawer,
                                     WeatherCondition condition,
                                     int frameCnt,
                                     float offset,
                                     float landscape,
                                     int snow1,
                                     int snow2,
                                     int snow3,
                                     int snow4,
                                     boolean snowOn) {
        if (condition != WeatherCondition.D7_FLURRIES_SNOW
                && condition != WeatherCondition.D9_SLEET) {
            return snowOn;
        }

        if (snowOn) {
            for (int i = 0; i < mSnowCount1; i++) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale1 = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale1 == 0 ? 1.0f : 0.5f;
            }
            for (int i = 0; i < mSnowCount2; i++) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale2 = ((int) (Math.random() * 100.0d)) % 3;
                mSnow2Scale[i] = scale2 == 0 ? 1.0f : scale2 == 1 ? 0.7f : 0.5f;
            }
            for (int i = 0; i < mSnowCount3; i++) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = ((float) (Math.random() * 16.0d)) - 8.0f;
                int scale3 = ((int) (Math.random() * 100.0d)) % 4;
                mSnow3Scale[i] = scale3 == 0 ? 1.0f : scale3 == 1 ? 0.5f : scale3 == 2 ? 0.3f : 0.2f;
            }
            return false;
        }

        for (int i = 0; i < mSnowCount1; i++) {
            if (mSnow1Y[i] < -8.0f) {
                mSnow1X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow1Y[i] = 9.0f;
                int scale1 = ((int) (Math.random() * 100.0d)) % 2;
                mSnow1Scale[i] = scale1 == 0 ? 1.0f : 0.5f;
            } else {
                mSnow1Y[i] -= 0.04f;
            }
            drawer.drawSprite(snow1, (mSnow1X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow1Y[i], -20.0f,
                    mSnow1Scale[i] * 0.1f * landscape, mSnow1Scale[i] * 0.1f, frameCnt * 0.225f,
                    condition == WeatherCondition.D9_SLEET ? mSnow1Scale[i] / 2.0f : mSnow1Scale[i]);
        }

        for (int i = 0; i < mSnowCount2; i++) {
            if (mSnow2Y[i] < -8.0f) {
                mSnow2X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow2Y[i] = 9.0f;
                int scale2 = ((int) (Math.random() * 100.0d)) % 3;
                mSnow2Scale[i] = scale2 == 0 ? 1.0f : scale2 == 1 ? 0.7f : 0.5f;
            } else {
                mSnow2Y[i] -= 0.02f;
            }
            drawer.drawSprite(snow2, (mSnow2X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow2Y[i], -20.0f,
                    mSnow2Scale[i] * 0.02f * landscape, mSnow2Scale[i] * 0.02f, 0.0f,
                    condition == WeatherCondition.D9_SLEET ? mSnow2Scale[i] / 2.0f : mSnow2Scale[i]);

            if ((i & 273) == 1) {
                drawer.drawSprite(snow4, (mSnow2X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow2Y[i] + 1.0f, -21.0f,
                        0.35f, 0.35f, 0.0f, 0.8f);
            }
        }

        for (int i = 0; i < mSnowCount3; i++) {
            if (mSnow3Y[i] < -8.0f) {
                mSnow3X[i] = ((float) (Math.random() * 20.0d)) - 10.0f;
                mSnow3Y[i] = 9.0f;
                int scale3 = ((int) (Math.random() * 100.0d)) % 4;
                mSnow3Scale[i] = scale3 == 0 ? 1.0f : scale3 == 1 ? 0.5f : scale3 == 2 ? 0.3f : 0.2f;
            } else {
                mSnow3Y[i] -= 0.01f;
            }
            drawer.drawSprite(snow3, (mSnow3X[i] + ((1.5f - offset) * 5.0f)) - 1.0f, mSnow3Y[i], -20.0f,
                    mSnow3Scale[i] * 0.01f * landscape, mSnow3Scale[i] * 0.01f, 0.0f,
                    condition == WeatherCondition.D9_SLEET ? mSnow3Scale[i] / 2.0f : mSnow3Scale[i]);
        }

        return snowOn;
    }
}