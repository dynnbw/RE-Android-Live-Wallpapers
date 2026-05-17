package com.reandroid.wallpaper;

public final class MathUtils {

    private MathUtils() {}

    // ---- clamp (int, float, long overloads) ----

    public static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static long clamp(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // ---- linear interpolation (lerp / mix) ----

    public static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    public static float mix(float a, float b, float t) {
        return lerp(a, b, t);
    }

    // ---- normalization (norm) ----

    public static float norm(float start, float stop, float value) {
        if (Math.abs(stop - start) < Float.MIN_VALUE) return 0f;
        return (value - start) / (stop - start);
    }

    // ---- smoothstep ----

    public static float smoothStep(float edge0, float edge1, float x) {
        if (x <= edge0) return 0f;
        if (x >= edge1) return 1f;
        float t = (x - edge0) / (edge1 - edge0);
        return t * t * (3 - 2 * t);
    }

    // ---- hsb to rgb ----

    public static int hsbToRgb(float h, float s, float b) {
        float hf = (h - (int) h) * 6.0f;
        int ihf = (int) hf;
        float f = hf - ihf;
        float pv = b * (1.0f - s);
        float qv = b * (1.0f - s * f);
        float tv = b * (1.0f - s * (1.0f - f));
        float red, green, blue;
        switch (ihf) {
            case 0: red = b; green = tv; blue = pv; break;
            case 1: red = qv; green = b; blue = pv; break;
            case 2: red = pv; green = b; blue = tv; break;
            case 3: red = pv; green = qv; blue = b; break;
            case 4: red = tv; green = pv; blue = b; break;
            default: red = b; green = pv; blue = qv; break;
        }
        return android.graphics.Color.argb(255,
                clamp((int)(red * 255), 0, 255),
                clamp((int)(green * 255), 0, 255),
                clamp((int)(blue * 255), 0, 255));
    }
}
