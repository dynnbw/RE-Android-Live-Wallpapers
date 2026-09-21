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

    private static final long DAY_MS = 86400000L;

    /**
     * 民用晨昏带：太阳从地平线降到地平线以下这么深，天就完全黑了。
     *
     * <p>与 grass 的 dawn/dusk 用的是同一条线（{@code SunCalculator.ZENITH_CIVIL}）。
     */
    private static final double CIVIL_TWILIGHT_DEG = 6.0;

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
     * （每小时 15°）。{@code zone} 为 null 表示用系统默认时区。
     *
     * <p>经度只决定日出日落（以及星空朝向）落在**当地几点**——赤道纬度下全年都是
     * 6 点上下，误差主要来自经度；15° 的经度偏差就是 1 小时的偏差。
     *
     * <p>nightsky 的星空朝向也用这条兜底，所以做成静态的、不依附于本类的实例状态。
     */
    public static float[] fallbackLatLng(TimeZone zone) {
        TimeZone tz = zone != null ? zone : TimeZone.getDefault();
        return new float[]{0.0f, (float) (tz.getRawOffset() / 3600000.0 * 15.0)};
    }

    public void applyFallbackLocation(TimeZone zone) {
        if (zone != null) {
            mTimeZone = zone;
        }
        float[] fallback = fallbackLatLng(mTimeZone);
        setLocation(fallback[0], fallback[1]);
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

    /**
     * 夜间权重：0 = 白天，1 = 完全入夜，中间是民用晨昏带上的平滑过渡。
     *
     * <p>给的是**连续量**而不是"是不是夜里"，让调用方能把白天那套观感和夜里那套
     * 按同一个权重交叉淡入，而不是在日落那一刻硬切。
     *
     * <p>两端是精确的 0 和 1 —— 调用方据此跳过另一遍绘制，于是白天和深夜里
     * 这个改动一分钱不花，只有晨昏那半小时会画两遍。
     *
     * <p>注意入夜判据仍是 {@link #isNight}（高度角过零），权重的中点在 −3°，
     * 也就是视觉上"半明半暗"的时刻比 {@code isNight} 翻面的时刻晚十几分钟 —— 这是对的，
     * 两者回答的是不同的问题。
     */
    public float nightWeight(long nowMs) {
        double altitude = sunAltitude(nowMs);
        if (altitude >= 0.0) {
            return 0.0f;
        }
        if (altitude <= -CIVIL_TWILIGHT_DEG) {
            return 1.0f;
        }
        float t = (float) (-altitude / CIVIL_TWILIGHT_DEG);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * 把真实时刻压进"一天"的某一刻 —— 预览专用。
     *
     * <p>预览没法等一整天，就把一天压进 {@code cycleMs}：先取当地零点，再加上
     * "当前处在第几个周期"映射到一天里的位置。于是预览里能完整看到日出→正午→
     * 日落→深夜，而实机走的是真实时间。
     *
     * <p>{@code cycleMs <= 0} 表示不压缩，直接返回真实时刻。
     *
     * <p>本来是 grass 里的一段私有逻辑，ocean/windmill 的预览也要用同一套，就提上来共用。
     */
    public long compressedClockMs(long realMs, long cycleMs) {
        if (cycleMs <= 0L) {
            return realMs;
        }
        mCalendar.setTimeZone(mTimeZone);
        mCalendar.setTimeInMillis(realMs);
        mCalendar.set(Calendar.HOUR_OF_DAY, 0);
        mCalendar.set(Calendar.MINUTE, 0);
        mCalendar.set(Calendar.SECOND, 0);
        mCalendar.set(Calendar.MILLISECOND, 0);
        long localMidnight = mCalendar.getTimeInMillis();
        return localMidnight + (realMs % cycleMs) * DAY_MS / cycleMs;
    }

    private Calendar calendarAt(long nowMs) {
        mCalendar.setTimeZone(mTimeZone);
        mCalendar.setTimeInMillis(nowMs);
        return mCalendar;
    }
}
