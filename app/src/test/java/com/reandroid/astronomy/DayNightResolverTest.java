package com.reandroid.astronomy;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DayNightResolver} / {@link SunCalculator} 的行为测试。
 *
 * <p>这套东西现在服务三个壁纸，判据是"某地某刻是不是夜里"。要能自查的点不是公式，
 * 而是几条**能拿现实核对**的性质：北京夏至的日出日落时刻、极昼极夜整天不变、
 * 南半球季节相反、没定位权限时的兜底落在 6 点上下。
 *
 * <p>刻意不碰 {@link DeviceLocation} —— 那是 Android 那一半，在 JVM 上跑不了。
 * 这里测的是纯计算那一半，也正是它存在的理由。
 */
public class DayNightResolverTest {

    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    private static final double BEIJING_LAT = 39.9042;
    private static final double BEIJING_LNG = 116.4074;
    private static final double SYDNEY_LAT = -33.8688;
    private static final double SYDNEY_LNG = 151.2093;

    private static Calendar at(TimeZone tz, int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c;
    }

    private static DayNightResolver fixed(double lat, double lng, TimeZone tz) {
        DayNightResolver resolver = new DayNightResolver();
        resolver.setTimeZone(tz);
        resolver.setLocation(lat, lng);
        return resolver;
    }

