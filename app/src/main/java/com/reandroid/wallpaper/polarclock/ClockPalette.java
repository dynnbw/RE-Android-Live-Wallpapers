package com.reandroid.wallpaper.polarclock;

import android.content.res.XmlResourceParser;

abstract class ClockPalette {
    static ClockPalette parseXmlPaletteTag(XmlResourceParser xrp) {
        String kind = xrp.getAttributeValue(null, "kind");
        if ("cycling".equals(kind)) {
            return CyclingClockPalette.parseXmlPaletteTag(xrp);
        } else {
            return FixedClockPalette.parseXmlPaletteTag(xrp);
        }
    }

    public abstract int getBackgroundColor();
    public abstract int getSecondColor(float forAngle);
    public abstract int getMinuteColor(float forAngle);
    public abstract int getHourColor(float forAngle);
    public abstract int getDayColor(float forAngle);
    public abstract int getMonthColor(float forAngle);
    public abstract String getId();
}
