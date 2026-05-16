package com.reandroid.wallpaper.deepsea;

class PositionVO {
    private static final int REF_WIDTH = 720;
    private static final int REF_HEIGHT = 1280;
    private static float[] mRandomPositions;
    private static float[] mPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};
    private static final float[] mDefaultPositions = {-3.0f, -0.88f, 0.88f, -2.0f, 1.8f, -4.0f, -1.46f, 1.46f, -3.0f, 2.72f, -5.0f, -2.0f, 2.0f, -4.0f, 3.64f, -6.0f, -2.6f, 2.6f, -5.0f, 4.58f, -7.0f, -3.13f, 3.13f, -6.0f, 5.5f, -8.0f, -3.7f, 3.7f, -7.0f, 6.4f, -9.0f, -4.25f, 4.25f, -8.0f, 7.3f, -10.0f, -4.8f, 4.8f, -9.0f, 8.25f};

    PositionVO() {
    }

    public static void changePositions(int i, int i2) {
        if (i > REF_WIDTH || i2 > REF_HEIGHT) {
            float f = (i / (float) REF_WIDTH) * 2.0f;
            float[] fArr = mPositions;
            float[] fArr2 = mDefaultPositions;
            float f2 = fArr2[2];
            fArr[1] = (-(f2 - fArr2[1])) * f;
            fArr[2] = (f2 - fArr2[1]) * f;
            float f3 = fArr2[7];
            fArr[6] = (-(f3 - fArr2[6])) * f;
            fArr[7] = (f3 - fArr2[6]) * f;
            float f4 = fArr2[12];
            fArr[11] = (-(f4 - fArr2[11])) * f;
            fArr[12] = (f4 - fArr2[11]) * f;
            float f5 = fArr2[17];
            fArr[16] = (-(f5 - fArr2[16])) * f;
            fArr[17] = (f5 - fArr2[16]) * f;
            float f6 = fArr2[22];
            fArr[21] = (-(f6 - fArr2[21])) * f;
            fArr[22] = (f6 - fArr2[21]) * f;
            float f7 = fArr2[27];
            fArr[26] = (-(f7 - fArr2[26])) * f;
            fArr[27] = (f7 - fArr2[26]) * f;
            float f8 = fArr2[32];
            fArr[31] = (-(f8 - fArr2[31])) * f;
            fArr[32] = (f8 - fArr2[31]) * f;
            float f9 = fArr2[37];
            fArr[36] = (-(f9 - fArr2[36])) * f;
            fArr[37] = (f9 - fArr2[36]) * f;
            float f22 = (i2 / (float) REF_HEIGHT) * 2.0f;
            float f10 = fArr2[4];
            fArr[3] = (-(f10 - fArr2[3])) * f22;
            fArr[4] = (f10 - fArr2[3]) * f22;
            float f11 = fArr2[9];
            fArr[8] = (-(f11 - fArr2[8])) * f22;
            fArr[9] = (f11 - fArr2[8]) * f22;
            float f12 = fArr2[14];
            fArr[13] = (-(f12 - fArr2[13])) * f22;
            fArr[14] = (f12 - fArr2[13]) * f22;
            float f13 = fArr2[19];
            fArr[18] = (-(f13 - fArr2[18])) * f22;
            fArr[19] = (f13 - fArr2[18]) * f22;
            float f14 = fArr2[24];
            fArr[23] = (-(f14 - fArr2[23])) * f22;
            fArr[24] = (f14 - fArr2[23]) * f22;
            float f15 = fArr2[29];
            fArr[28] = (-(f15 - fArr2[28])) * f22;
            fArr[29] = (f15 - fArr2[28]) * f22;
            float f16 = fArr2[34];
            fArr[33] = (-(f16 - fArr2[33])) * f22;
            fArr[34] = (f16 - fArr2[33]) * f22;
            float f17 = fArr2[39];
            fArr[38] = (-(f17 - fArr2[38])) * f22;
            fArr[39] = (f17 - fArr2[38]) * f22;
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

    public static float[] getMinMaxXYByZ(float f) {
        float f2 = f <= -2.0f ? f : -2.0f;
        float f3 = f2 >= -10.0f ? f2 : -10.0f;
        int length = mPositions.length / 5;
        int i = 0;
        float f4 = 0.0f;
        float f5 = 0.0f;
        float f6 = 0.0f;
        float f7 = 0.0f;
        while (true) {
            if (i >= length) {
                break;
            }
            int i2 = i * 5;
            if (i == length - 1) {
                float[] fArr = mPositions;
                f6 = fArr[i2 + 1];
                f4 = fArr[i2 + 2];
                f7 = fArr[i2 + 3];
                f5 = fArr[i2 + 4];
            } else {
                float[] fArr2 = mPositions;
                if (f3 > fArr2[i2 + 5]) {
                    f6 = fArr2[i2 + 1];
                    f4 = fArr2[i2 + 2];
                    f7 = fArr2[i2 + 3];
                    f5 = fArr2[i2 + 4];
                    break;
                }
            }
            i++;
        }
        return new float[]{f6, f4, f7, f5};
    }

    public static void initRandomPositions() {
        mRandomPositions = null;
        mRandomPositions = new float[80];
        for (int i = 0; i < 8; i++) {
            int i2 = i * 5 * 2;
            float[] randomPositionByZindex = getRandomPositionByZindex(i);
            float f = randomPositionByZindex[0];
            float f2 = randomPositionByZindex[1];
            float f3 = randomPositionByZindex[2];
            float f4 = randomPositionByZindex[3];
            float f5 = randomPositionByZindex[4];
            float[] fArr = mRandomPositions;
            fArr[i2] = f;
            fArr[i2 + 1] = f2;
            fArr[i2 + 2] = f3;
            fArr[i2 + 3] = f4;
            fArr[i2 + 4] = f5;
            float f6 = randomPositionByZindex[5];
            float f7 = randomPositionByZindex[6];
            float f8 = randomPositionByZindex[7];
            float f9 = randomPositionByZindex[8];
            fArr[i2 + 5] = f;
            fArr[i2 + 6] = f6;
            fArr[i2 + 7] = f7;
            fArr[i2 + 8] = f8;
            fArr[i2 + 9] = f9;
        }
    }

    public static float getZByIndex(int i) {
        return getZs()[i];
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
    }  private static float[] getRandomPositionByZindex(int i) {
        char c;
        float f;
        float f2;
        float f3;
        float f4;
        float f5;
        float f6;
        float f7;
        float f8;
        float f9 = mPositions[i * 5];
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
