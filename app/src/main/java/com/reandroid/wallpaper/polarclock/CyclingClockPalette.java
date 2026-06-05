package com.reandroid.wallpaper.polarclock;

import android.content.res.XmlResourceParser;
import android.graphics.Color;

class CyclingClockPalette extends ClockPalette {
    String mId;
    int mBackgroundColor;
    float mSaturation;
    float mBrightness;

    private static final int COLORS_CACHE_COUNT = 720;
    final int[] mColors = new int[COLORS_CACHE_COUNT];

    private static CyclingClockPalette sFallbackPalette;

    static CyclingClockPalette getFallback() {
        if (sFallbackPalette == null) {
            sFallbackPalette = new CyclingClockPalette();
            sFallbackPalette.mId = "default_c";
            sFallbackPalette.mBackgroundColor = Color.WHITE;
            sFallbackPalette.mSaturation = 0.8f;
            sFallbackPalette.mBrightness = 0.9f;
            sFallbackPalette.computeIntermediateColors();
        }
        return sFallbackPalette;
    }

    private CyclingClockPalette() {}

    private void computeIntermediateColors() {
        final int[] colors = mColors;
        final int count = colors.length;
        float invCount = 1.0f / (float) COLORS_CACHE_COUNT;
        float[] hsb = new float[3];
        for (int i = 0; i < count; i++) {
            hsb[0] = ((float) i * invCount) * 360.0f;
            hsb[1] = mSaturation;
            hsb[2] = mBrightness;
            colors[i] = Color.HSVToColor(hsb);
        }
    }

    static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
        final CyclingClockPalette pal = new CyclingClockPalette();
        pal.mId = xrp.getAttributeValue(null, "id");
        String val;
        if ((val = xrp.getAttributeValue(null, "background")) != null)
            pal.mBackgroundColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "saturation")) != null)
            pal.mSaturation = Float.parseFloat(val);
        if ((val = xrp.getAttributeValue(null, "brightness")) != null)
            pal.mBrightness = Float.parseFloat(val);
        pal.computeIntermediateColors();
        return (pal.mId == null) ? null : pal;
    }

    @Override public int getBackgroundColor() { return mBackgroundColor; }
    @Override public int getSecondColor(float a) { return getCyclingColor(a); }
    @Override public int getMinuteColor(float a) { return getCyclingColor(a); }
    @Override public int getHourColor(float a) { return getCyclingColor(a); }
    @Override public int getDayColor(float a) { return getCyclingColor(a); }
    @Override public int getMonthColor(float a) { return getCyclingColor(a); }
    @Override public String getId() { return mId; }

    private int getCyclingColor(float angle) {
        if (angle >= 1.0f || angle < 0.0f) angle = 0.0f;
        int idx = (int) (angle * COLORS_CACHE_COUNT);
        if (idx < 0) idx = 0;
        if (idx >= COLORS_CACHE_COUNT) idx = COLORS_CACHE_COUNT - 1;
        return mColors[idx];
    }
}
