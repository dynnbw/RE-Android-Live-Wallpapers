package com.reandroid.wallpaper.weatherwallpapers;

import com.reandroid.weather.WeatherCondition;

public class ThunderRenderer {
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

    private int[] mThunderStart;
    private int[] mThunderDuration;
    private int[] mThunderNum;
    private float[] mThunderScale;
    private float[] mThunderX;
    private float[] mThunderY;

    public void initMemory() {
        mThunderStart = new int[40];
        mThunderDuration = new int[40];
        mThunderNum = new int[40];
        mThunderScale = new float[40];
        mThunderX = new float[40];
        mThunderY = new float[40];
    }

    public boolean drawThunder(Drawer drawer,
                        WeatherCondition condition,
                        int frameCnt,
                        float offset,
                        float landscape,
                        int skyFlash,
                        int lightning1,
                        int lightning2,
                        int lightning3,
                        boolean thunderOn) {
                return drawThunder(
                    drawer,
                    condition,
                    frameCnt,
                    offset,
                    landscape,
                    skyFlash,
                    lightning1,
                    lightning2,
                    lightning3,
                    thunderOn,
                    Config.OCEAN
                );
                }

                public boolean drawThunder(Drawer drawer,
                               WeatherCondition condition,
                               int frameCnt,
                               float offset,
                               float landscape,
                               int skyFlash,
                               int lightning1,
                               int lightning2,
                               int lightning3,
                               boolean thunderOn,
                               Config config) {
                if (config != null && config.mWindmill) {
                    return drawWindmillThunder(drawer, condition, frameCnt, offset, landscape,
                        skyFlash, lightning1, lightning2, lightning3, thunderOn);
                }

                return drawOceanThunder(drawer, condition, frameCnt, offset, landscape,
                    skyFlash, lightning1, lightning2, lightning3, thunderOn);
                }

                private boolean drawOceanThunder(Drawer drawer,
                                 WeatherCondition condition,
                                 int frameCnt,
                                 float offset,
                                 float landscape,
                                 int skyFlash,
                                 int lightning1,
                                 int lightning2,
                                 int lightning3,
                                 boolean thunderOn) {
        if (condition != WeatherCondition.D6_THUNDERSTORMS) {
            return thunderOn;
        }

        if (thunderOn || frameCnt % 400 == 0) {
            for (int i = 0; i < 40; i++) {
                mThunderStart[i] = (int) (Math.random() * 390.0d);
                mThunderDuration[i] = (int) (Math.random() * 20.0d);
                mThunderNum[i] = (int) (Math.random() * 3.0d);
                mThunderScale[i] = (float) (Math.random() * 0.5d);
                mThunderX[i] = (float) ((Math.random() * 15.0d) - 6.0d);
                mThunderY[i] = (float) ((Math.random() * 5.0d) + 6.0d);
            }
            if (thunderOn) {
                mThunderStart[0] = (frameCnt % 400) + 10;
                mThunderDuration[0] = 5;
                mThunderNum[0] = 1;
                mThunderScale[0] = 1.0f;
                mThunderX[0] = 0.0f;
                mThunderY[0] = 10.0f;
                mThunderStart[1] = (frameCnt % 400) + 8;
                mThunderDuration[1] = 7;
                mThunderNum[1] = 2;
                mThunderScale[1] = 1.0f;
                mThunderX[1] = 5.0f;
                mThunderY[1] = 8.0f;
                mThunderStart[2] = (frameCnt % 400) + 13;
                mThunderDuration[2] = 2;
                mThunderNum[2] = 3;
                mThunderScale[2] = 1.0f;
                mThunderX[2] = 3.0f;
                mThunderY[2] = 9.0f;
                mThunderStart[3] = (frameCnt % 400) + 8;
                mThunderDuration[3] = 5;
                mThunderNum[3] = 2;
                mThunderScale[3] = 1.0f;
                mThunderX[3] = 10.0f;
                mThunderY[3] = 10.0f;
                mThunderStart[4] = (frameCnt % 400) + 16;
                mThunderDuration[4] = 4;
                mThunderNum[4] = 1;
                mThunderScale[4] = 1.0f;
                mThunderX[4] = -2.0f;
                mThunderY[4] = 7.0f;
                mThunderStart[5] = (frameCnt % 400) + 5;
                mThunderDuration[5] = 5;
                mThunderNum[5] = 1;
                mThunderScale[5] = 1.0f;
                mThunderX[5] = -5.0f;
                mThunderY[5] = 8.0f;
            }
            return false;
        }

        for (int i = 0; i < 40; i++) {
            if (frameCnt % 400 > mThunderStart[i] && frameCnt % 400 < mThunderStart[i] + 8) {
                float alpha = (frameCnt % 400 < mThunderStart[i] + 4)
                        ? 0.8f + (((float) Math.random()) * 0.2f)
                        : 0.2f + (((float) Math.random()) * 0.2f);
                drawer.drawSprite(skyFlash, (-0.5f) + ((1.5f - offset) * 5.0f), 0.0f, -19.0f,
                        1.5f, 1.2f, 0.0f, alpha);
            }
            if (frameCnt % 400 > mThunderStart[i]
                    && frameCnt % 400 < mThunderStart[i] + mThunderDuration[i]) {
                float alpha;
                if (frameCnt % 400 < mThunderStart[i] + (mThunderDuration[i] * 0.5f)) {
                    alpha = mThunderScale[i] > 0.75f
                            ? 0.8f + (((float) Math.random()) * 0.2f)
                            : 0.5f + (((float) Math.random()) * 0.2f);
                } else {
                    alpha = mThunderScale[i] > 0.75f
                            ? 0.3f + (((float) Math.random()) * 0.2f)
                            : 0.1f + (((float) Math.random()) * 0.2f);
                }
                int tex = 0;
                switch (mThunderNum[i]) {
                    case 0:
                        tex = lightning1;
                        break;
                    case 1:
                        tex = lightning2;
                        break;
                    case 2:
                        tex = lightning3;
                        break;
                    default:
                        break;
                }
                if (tex != 0) {
                    drawer.drawSprite(tex, mThunderX[i] + ((1.5f - offset) * 5.0f), mThunderY[i], -26.0f,
                            mThunderScale[i] * landscape, mThunderScale[i], 0.0f, alpha);
                }
            }
        }

        return thunderOn;
    }