    /** 北京夏至：日出约 04:46、日落约 19:47（东八区）。容差 20 分钟。 */
    @Test
    public void beijingSolsticeMatchesAlmanac() {
        SunCalculator sun = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI).getSunCalculator();
        Calendar day = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0);

        double sunrise = sun.computeSunriseTime(SunCalculator.ZENITH_OFFICIAL, day);
        double sunset = sun.computeSunsetTime(SunCalculator.ZENITH_OFFICIAL, day);

        assertEquals("北京 2026-06-21 日出", 4.77, sunrise, 20.0 / 60.0);
        assertEquals("北京 2026-06-21 日落", 19.78, sunset, 20.0 / 60.0);
    }

    /** 昼夜跟着当地时间走：正午是白天，午夜是夜里。 */
    @Test
    public void noonIsDayMidnightIsNight() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);

        long noon = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();
        long midnight = at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0).getTimeInMillis();

        assertFalse("北京夏至正午应当不是夜里", resolver.isNight(noon));
        assertTrue("北京夏至午夜应当是夜里", resolver.isNight(midnight));
    }

    /**
     * 极昼极夜整天不变。
     *
     * <p>这条是选"太阳高度角过零"而不是"比日出日落时刻"当判据的**理由**：
     * 极区里日出日落时刻返回值退化成 0 或 24，比时刻会把极昼判成整夜。
     * 高度角不看这两个退化值，天然正确。
     */
    @Test
    public void polarDayAndNightHoldAllDay() {
        DayNightResolver svalbard = fixed(78.22, 15.65, TimeZone.getTimeZone("Europe/Oslo"));

        for (int hour = 0; hour < 24; hour++) {
            long summer = at(TimeZone.getTimeZone("Europe/Oslo"), 2026, Calendar.JUNE, 21, hour, 0)
                    .getTimeInMillis();
            assertFalse("朗伊尔城夏至 " + hour + " 点不该是夜里", svalbard.isNight(summer));

            long winter = at(TimeZone.getTimeZone("Europe/Oslo"), 2026, Calendar.DECEMBER, 21, hour, 0)
                    .getTimeInMillis();
            assertTrue("朗伊尔城冬至 " + hour + " 点该是夜里", svalbard.isNight(winter));
        }
    }

    /** 南北半球季节相反：同一个六月，北京白天长、悉尼白天短。 */
    @Test
    public void hemispheresHaveOppositeSeasons() {
        assertEquals("六月北京是长昼", 1, dayLengthOrder(BEIJING_LAT, BEIJING_LNG, SHANGHAI));
        assertEquals("六月悉尼是短昼", -1, dayLengthOrder(SYDNEY_LAT, SYDNEY_LNG, SHANGHAI));
    }

    /**
     * 昼长排序：夏至 &gt; 春分 &gt; 冬至 → 1；反之为 -1。
     *
     * <p>用"顺序"而不是具体小时数，是为了不把测试绑死在公式精度上 ——
     * 顺序错了才是真的坏了。
     */
    private static int dayLengthOrder(double lat, double lng, TimeZone tz) {
        double summer = dayLengthHours(lat, lng, tz, 2026, Calendar.JUNE, 21);
        double equinox = dayLengthHours(lat, lng, tz, 2026, Calendar.MARCH, 21);
        double winter = dayLengthHours(lat, lng, tz, 2026, Calendar.DECEMBER, 21);
        if (summer > equinox && equinox > winter) return 1;
        if (summer < equinox && equinox < winter) return -1;
        return 0;
    }

    /**
     * 昼长（小时）。
     *
     * <p>两个时刻都是**当地钟点**，时区与经度不匹配时它们会跨过午夜（日落算出来比日出还小），
     * 所以差值要按 24 小时取模。这是纯计算问题，不是靠"给每个城市配对的时区"绕开的。
     */
    private static double dayLengthHours(double lat, double lng, TimeZone tz,
            int year, int month, int day) {
        SunCalculator sun = fixed(lat, lng, tz).getSunCalculator();
        Calendar c = at(tz, year, month, day, 12, 0);
        double length = sun.computeSunsetTime(SunCalculator.ZENITH_OFFICIAL, c)
                - sun.computeSunriseTime(SunCalculator.ZENITH_OFFICIAL, c);
        if (length < 0.0) length += 24.0;
        return length;
    }

    /** 春分全球昼夜等长（12 小时上下），南北半球、赤道、高纬都一样。 */
    @Test
    public void equinoxIsTwelveHoursEverywhere() {
        for (double[] place : new double[][]{
                {BEIJING_LAT, BEIJING_LNG},
                {SYDNEY_LAT, SYDNEY_LNG},
                {0.0, 0.0},
                {64.0, -21.0}}) {
            double hours = dayLengthHours(place[0], place[1], SHANGHAI, 2026, Calendar.MARCH, 21);
            assertEquals("纬度 " + place[0] + " 的春分昼长",
                    12.0, hours, 0.5);
        }
    }

    /**
     * 没有定位权限时的兜底：纬度赤道、经度按时区反推，于是日出日落落在当地时间 6 点上下。
     *
     * <p>这是 {@code applyFallbackLocation} 存在的全部意义 —— 拿不到位置时，
     * 天空至少还跟着用户的钟表走，而不是回到写死的值上。
     */
    @Test
    public void fallbackLandsNearSixOClock() {
        DayNightResolver resolver = new DayNightResolver();
        resolver.applyFallbackLocation(SHANGHAI);

        assertEquals("兜底纬度", 0.0, resolver.getLatitude(), 1.0E-9);
        assertEquals("兜底经度 = 时区偏移 × 15°", 120.0, resolver.getLongitude(), 1.0E-9);

        SunCalculator sun = resolver.getSunCalculator();
        Calendar day = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0);
        double sunrise = sun.computeSunriseTime(SunCalculator.ZENITH_OFFICIAL, day);
        double sunset = sun.computeSunsetTime(SunCalculator.ZENITH_OFFICIAL, day);

        // 均时差全年摆动 ±16 分钟，容差给到 30 分钟
        assertEquals("兜底日出", 6.0, sunrise, 0.5);
        assertEquals("兜底日落", 18.0, sunset, 0.5);
    }

    /**
     * 换位置要生效：同一个瞬间、同一个时区，只把经度挪到地球另一侧，昼夜就该翻转。
     *
     * <p>经度差 176° ≈ 11.7 小时的地方时 —— 东经 116° 正当午时，西经 60° 是半夜。
     */
    @Test
    public void setLocationTakesEffect() {
        long instant = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();

        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        assertFalse("北京此刻是白天", resolver.isNight(instant));
        double beijingAltitude = resolver.sunAltitude(instant);

        resolver.setLocation(BEIJING_LAT, -60.0);
        assertEquals("换经度", -60.0, resolver.getLongitude(), 1.0E-9);
        assertTrue("西经 60° 此刻是夜里", resolver.isNight(instant));
        double westAltitude = resolver.sunAltitude(instant);

        assertTrue("换位置后太阳高度角应大幅改变（" + beijingAltitude + " vs " + westAltitude + "）",
                Math.abs(beijingAltitude - westAltitude) > 10.0);
    }

    /** 换时区要生效：同一瞬间换个时区，当地钟点就变了，昼夜判定跟着变。 */
    @Test
    public void setTimeZoneTakesEffect() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, TimeZone.getTimeZone("UTC"));
        long instant = at(SHANGHAI, 2026, Calendar.JUNE, 21, 4, 0).getTimeInMillis();

        // 东八区凌晨 4 点 = UTC 前一天 20 点，这时北京的太阳在地平线下
        assertTrue("UTC 视角下应是夜里", resolver.isNight(instant));

        resolver.setTimeZone(SHANGHAI);
        assertEquals("换时区", SHANGHAI.getID(), resolver.getTimeZone().getID());
        assertTrue("东八区视角下仍是夜里（凌晨 4 点）", resolver.isNight(instant));

        long shanghaiNoon = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();
        assertFalse("东八区正午不是夜里", resolver.isNight(shanghaiNoon));
    }

    // ---- 夜间权重（渲染器按它交叉淡入） ----

    /**
     * 两端必须**精确**是 0 和 1。
     *
     * <p>渲染器靠这两个端点跳过"另一套观感"那一遍绘制 —— 只要有一个像素级的不精确，
     * 白天和深夜就会白白多画一整遍场景。
     */
    @Test
    public void weightEndpointsAreExact() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        assertEquals("北京夏至正午", 0.0f,
                resolver.nightWeight(at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis()), 0.0f);
        assertEquals("北京夏至午夜", 1.0f,
                resolver.nightWeight(at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0).getTimeInMillis()), 0.0f);
    }

    /** 白天（{@code isNight} 为假）时权重恒为 0 —— 两条判据在这一点上必须一致。 */
    @Test
    public void dayIsAlwaysExactlyZero() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        for (int minute = 0; minute < 24 * 60; minute += 7) {
            Calendar c = at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0);
            c.add(Calendar.MINUTE, minute);
            long t = c.getTimeInMillis();
            if (!resolver.isNight(t)) {
                assertEquals("白天第 " + minute + " 分钟权重应为 0", 0.0f,
                        resolver.nightWeight(t), 0.0f);
            }
        }
    }

    /** 黄昏里权重单调升、且不跳变 —— 否则会看到台阶。 */
    @Test
    public void weightRisesSmoothlyIntoNight() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        float previous = -1.0f;
        for (int minute = 12 * 60; minute <= 24 * 60; minute += 2) {
            Calendar c = at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0);
            c.add(Calendar.MINUTE, minute);
            float w = resolver.nightWeight(c.getTimeInMillis());
            if (previous >= 0.0f) {
                assertTrue("权重不该回落：" + previous + " → " + w, w >= previous);
                assertTrue("权重跳变：" + previous + " → " + w, w - previous < 0.2f);
            }
            previous = w;
        }
        assertEquals("扫到午夜应是全夜", 1.0f, previous, 0.0f);
    }

    /** 半明半暗落在太阳高度角 −3° 附近（民用晨昏带的中点）。 */
    @Test
    public void halfWeightHappensNearMinusThreeDegrees() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        double closestAltitude = 0.0;
        float weightThere = -1.0f;
        for (int minute = 12 * 60; minute <= 24 * 60; minute++) {
            Calendar c = at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0);
            c.add(Calendar.MINUTE, minute);
            long t = c.getTimeInMillis();
            double altitude = resolver.sunAltitude(t);
            if (weightThere < 0.0f || Math.abs(altitude + 3.0) < Math.abs(closestAltitude + 3.0)) {
                closestAltitude = altitude;
                weightThere = resolver.nightWeight(t);
            }
        }
        assertTrue("最接近 −3° 的那一分钟高度角是 " + closestAltitude,
                Math.abs(closestAltitude + 3.0) < 0.5);
        assertEquals("−3° 处的权重应约等于 0.5", 0.5f, weightThere, 0.1f);
    }

    /** 极昼恒 0、极夜恒 1 —— 和 {@code isNight} 一样不需要给极区打特判。 */
    @Test
    public void polarWeightsAreConstant() {
        DayNightResolver svalbard = fixed(78.22, 15.65, TimeZone.getTimeZone("Europe/Oslo"));
        for (int hour = 0; hour < 24; hour++) {
            assertEquals("朗伊尔城夏至 " + hour + " 点",
                    0.0f, svalbard.nightWeight(
                            at(TimeZone.getTimeZone("Europe/Oslo"), 2026, Calendar.JUNE, 21, hour, 0)
                                    .getTimeInMillis()), 0.0f);
            assertEquals("朗伊尔城冬至 " + hour + " 点",
                    1.0f, svalbard.nightWeight(
                            at(TimeZone.getTimeZone("Europe/Oslo"), 2026, Calendar.DECEMBER, 21, hour, 0)
                                    .getTimeInMillis()), 0.0f);
        }
    }

    // ---- 预览用的压缩时钟 ----

    /** 不压缩时原样返回。 */
    @Test
    public void zeroCycleMeansRealTime() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        long real = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();
        assertEquals(real, resolver.compressedClockMs(real, 0L));
    }

    /** 本地当天零点。 */
    private static long localMidnight(TimeZone tz, long ms) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 压出来的一天仍落在本地当天之内（0 点到 24 点），不会跑到别的日子去。 */
    @Test
    public void compressedClockStaysInsideTheLocalDay() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        long base = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();
        long day = 86400000L;

        // 跨过好几个周期，覆盖取模会绕回去的各个相位
        for (long real = base; real < base + 100000L; real += 1237L) {
            long midnight = localMidnight(SHANGHAI, real);
            long compressed = resolver.compressedClockMs(real, 9000L);
            assertTrue("压缩结果 " + compressed + " 应落在 [" + midnight + ", "
                            + (midnight + day) + ")",
                    compressed >= midnight && compressed < midnight + day);
        }
    }

    /**
     * 时间轴是线性的：半天对应半天，一个周期正好覆盖一整天。
     *
     * <p>先把时刻对齐到周期起点，免得取模绕回去，测出来的是假的。
     */
    @Test
    public void compressedClockIsLinear() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        long cycle = 9000L;
        long noon = at(SHANGHAI, 2026, Calendar.JUNE, 21, 12, 0).getTimeInMillis();
        long aligned = noon - (noon % cycle);
        assertTrue("对齐后应仍在这一天的正午附近", aligned <= noon && noon - aligned < cycle);

        long start = resolver.compressedClockMs(aligned, cycle);
        assertEquals("周期起点应映到当地零点", localMidnight(SHANGHAI, aligned), start);

        assertEquals("走半圈应对应半天", 43200000L,
                resolver.compressedClockMs(aligned + cycle / 2, cycle) - start);
        assertEquals("走满一圈应回到同一点", start,
                resolver.compressedClockMs(aligned + cycle, cycle));
    }

    /** 一天的太阳高度角是连续的：逐分钟扫过去，不该出现跳变。 */
    @Test
    public void altitudeIsContinuousAcrossTheDay() {
        DayNightResolver resolver = fixed(BEIJING_LAT, BEIJING_LNG, SHANGHAI);
        double previous = resolver.sunAltitude(
                at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0).getTimeInMillis());
        for (int minute = 1; minute < 24 * 60; minute++) {
            Calendar c = at(SHANGHAI, 2026, Calendar.JUNE, 21, 0, 0);
            c.add(Calendar.MINUTE, minute);
            double current = resolver.sunAltitude(c.getTimeInMillis());
            assertTrue("第 " + minute + " 分钟跳变 " + previous + " → " + current,
                    Math.abs(current - previous) < 1.0);
            previous = current;
        }
    }
}
