package com.reandroid.wallpaper.deepsea;

class PositionVO {
    private static final int REF_WIDTH = 720;
    private static final int REF_HEIGHT = 1280;
    private static float[] mRandomPositions;
    private static float[] mPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};
    private static final float[] mDefaultPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};

    PositionVO() {
    }

    public static void changePositions(int width, int height) {
        if (width > REF_WIDTH || height > REF_HEIGHT) {
            float xScaleFactor = (width / (float) REF_WIDTH) * 2.0f;
            float[] fArr = mPositions;
            float[] fArr2 = mDefaultPositions;
            float f2 = fArr2[2];
            fArr[1] = (-(f2 - fArr2[1])) * xScaleFactor;
            fArr[2] = (f2 - fArr2[1]) * xScaleFactor;
            float f3 = fArr2[7];
            fArr[6] = (-(f3 - fArr2[6])) * xScaleFactor;
            fArr[7] = (f3 - fArr2[6]) * xScaleFactor;
            float f4 = fArr2[12];
            fArr[11] = (-(f4 - fArr2[11])) * xScaleFactor;
            fArr[12] = (f4 - fArr2[11]) * xScaleFactor;
            float f5 = fArr2[17];
            fArr[16] = (-(f5 - fArr2[16])) * xScaleFactor;
            fArr[17] = (f5 - fArr2[16]) * xScaleFactor;
            float f6 = fArr2[22];
            fArr[21] = (-(f6 - fArr2[21])) * xScaleFactor;
            fArr[22] = (f6 - fArr2[21]) * xScaleFactor;
            float f7 = fArr2[27];
            fArr[26] = (-(f7 - fArr2[26])) * xScaleFactor;
            fArr[27] = (f7 - fArr2[26]) * xScaleFactor;
            float f8 = fArr2[32];
            fArr[31] = (-(f8 - fArr2[31])) * xScaleFactor;
            fArr[32] = (f8 - fArr2[31]) * xScaleFactor;
            float f9 = fArr2[37];
            fArr[36] = (-(f9 - fArr2[36])) * xScaleFactor;
            fArr[37] = (f9 - fArr2[36]) * xScaleFactor;
            float yScaleFactor = (height / (float) REF_HEIGHT) * 2.0f;
            float f10 = fArr2[4];
            fArr[3] = (-(f10 - fArr2[3])) * yScaleFactor;
            fArr[4] = (f10 - fArr2[3]) * yScaleFactor;
            float f11 = fArr2[9];
            fArr[8] = (-(f11 - fArr2[8])) * yScaleFactor;
            fArr[9] = (f11 - fArr2[8]) * yScaleFactor;
            float f12 = fArr2[14];
            fArr[13] = (-(f12 - fArr2[13])) * yScaleFactor;
            fArr[14] = (f12 - fArr2[13]) * yScaleFactor;
            float f13 = fArr2[19];
            fArr[18] = (-(f13 - fArr2[18])) * yScaleFactor;
            fArr[19] = (f13 - fArr2[18]) * yScaleFactor;
            float f14 = fArr2[24];
            fArr[23] = (-(f14 - fArr2[23])) * yScaleFactor;
            fArr[24] = (f14 - fArr2[23]) * yScaleFactor;
            float f15 = fArr2[29];
            fArr[28] = (-(f15 - fArr2[28])) * yScaleFactor;
            fArr[29] = (f15 - fArr2[28]) * yScaleFactor;
            float f16 = fArr2[34];
            fArr[33] = (-(f16 - fArr2[33])) * yScaleFactor;
            fArr[34] = (f16 - fArr2[33]) * yScaleFactor;
            float f17 = fArr2[39];
            fArr[38] = (-(f17 - fArr2[38])) * yScaleFactor;
            fArr[39] = (f17 - fArr2[38]) * yScaleFactor;
            return;
        }
        float[] fArr3 = mPositions;
        float[] fArr4 = mDefaultPositions;
        fArr3[1] = fArr4[1];
        fArr3[2] = fArr4[2];
        fArr3[6] = fArr4[6];
        fArr3[7] = fArr4[7];
        fArr3[11] = fArr4[11];
        fArr3[12] = fArr4[12];
        fArr3[16] = fArr4[16];
        fArr3[17] = fArr4[17];
        fArr3[21] = fArr4[21];
        fArr3[22] = fArr4[22];
        fArr3[26] = fArr4[26];
        fArr3[27] = fArr4[27];
        fArr3[31] = fArr4[31];
        fArr3[32] = fArr4[32];
        fArr3[36] = fArr4[36];
        fArr3[37] = fArr4[37];
        fArr3[3] = fArr4[3];
        fArr3[4] = fArr4[4];
        fArr3[8] = fArr4[8];
        fArr3[9] = fArr4[9];
        fArr3[13] = fArr4[13];
        fArr3[14] = fArr4[14];
        fArr3[18] = fArr4[18];
        fArr3[19] = fArr4[19];
        fArr3[23] = fArr4[23];
        fArr3[24] = fArr4[24];
        fArr3[28] = fArr4[28];
        fArr3[29] = fArr4[29];
        fArr3[33] = fArr4[33];
        fArr3[34] = fArr4[34];
        fArr3[38] = fArr4[38];
        fArr3[39] = fArr4[39];
    }

    public static float[] getMinMaxXYByZ(float originalZ) {
        float zClampedMin = originalZ <= -2.0f ? originalZ : -2.0f;
        float zClampedFinal = zClampedMin >= -10.0f ? zClampedMin : -10.0f;
        int length = mPositions.length / 5;
        int depthIndex = 0;
        float minX = 0.0f;
        float maxX = 0.0f;
        float minY = 0.0f;
        float maxY = 0.0f;
        while (true) {
            if (depthIndex >= length) {
                break;
            }
            int arrayOffset = depthIndex * 5;
            if (depthIndex == length - 1) {
                float[] fArr = mPositions;
                minY = fArr[arrayOffset + 1];
                minX = fArr[arrayOffset + 2];
                maxY = fArr[arrayOffset + 3];
                maxX = fArr[arrayOffset + 4];
            } else {
                float[] fArr2 = mPositions;
                if (zClampedFinal > fArr2[arrayOffset + 5]) {
                    minY = fArr2[arrayOffset + 1];
                    minX = fArr2[arrayOffset + 2];
                    maxY = fArr2[arrayOffset + 3];
                    maxX = fArr2[arrayOffset + 4];
                    break;
                }
            }
            depthIndex++;
        }
        return new float[]{minY, minX, maxY, maxX};
    }

    public static void initRandomPositions() {
        mRandomPositions = null;
        mRandomPositions = new float[80];
        for (int depthLayerIndex = 0; depthLayerIndex < 8; depthLayerIndex++) {
            int arrayOffset = depthLayerIndex * 5 * 2;
            float[] randomPositionByZindex = getRandomPositionByZindex(depthLayerIndex);
            float f = randomPositionByZindex[0];
            float f2 = randomPositionByZindex[1];
            float f3 = randomPositionByZindex[2];
            float f4 = randomPositionByZindex[3];
            float f5 = randomPositionByZindex[4];
            float[] fArr = mRandomPositions;
            fArr[arrayOffset] = f;
            fArr[arrayOffset + 1] = f2;
            fArr[arrayOffset + 2] = f3;
            fArr[arrayOffset + 3] = f4;
            fArr[arrayOffset + 4] = f5;
            float f6 = randomPositionByZindex[5];
            float f7 = randomPositionByZindex[6];
            float f8 = randomPositionByZindex[7];
            float f9 = randomPositionByZindex[8];
            fArr[arrayOffset + 5] = f;
            fArr[arrayOffset + 6] = f6;
            fArr[arrayOffset + 7] = f7;
            fArr[arrayOffset + 8] = f8;
            fArr[arrayOffset + 9] = f9;
        }
    }

    public static float getZByIndex(int depthIndex) {
        return getZs()[depthIndex];
    }

    public static int getNumberOfZs() {
        return 8;
    }

    private static float[] getZs() {
        float[] fArr = new float[8];
        int length = mPositions.length / 5;
        for (int i = 0; i < length; i++) {
            fArr[i] = mPositions[i * 5];
        }
        return fArr;
    }  private static float[] getRandomPositionByZindex(int depthLayerIndex) {
        char c;
        float f;
        float f2;
        float f3;
        float f4;
        float f5;
        float f6;
        float f7;
        float f8;
        float f9 = mPositions[depthLayerIndex * 5];
        int random = (int) (Math.random() * 4.0d);
        float[] minMaxXYByZ = getMinMaxXYByZ(f9);
        float f10 = minMaxXYByZ[0];
        float f11 = minMaxXYByZ[1];
        float f12 = minMaxXYByZ[2];
        float f13 = minMaxXYByZ[3];
        if (random == 0) {
            c = 3;
            f = 0.0f;
            f2 = f10 - 0.2f;
            f3 = f13 + 0.2f;
            f4 = 0.2f;
        } else if (random == 1) {
            c = 2;
            f = f12 - 0.2f;
            f2 = f10 - 0.2f;
            f3 = 0.0f;
            f4 = 0.2f;
        } else if (random == 2) {
            c = 1;
            f = 0.0f;
            f2 = 0.2f;
            f3 = f13 + 0.2f;
            f4 = f11 + 0.2f;
        } else {
            c = 0;
            f = f12 - 0.2f;
            f2 = 0.2f;
            f3 = 0.0f;
            f4 = f11 + 0.2f;
        }
        if (c == 0) {
            f5 = f10 - 0.2f;
            f6 = 0.2f;
            f7 = 0.0f;
            f8 = 0.2f + f13;
        } else if (c == 1) {
            f5 = f10 - 0.2f;
            f6 = 0.2f;
            f7 = f12 - 0.2f;
            f8 = 0.0f;
        } else if (c == 2) {
            f5 = 0.2f;
            f6 = f11 + 0.2f;
            f7 = 0.0f;
            f8 = 0.2f + f13;
        } else {
            f5 = 0.2f;
            f6 = f11 + 0.2f;
            f7 = f12 - 0.2f;
            f8 = 0.0f;
        }
        return new float[]{f9, f2, f4, f, f3, f5, f6, f7, f8};
    }
}
