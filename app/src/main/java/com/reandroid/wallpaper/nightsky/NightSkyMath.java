package com.reandroid.wallpaper.nightsky;

final class NightSkyMath {
    static final double DEG_TO_RAD = Math.PI / 180.0;
    static final double TWO_PI = Math.PI * 2.0;

    private NightSkyMath() {
    }

    static double computeLocalSiderealTimeRad(long utcMs, float longitudeDeg) {
        double jd = (utcMs / 86400000.0) + 2440587.5;
        double d = jd - 2451545.0;
        double gstHours = 18.697374558 + 24.06570982441908 * d;
        double gstNormHours = ((gstHours % 24.0) + 24.0) % 24.0;
        double gstRad = gstNormHours / 24.0 * TWO_PI;
        double lstRad = gstRad + (longitudeDeg * DEG_TO_RAD);
        return normalizeRad(lstRad);
    }

    static double normalizeRad(double rad) {
        return ((rad % TWO_PI) + TWO_PI) % TWO_PI;
    }

    static void equatorialToHorizon(float raRad, float decRad, float latitudeRad, float lstRad, float[] outVec3) {
        float h = lstRad - raRad;

        float sinDec = (float) Math.sin(decRad);
        float cosDec = (float) Math.cos(decRad);
        float sinLat = (float) Math.sin(latitudeRad);
        float cosLat = (float) Math.cos(latitudeRad);
        float sinH = (float) Math.sin(h);
        float cosH = (float) Math.cos(h);

        float east = -cosDec * sinH;
        float north = (sinDec * cosLat) - (cosDec * sinLat * cosH);
        float up = (sinDec * sinLat) + (cosDec * cosLat * cosH);

        outVec3[0] = east;
        outVec3[1] = north;
        outVec3[2] = up;
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static float[] bvToRgb(float bv) {
        float x = clamp(bv, -0.4f, 2.0f);

        float r;
        float g;
        float b;

        if (x < 0.0f) {
            r = 0.62f + 0.11f * (x + 0.4f) / 0.4f;
            g = 0.70f + 0.07f * (x + 0.4f) / 0.4f;
            b = 1.0f;
        } else if (x < 0.4f) {
            r = 0.73f + 0.27f * x / 0.4f;
            g = 0.77f + 0.18f * x / 0.4f;
            b = 1.0f;
        } else if (x < 1.5f) {
            r = 1.0f;
            g = 0.95f - 0.35f * (x - 0.4f) / 1.1f;
            b = 1.0f - 0.70f * (x - 0.4f) / 1.1f;
        } else {
            r = 1.0f;
            g = 0.60f - 0.20f * (x - 1.5f) / 0.5f;
            b = 0.30f - 0.20f * (x - 1.5f) / 0.5f;
        }

        return new float[] { clamp(r, 0.0f, 1.0f), clamp(g, 0.0f, 1.0f), clamp(b, 0.0f, 1.0f) };
    }
}
