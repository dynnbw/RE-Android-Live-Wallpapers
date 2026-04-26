package com.reandroid.wallpaper.weatherwallpapers.windmill;

class WindmillRenderer {
    interface Drawer {
        void drawSpriteColored(int texture, float x, float y, float z,
                               float scaleX, float scaleY, float rotation,
                               float r, float g, float b, float alpha);
    }

    private WindmillInstance[] mWindmills;

    void initInstances() {
        mWindmills = new WindmillInstance[13];

        float[] windmillPosX = {-6.5f, -3.5f, -0.8f, 8.5f, 10.4f, -7.9f, -4.4f, -0.2f, 11.5f, 12.0f, -11.5f, -6.0f, -3.0f};
        float[] windmillPosY = {-2.8f, -1.3f, 2.2f, 0.3f, -1.1f, -2.7f, -2.75f, -2.75f, -2.5f, -2.8f, -3.5f, -3.3f, -3.2f};
        float[] windmillPosZ = {-23.0f, -23.0f, -23.0f, -23.0f, -23.0f, -24.05f, -24.05f, -24.05f, -23.95f, -23.95f, -25.0f, -25.0f, -25.0f};
        float[] windmillScaleX = {0.2f, 0.35f, 0.75f, 0.5f, 0.3f, 0.15f, 0.12f, 0.12f, 0.15f, 0.09f, 0.08f, 0.08f, 0.08f};
        float[] windmillScaleY = {0.2f, 0.35f, 0.75f, 0.5f, 0.3f, 0.15f, 0.12f, 0.12f, 0.15f, 0.09f, 0.08f, 0.08f, 0.08f};
        int[] windmillDistance = {0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 2, 2, 2};
        boolean[] windmillType = {true, true, true, true, true, false, false, false, false, false, false, false, false};
        boolean[] windmillFlip = {false, false, false, true, true, false, false, false, true, true, false, false, false};
        float[] windmillPillarOffsetX = {-0.05f, -0.1f, -0.15f, 0.1f, 0.05f, -0.05f, -0.05f, -0.05f, 0.05f, 0.05f, -0.05f, -0.05f, -0.05f};
        float[] windmillPillarOffsetY = {-1.55f, -2.7f, -5.9f, -3.9f, -2.32f, -1.2f, -0.9f, -0.9f, -1.1f, -0.7f, -0.6f, -0.6f, -0.6f};
        float[] windmillWingOffset = {0.0f, 20.0f, 40.0f, 60.0f, 80.0f, 20.0f, 40.0f, 60.0f, 80.0f, 100.0f, 40.0f, 60.0f, 80.0f};
        float[] windmillAlpha = {0.8f, 0.9f, 1.0f, 0.9f, 0.8f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.5f, 0.5f, 0.5f};

        for (int i = 0; i < mWindmills.length; i++) {
            WindmillInstance mill = new WindmillInstance();
            mill.center = new DrawingAttribute(windmillPosX[i], windmillPosY[i], windmillPosZ[i] - 0.1f,
                    windmillScaleX[i] * 0.04f, windmillScaleY[i] * 0.04f);
            mill.pillar = new DrawingAttribute(windmillPosX[i] + windmillPillarOffsetX[i],
                    windmillPosY[i] + windmillPillarOffsetY[i], windmillPosZ[i] + 0.1f,
                    windmillScaleX[i] * 0.08f, windmillScaleY[i]);
            mill.wing = new DrawingAttribute(windmillPosX[i], windmillPosY[i], windmillPosZ[i],
                    windmillScaleX[i], windmillScaleY[i]);
            mill.distance = windmillDistance[i];
            mill.isTypeA = windmillType[i];
            mill.alpha = windmillAlpha[i];
            mill.flip = windmillFlip[i];
            mill.wingOffset = windmillWingOffset[i];
            mWindmills[i] = mill;
        }
    }

    void drawByDistance(Drawer drawer,
                        int distance,
                        int frameCnt,
                        float offset,
                        float landscape,
                        boolean isNight,
                        int windmillWing,
                        int windmillWingBlur,
                        int windmillCenter1,
                        int windmillCenter2,
                        int windmillPillar1,
                        int windmillPillar2,
                        int windmillPillarFlip1,
                        int windmillPillarFlip2) {
        if (mWindmills == null) {
            return;
        }

        float tone = isNight ? 0.0f : 1.0f;
        for (int i = 0; i < mWindmills.length; i++) {
            WindmillInstance mill = mWindmills[i];
            if (mill == null || mill.distance != distance) {
                continue;
            }

            mill.fanAngle = (frameCnt * -1.8f) + mill.wingOffset;
            drawSingle(drawer, mill, offset, landscape, tone,
                    windmillWing, windmillWingBlur,
                    windmillCenter1, windmillCenter2,
                    windmillPillar1, windmillPillar2,
                    windmillPillarFlip1, windmillPillarFlip2);
        }
    }

    private void drawSingle(Drawer drawer,
                            WindmillInstance mill,
                            float offset,
                            float landscape,
                            float tone,
                            int windmillWing,
                            int windmillWingBlur,
                            int windmillCenter1,
                            int windmillCenter2,
                            int windmillPillar1,
                            int windmillPillar2,
                            int windmillPillarFlip1,
                            int windmillPillarFlip2) {
        float weight = mill.distance == 0 ? 1.2f : mill.distance == 1 ? 0.5f : 0.2f;

        int pillarTex;
        if (mill.isTypeA) {
            pillarTex = mill.flip ? windmillPillarFlip1 : windmillPillar1;
        } else {
            pillarTex = mill.flip ? windmillPillarFlip2 : windmillPillar2;
        }
        drawer.drawSpriteColored(
                pillarTex,
                calcX(mill.pillar.x, offset, weight),
                mill.pillar.y,
                mill.pillar.z,
                mill.pillar.scaleX * landscape,
                mill.pillar.scaleY,
                0.0f,
                tone,
                tone,
                tone,
                mill.alpha
        );

        int wingTex = mill.distance == 0 ? windmillWing : windmillWingBlur;
        drawer.drawSpriteColored(
                wingTex,
                calcX(mill.wing.x, offset, weight),
                mill.wing.y,
                mill.wing.z,
                mill.wing.scaleX * landscape,
                mill.wing.scaleY,
                mill.fanAngle,
                tone,
                tone,
                tone,
                mill.alpha
        );

        int centerTex = mill.isTypeA ? windmillCenter1 : windmillCenter2;
        drawer.drawSpriteColored(
                centerTex,
                calcX(mill.center.x, offset, weight),
                mill.center.y,
                mill.center.z,
                mill.center.scaleX * landscape,
                mill.center.scaleY,
                0.0f,
                tone,
                tone,
                tone,
                mill.alpha
        );
    }

    private float calcX(float x, float offset, float weight) {
        return (x - 1.5f) + ((1.5f - (offset * weight)) * 5.0f);
    }

    private static final class DrawingAttribute {
        final float x;
        final float y;
        final float z;
        final float scaleX;
        final float scaleY;

        DrawingAttribute(float x, float y, float z, float scaleX, float scaleY) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    private static final class WindmillInstance {
        DrawingAttribute pillar;
        DrawingAttribute center;
        DrawingAttribute wing;
        float fanAngle;
        float alpha;
        boolean isTypeA;
        boolean flip;
        int distance;
        float wingOffset;
    }
}