    private boolean drawWindmillThunder(Drawer drawer,
                                        WeatherCondition condition,
                                        int frameCnt,
                                        float offset,
                                        float landscape,
                                        int skyFlash,
                                        int lightning1,
                                        int lightning2,
                                        int lightning3,
                                        boolean thunderOn) {
        if (condition != WeatherCondition.D6_THUNDERSTORMS) {
            return thunderOn;
        }

        if (thunderOn || frameCnt % 400 == 0) {
            for (int i = 0; i < 40; i++) {
                mThunderStart[i] = (int) (Math.random() * 400.0d);
                mThunderDuration[i] = (int) (Math.random() * 5.0d);
                mThunderNum[i] = ((int) (Math.random() * 100.0d)) % 3;
                mThunderScale[i] = (float) ((Math.random() * 0.5d) + 0.5d);
                if (mThunderScale[i] > 0.75d) {
                    mThunderX[i] = (float) ((Math.random() * 16.0d) - 8.0d);
                    mThunderY[i] = (float) ((Math.random() * 3.0d) + 8.0d);
                } else {
                    mThunderX[i] = (float) ((Math.random() * 16.0d) - 8.0d);
                    mThunderY[i] = (float) ((Math.random() * 3.0d) + 3.0d);
                }
            }
            if (thunderOn) {
                mThunderStart[0] = (frameCnt % 400) + 10;
                mThunderDuration[0] = 3;
                mThunderNum[0] = 2;
                mThunderScale[0] = 1.0f;
                mThunderX[0] = 2.0f;
                mThunderY[0] = 8.0f;
                mThunderStart[1] = (frameCnt % 400) + 15;
                mThunderDuration[1] = 5;
                mThunderNum[1] = 1;
                mThunderScale[1] = 0.7f;
                mThunderX[1] = -3.0f;
                mThunderY[1] = 8.0f;
                mThunderStart[2] = (frameCnt % 400) + 13;
                mThunderDuration[2] = 2;
                mThunderNum[2] = 3;
                mThunderScale[2] = 1.0f;
                mThunderX[2] = 3.0f;
                mThunderY[2] = 9.0f;
                mThunderStart[3] = (frameCnt % 400) + 8;
                mThunderDuration[3] = 5;
                mThunderNum[3] = 2;
                mThunderScale[3] = 1.0f;
                mThunderX[3] = 10.0f;
                mThunderY[3] = 10.0f;
                mThunderStart[4] = (frameCnt % 400) + 16;
                mThunderDuration[4] = 4;
                mThunderNum[4] = 1;
                mThunderScale[4] = 1.0f;
                mThunderX[4] = -2.0f;
                mThunderY[4] = 7.0f;
                mThunderStart[5] = (frameCnt % 400) + 5;
                mThunderDuration[5] = 5;
                mThunderNum[5] = 1;
                mThunderScale[5] = 1.0f;
                mThunderX[5] = -5.0f;
                mThunderY[5] = 8.0f;
            }
            return false;
        }

        for (int i = 0; i < 40; i++) {
            if (frameCnt % 400 > mThunderStart[i] && frameCnt % 400 < mThunderStart[i] + 8) {
                float alpha = (frameCnt % 400 < mThunderStart[i] + 4)
                        ? (0.8f + ((float) Math.random()) * 0.2f)
                        : (0.2f + ((float) Math.random()) * 0.2f);
                drawer.drawSprite(skyFlash, (-0.5f) + ((1.5f - offset) * 5.0f), 0.0f, -19.0f,
                        1.5f, 1.2f, 0.0f, alpha);
            }
            if (frameCnt % 400 > mThunderStart[i] && frameCnt % 400 < mThunderStart[i] + mThunderDuration[i]) {
                float alpha;
                if (frameCnt % 400 < mThunderStart[i] + (mThunderDuration[i] * 0.5d)) {
                    alpha = mThunderScale[i] > 0.75d
                            ? (0.8f + ((float) Math.random()) * 0.2f)
                            : (0.5f + ((float) Math.random()) * 0.2f);
                } else {
                    alpha = mThunderScale[i] > 0.75d
                            ? (0.3f + ((float) Math.random()) * 0.2f)
                            : (0.1f + ((float) Math.random()) * 0.2f);
                }
                int tex = mThunderNum[i] == 0 ? lightning1 : mThunderNum[i] == 1 ? lightning2 : lightning3;
                drawer.drawSprite(tex, mThunderX[i] + ((1.5f - offset) * 5.0f), mThunderY[i], -26.0f,
                        mThunderScale[i] * landscape, mThunderScale[i], 0.0f, alpha);
            }
        }

        return thunderOn;
    }
}