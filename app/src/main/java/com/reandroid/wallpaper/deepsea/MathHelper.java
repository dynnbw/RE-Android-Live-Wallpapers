package com.reandroid.wallpaper.deepsea;

class MathHelper {
    MathHelper() {
    }

    public static float getRandomFloat(float f, float f2) {
        return (((float) Math.random()) * (f2 - f)) + f;
    }

    public static int getIndexForFragmentShader(int i, int i2) {
        if (i < i2 - 1) {
            return i + 1;
        }
        return 0;
    }

    public static float[] getRGB(int i) {
        return new float[]{((i >> 16) & 255) / 255.0f, ((i >> 8) & 255) / 255.0f, (i & 255) / 255.0f};
    }
}
