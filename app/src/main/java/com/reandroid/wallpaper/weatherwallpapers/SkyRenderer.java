package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class SkyRenderer {
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

        void drawSpriteRectOneToTwo(int texture, float x, float y, float z,
                                    float scaleX, float scaleY, float rotation, float alpha);

        void drawSpriteRectOneToFour(int texture, float x, float y, float z,
                                     float scaleX, float scaleY, float rotation, float alpha);
    }

    private static final float[] STAR_X = {1.0f, -1.7f, 1.2f, -1.5f, -4.5f, -6.1f, -7.5f};
    private static final float[] STAR_Y = {5.4f, 4.5f, 3.2f, 3.0f, 4.7f, 5.2f, 4.8f};
    private static final float[] STAR_SIZE = {0.1f, 0.1f, 0.08f, 0.1f, 0.08f, 0.08f, 0.1f};

    private boolean[] mStarDraw;
    private float[] mStarAlpha;
    private int[] mStarStart;
    private int[] mStarDuration;

    private float mMeteorX = 0.0f;
    private float mMeteorY = 0.0f;
    private float mMeteorScale = 0.0f;
    private float mMeteorAlpha = 0.0f;
    private int mMeteorInitCnt = 0;
    private int mSunInitCnt = 0;

    public void initMemory() {
        mStarDraw = new boolean[7];
        mStarAlpha = new float[7];
        mStarStart = new int[7];
        mStarDuration = new int[7];
    }

    public boolean drawSkyAndCelestial(Drawer drawer,
                                WeatherCondition condition,
                                boolean isNight,
                                int frameCnt,
                                float offset,
                                float landscape,
                                float fillScaleY,
                                int skyA,
                                int skyB,
                                int skyC,
                                int skyD,
                                int skyG,
                                int skyStars,
                                int sun1,
                                int sun2,
                                int sun3,
                                int moon,
                                int star,
                                int meteor,
                                boolean clearOn) {
                    return drawSkyAndCelestial(
                        drawer,
                        condition,
                        isNight,
                        frameCnt,
                        offset,
                        landscape,
                        fillScaleY,
                        skyA,
                        skyB,
                        skyC,
                        skyD,
                        skyG,
                        skyStars,
                        sun1,
                        sun2,
                        sun3,
                        moon,
                        star,
                        meteor,
                        clearOn,
                        Config.OCEAN
                    );
                    }

                    public boolean drawSkyAndCelestial(Drawer drawer,
                                       WeatherCondition condition,
                                       boolean isNight,
                                       int frameCnt,
                                       float offset,
                                       float landscape,
                                       float fillScaleY,
                                       int skyA,
                                       int skyB,
                                       int skyC,
                                       int skyD,
                                       int skyG,
                                       int skyStars,
                                       int sun1,
                                       int sun2,
                                       int sun3,
                                       int moon,
                                       int star,
                                       int meteor,
                                       boolean clearOn,
                                       Config config) {
                    if (config != null && config.mWindmill) {
                        return drawWindmillSkyAndCelestial(
                            drawer,
                            condition,
                            isNight,
                            frameCnt,
                            offset,
                            landscape,
                            fillScaleY,
                            skyA,
                            skyB,
                            skyC,
                            skyD,
                            skyStars,
                            sun1,
                            sun2,
                            sun3,
                            moon,
                            star,
                            meteor,
                            clearOn
                        );
                    }

                    return drawOceanSkyAndCelestial(
                        drawer,
                        condition,
                        isNight,
                        frameCnt,
                        offset,
                        landscape,
                        fillScaleY,
                        skyA,
                        skyB,
                        skyC,
                        skyD,
                        skyG,
                        skyStars,
                        sun1,
                        sun2,
                        sun3,
                        moon,
                        star,
                        meteor,
                        clearOn
                    );
                    }

                    private boolean drawOceanSkyAndCelestial(Drawer drawer,
                                         WeatherCondition condition,
                                         boolean isNight,
                                         int frameCnt,
                                         float offset,
                                         float landscape,
                                         float fillScaleY,
                                         int skyA,
                                         int skyB,
                                         int skyC,
                                         int skyD,
                                         int skyG,
                                         int skyStars,
                                         int sun1,
                                         int sun2,
                                         int sun3,
                                         int moon,
                                         int star,
                                         int meteor,
                                         boolean clearOn) {
        drawer.drawSpriteRectOneToTwo(
                selectSkyTexture(condition, isNight, skyA, skyB, skyC, skyD, skyG),
                (-2.0f) + ((1.5f - offset) * 5.0f),
                4.7f,
                -30.0f,
                3.8f * landscape,
                3.5f * fillScaleY,
                0.0f,
                1.0f
        );

        if (condition == WeatherCondition.D1_CLEAR && isNight) {
            drawer.drawSpriteRectOneToFour(skyStars, 2.2f + ((1.5f - offset) * 5.0f), 7.0f, -29.9f,
                    2.3f * landscape, 2.3f, 0.0f, 1.0f);
        }

        if (condition == WeatherCondition.D1_CLEAR
                || condition == WeatherCondition.D2_CLOUDY
                || condition == WeatherCondition.D8_ICE_COLD) {
            if (!isNight) {
                if (condition == WeatherCondition.D1_CLEAR) {
                    float sunX = (((1.5f - offset) * 5.0f) * 0.2f) - 3.0f;
                    drawer.drawSprite(sun1, sunX, 5.5f, -28.0f, 1.0f, 1.0f, frameCnt * 0.45f, 1.0f);
                    drawer.drawSprite(sun2, sunX, 5.5f, -28.0f, 1.0f, 1.0f, frameCnt * 0.225f, 1.0f);
                    drawer.drawSprite(sun3, sunX, 5.5f, -28.0f, 1.0f, 1.0f, frameCnt * -0.45f, 1.0f);
                }
            } else {
                if (condition != WeatherCondition.D2_CLOUDY) {
                    drawer.drawSprite(moon, 3.2f + ((1.5f - offset) * 5.0f), 7.0f, -28.5f,
                            0.25f * landscape, 0.25f, 0.0f, 1.0f);
                    updateStars(drawer, frameCnt, offset, landscape, star, clearOn);
                    clearOn = updateMeteor(drawer, frameCnt, offset, landscape, meteor, clearOn);
                }
            }
        }

        return clearOn;
    }

    private boolean drawWindmillSkyAndCelestial(Drawer drawer,
                                                WeatherCondition condition,
                                                boolean isNight,
                                                int frameCnt,
                                                float offset,
                                                float landscape,
                                                float fillScaleY,
                                                int skyA,
                                                int skyB,
                                                int skyC,
                                                int skyD,
                                                int skyStars,
                                                int sun1,
                                                int sun2,
                                                int sun3,
                                                int moon,
                                                int star,
                                                int meteor,
                                                boolean clearOn) {
        drawer.drawSprite(
                selectWindmillSkyTexture(condition, isNight, skyA, skyB, skyC, skyD),
                (-1.5f) + ((1.5f - offset) * 5.0f),
                -2.3f,
                -30.0f,
                2.0f * landscape,
                2.0f * fillScaleY,
                0.0f,
                1.0f
        );

        if (condition == WeatherCondition.D1_CLEAR && isNight) {
            drawer.drawSprite(skyStars, 1.3f + ((1.5f - offset) * 5.0f), 7.0f, -29.9f,
                    1.8f * landscape, 0.45f, 0.0f, 1.0f);
        }

        if (condition == WeatherCondition.D1_CLEAR || condition == WeatherCondition.D8_ICE_COLD) {
            if (!isNight) {
                if (condition == WeatherCondition.D1_CLEAR) {
                    float sunX = ((1.5f - offset) * 5.0f * 0.2f) + 3.0f;
                    drawer.drawSprite(sun1, sunX, 6.0f, -28.0f, 1.0f, 1.0f, frameCnt * 0.54f, 1.0f);
                    drawer.drawSprite(sun2, sunX, 6.0f, -28.0f, 1.0f, 1.0f, frameCnt * 0.36f, 1.0f);
                    drawer.drawSprite(sun3, sunX, 6.0f, -28.0f, 1.0f, 1.0f, frameCnt * -0.54f, 1.0f);
                }
            } else {
                drawer.drawSprite(moon, 3.2f + ((1.5f - offset) * 5.0f), 7.0f, -28.5f,
                        0.25f * landscape, 0.25f, 0.0f, 1.0f);
                updateStars(drawer, frameCnt, offset, landscape, star, clearOn);
                clearOn = updateMeteor(drawer, frameCnt, offset, landscape, meteor, clearOn);
            }
        }

        return clearOn;
    }

    public boolean drawSunlight(Drawer drawer,
                         WeatherCondition condition,
                         boolean isNight,
                         int frameCnt,
                         float offset,
                         float landscape,
                         int sun4,
                         boolean clearOn) {
        return drawSunlight(drawer, condition, isNight, frameCnt, offset, landscape, sun4, clearOn, Config.OCEAN);
    }

    public boolean drawSunlight(Drawer drawer,
                                WeatherCondition condition,
                                boolean isNight,
                                int frameCnt,
                                float offset,
                                float landscape,
                                int sun4,
                                boolean clearOn,
                                Config config) {
        if (config != null && config.mWindmill) {
            return drawWindmillSunlight(drawer, condition, isNight, frameCnt, offset, landscape, sun4, clearOn);
        }

        return drawOceanSunlight(drawer, condition, isNight, frameCnt, offset, landscape, sun4, clearOn);
    }

    private boolean drawOceanSunlight(Drawer drawer,
                                      WeatherCondition condition,
                                      boolean isNight,
                                      int frameCnt,
                                      float offset,
                                      float landscape,
                                      int sun4,
                                      boolean clearOn) {
        if (condition == WeatherCondition.D1_CLEAR && !isNight && clearOn) {
            float sunlightCnt = (frameCnt + 50) % 200;
            float coeff = sunlightCnt < 100.0f
                    ? 2.0f - (sunlightCnt / 100.0f)
                    : ((sunlightCnt - 100.0f) / 100.0f) + 1.0f;
            float alpha = (float) Math.sqrt(coeff - 1.0f);
            drawer.drawSprite(sun4, (0.6f - 3.0f) + ((1.5f - offset) * 5.0f * 0.15f), 5.5f - 1.75f, -20.5f,
                    landscape * coeff * 0.6f, 0.6f * coeff, -1.8f * sunlightCnt, alpha);
            mSunInitCnt++;
            if (mSunInitCnt > 400 && sunlightCnt == 100.0f) {
                clearOn = false;
                mSunInitCnt = 0;
            }
        }
        return clearOn;
    }

    private boolean drawWindmillSunlight(Drawer drawer,
                                         WeatherCondition condition,
                                         boolean isNight,
                                         int frameCnt,
                                         float offset,
                                         float landscape,
                                         int sun4,
                                         boolean clearOn) {
        if (condition == WeatherCondition.D1_CLEAR && !isNight && clearOn) {
            float sunlightCnt = (frameCnt + 125) % 200;
            float coeff = sunlightCnt < 100.0f
                    ? 2.0f - (sunlightCnt / 100.0f)
                    : ((sunlightCnt - 100.0f) / 100.0f) + 1.0f;
            float alpha = (float) Math.sqrt(coeff - 1.0f);
            drawer.drawSprite(sun4, (3.0f - 0.4f) + ((1.5f - offset) * 5.0f * 0.15f), 6.0f - 1.0f, -23.5f,
                    landscape * coeff * 0.8f, 0.8f * coeff, -1.8f * sunlightCnt, alpha);
            mSunInitCnt++;
            if (mSunInitCnt > 800 && sunlightCnt == 100.0f) {
                clearOn = false;
                mSunInitCnt = 0;
            }
        }
        return clearOn;
    }

    private int selectSkyTexture(WeatherCondition condition,
                                 boolean isNight,
                                 int skyA,
                                 int skyB,
                                 int skyC,
                                 int skyD,
                                 int skyG) {
        if (isNight) {
            return skyD;
        }
        switch (condition) {
            case D1_CLEAR:
                return skyA;
            case D2_CLOUDY:
            case D4_FOG:
            case D8_ICE_COLD:
                return skyB;
            case D5_RAIN_SHOWERS:
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return skyC;
            case D3_DREARY:
            case D6_THUNDERSTORMS:
            default:
                return skyG;
        }
    }

    private int selectWindmillSkyTexture(WeatherCondition condition,
                                         boolean isNight,
                                         int skyA,
                                         int skyB,
                                         int skyC,
                                         int skyD) {
        if (isNight) {
            return skyB;
        }
        switch (condition) {
            case D1_CLEAR:
            case D8_ICE_COLD:
                return skyA;
            case D2_CLOUDY:
            case D4_FOG:
            case D7_FLURRIES_SNOW:
            case D9_SLEET:
                return skyC;
            case D3_DREARY:
            case D5_RAIN_SHOWERS:
            case D6_THUNDERSTORMS:
            default:
                return skyD;
        }
    }

    private void updateStars(Drawer drawer,
                             int frameCnt,
                             float offset,
                             float landscape,
                             int star,
                             boolean clearOn) {
        if (mStarDraw == null || mStarAlpha == null || mStarStart == null || mStarDuration == null) {
            return;
        }

        if (frameCnt % 200 == 0 || clearOn) {
            if (clearOn) {
                for (int i = 0; i < 7; i++) {
                    mStarDraw[i] = true;
                    mStarStart[i] = frameCnt % 200;
                    mStarAlpha[i] = 0.0f;
                    mStarDuration[i] = (int) ((Math.random() * 20.0d) + 30.0d);
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    mStarDraw[i] = Math.random() > 0.2d;
                    mStarStart[i] = (int) (Math.random() * 100.0d);
                    mStarAlpha[i] = 0.0f;
                    mStarDuration[i] = (int) ((Math.random() * 20.0d) + 30.0d);
                }
            }
        } else {
            for (int i = 0; i < 7; i++) {
                if (mStarDraw[i] && frameCnt % 200 > mStarStart[i]) {
                    if (frameCnt % 200 < mStarStart[i] + mStarDuration[i]) {
                        if (mStarAlpha[i] < 1.0f) {
                            mStarAlpha[i] = (float) (mStarAlpha[i] + 0.04d);
                        }
                    } else if (frameCnt % 200 < mStarStart[i] + (mStarDuration[i] * 2)
                            && mStarAlpha[i] > 0.0f) {
                        mStarAlpha[i] = (float) (mStarAlpha[i] - 0.04d);
                    }
                    drawer.drawSprite(star, STAR_X[i] + ((1.5f - offset) * 5.0f), STAR_Y[i], -28.0f,
                            landscape * STAR_SIZE[i], STAR_SIZE[i], 0.0f, mStarAlpha[i]);
                }
            }
        }
    }

    private boolean updateMeteor(Drawer drawer,
                                 int frameCnt,
                                 float offset,
                                 float landscape,
                                 int meteor,
                                 boolean clearOn) {
        if (frameCnt % 200 == 0 || clearOn) {
            if (clearOn) {
                mMeteorX = 9.0f;
                mMeteorY = 10.0f;
                mMeteorScale = 0.4f;
                mMeteorInitCnt = 0;
                mMeteorAlpha = 1.0f;
            } else if (mMeteorInitCnt > 199) {
                mMeteorX = (float) ((Math.random() * 6.0d) + 5.0d);
                mMeteorY = (float) ((Math.random() * 8.0d) + 8.0d);
                mMeteorScale = ((float) (Math.random() * 0.2d)) + 0.2f;
                mMeteorAlpha = 1.0f;
            }
            return false;
        }

        if (mMeteorInitCnt < 200) {
            mMeteorInitCnt++;
        }
        mMeteorX = (float) (mMeteorX - 0.45d);
        mMeteorY = (float) (mMeteorY - 0.3d);
        mMeteorScale *= 0.98f;
        mMeteorAlpha *= 0.9f;
        drawer.drawSprite(meteor, mMeteorX + ((1.5f - offset) * 5.0f), mMeteorY, -28.0f,
                landscape * mMeteorScale, mMeteorScale, 0.0f, 1.0f);
        return clearOn;
    }
}