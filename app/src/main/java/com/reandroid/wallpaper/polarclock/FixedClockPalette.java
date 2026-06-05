package com.reandroid.wallpaper.polarclock;

import android.content.res.XmlResourceParser;
import android.graphics.Color;

class FixedClockPalette extends ClockPalette {
    String mId;
    int mBackgroundColor;
    int mSecondColor, mMinuteColor, mHourColor, mDayColor, mMonthColor;

    private static FixedClockPalette sFallbackPalette;

    static FixedClockPalette getFallback() {
        if (sFallbackPalette == null) {
            sFallbackPalette = new FixedClockPalette();
            sFallbackPalette.mId = "default";
            sFallbackPalette.mBackgroundColor = Color.WHITE;
            sFallbackPalette.mSecondColor = sFallbackPalette.mMinuteColor =
                sFallbackPalette.mHourColor = sFallbackPalette.mDayColor =
                sFallbackPalette.mMonthColor = Color.BLACK;
        }
        return sFallbackPalette;
    }

    private FixedClockPalette() {}

    static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
        final FixedClockPalette pal = new FixedClockPalette();
        pal.mId = xrp.getAttributeValue(null, "id");
        String val;
        if ((val = xrp.getAttributeValue(null, "background")) != null)
            pal.mBackgroundColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "second")) != null)
            pal.mSecondColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "minute")) != null)
            pal.mMinuteColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "hour")) != null)
            pal.mHourColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "day")) != null)
            pal.mDayColor = Color.parseColor(val);
        if ((val = xrp.getAttributeValue(null, "month")) != null)
            pal.mMonthColor = Color.parseColor(val);
        return (pal.mId == null) ? null : pal;
    }

    @Override public int getBackgroundColor() { return mBackgroundColor; }
    @Override public int getSecondColor(float a) { return mSecondColor; }
    @Override public int getMinuteColor(float a) { return mMinuteColor; }
    @Override public int getHourColor(float a) { return mHourColor; }
    @Override public int getDayColor(float a) { return mDayColor; }
    @Override public int getMonthColor(float a) { return mMonthColor; }
    @Override public String getId() { return mId; }
}
