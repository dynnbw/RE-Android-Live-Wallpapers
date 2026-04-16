package com.reandroid.weather;

public final class WeatherState {
    public final WeatherCondition condition;
    public final boolean isNight;
    public final float tempMinC;
    public final float tempMaxC;
    public final long sunriseUtc;
    public final long sunsetUtc;
    public final long updateUtc;

    public WeatherState(WeatherCondition condition, boolean isNight, float tempMinC, float tempMaxC,
            long sunriseUtc, long sunsetUtc, long updateUtc) {
        this.condition = condition;
        this.isNight = isNight;
        this.tempMinC = tempMinC;
        this.tempMaxC = tempMaxC;
        this.sunriseUtc = sunriseUtc;
        this.sunsetUtc = sunsetUtc;
        this.updateUtc = updateUtc;
    }
}
