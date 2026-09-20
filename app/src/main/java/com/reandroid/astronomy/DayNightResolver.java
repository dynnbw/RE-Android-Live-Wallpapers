package com.reandroid.astronomy;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 某地某时刻的昼夜判定 —— grass、ocean、windmill 共用这一套。
 *
 * <p>改成共用之前，ocean 和 windmill 的"是不是夜里"读的是
 * {@code last_sunrise}/{@code last_sunset}，拿不到就退回硬编码的 06:00/18:00。
 * 而那份读法实际上是**死的分支**：它读的是插件的 {@code plugin_<id>} 设置文件，
 * 而这两个键由 {@code WeatherManager} 写进应用的默认设置文件 —— 于是永远是后者，
 * 一整年都按本地钟表的 6 点/18 点切换，和用户在哪、什么季节都没有关系。
 *
 * <p>现在改成用设备位置就地算太阳高度角：不依赖网络，与经度、季节都对得上。
 *
 * <p>纯 Java（{@link Calendar}、{@link TimeZone} 都是 JDK 的），位置从哪来由
 * {@link DeviceLocation} 负责 —— 于是这个类能在 JVM 上单独测试。
 */
public final class DayNightResolver {

    /** 复用同一个 Calendar：{@code computeSunAltitude} 每帧都要读，不想每帧分配。 */
    private final Calendar mCalendar = Calendar.getInstance();

    private double mLatitude;
    private double mLongitude;
    private TimeZone mTimeZone = TimeZone.getDefault();
    private SunCalculator mSunCalculator;

    public DayNightResolver() {
        applyFallbackLocation(mTimeZone);
    }

    /**
     * 没有定位权限、也拿不到最后已知位置时的兜底：纬度取赤道，经度按时区偏移反推
     * （每小时 15°）。
     *
     * <p>经度只决定日出日落落在**当地几点**——赤道纬度下全年都是 6 点上下，
     * 误差主要来自经度；15° 的经度偏差就是 1 小时的时刻偏差。
     */
    public void applyFallbackLocation(TimeZone zone) {
        if (zone != null) {
            mTimeZone = zone;
        }
        setLocation(0.0, mTimeZone.getRawOffset() / 3600000.0 * 15.0);
    }

    /** 位置与时区一起换 —— 太阳计算器只在构造时认经纬度和时区，任一变化都要重建。 */
    public void setLocation(double latitude, double longitude) {
        mLatitude = latitude;
        mLongitude = longitude;
        mSunCalculator = new SunCalculator(mLatitude, mLongitude, mTimeZone.getID());
    }

    public void setTimeZone(TimeZone zone) {
        if (zone == null || zone.getID().equals(mTimeZone.getID())) {
            return;
        }
        mTimeZone = zone;
        setLocation(mLatitude, mLongitude);
    }

    public SunCalculator getSunCalculator() {
        return mSunCalculator;
    }

    public double getLatitude() {
        return mLatitude;
    }

    public double getLongitude() {
        return mLongitude;
    }

    public TimeZone getTimeZone() {
        return mTimeZone;
    }

    /** 该时刻的太阳高度角（度），地平线以下为负。 */
    public double sunAltitude(long nowMs) {
        return mSunCalculator.computeSunAltitude(calendarAt(nowMs));
    }

    /**
     * 该时刻是不是夜里。
     *
     * <p>判据是**太阳中心落到地平线以下**——和 grass 里那个 {@code isNight} 同一条规则，
     * 全项目只有一个"夜"的定义。这样极昼极夜天然成立（高度角一直为正或一直为负），
     * 不必像日出日落时刻那样给"太阳永不升起/永不落下"打特判。
     *
     * <p>比官方的日出日落时刻早两三分钟（官方算的是太阳上缘擦地平线，且含大气折射），
     * 对壁纸没有意义。
     */
    public boolean isNight(long nowMs) {
        return sunAltitude(nowMs) < 0.0;
    }

    private Calendar calendarAt(long nowMs) {
        mCalendar.setTimeZone(mTimeZone);
        mCalendar.setTimeInMillis(nowMs);
        return mCalendar;
    }
}
