package com.reandroid.wallpaper.polarclock;

import android.content.SharedPreferences;

/**
 * Polar Clock 场景逻辑层：环的布局（半径/厚度/间隙）与各环角度公式。
 *
 * <p>这里**不碰** {@code android.text.format.Time}，也不碰调色板 —— 前者是 Android 类，
 * 后者的实现用了 {@code android.graphics.Color} 与 XmlResourceParser，两者都会让本类
 * 无法在 JVM 上编译。所以日期字段由 GL 侧读出后传进来，颜色也仍由 GL 侧向调色板查询。
 * 换来的是：环怎么摆、角度怎么算这两件事可以单独测。
 */
final class PolarClockScene {

    // 环的下标。showSeconds=false 时秒环缺席，其余顺序不变。
    static final int RING_SECONDS = 0;
    static final int RING_MINUTES = 1;
    static final int RING_HOURS = 2;
    static final int RING_DAYS = 3;
    static final int RING_MONTHS = 4;

    // 设置键。原本挂在 PolarClockGL 上，但只有本类读它们 ——
    // 挂在 GL 上会让本类（以及它的 JVM 测试）多一个对 GL 类的编译依赖。
    static final String PREF_SHOW_SECONDS = "show_seconds";
    static final String PREF_VARIABLE_LINE_WIDTH = "variable_line_width";
    static final String PREF_PALETTE = "palette";
    static final String PREF_RING_THICKNESS = "ring_thickness";

    /**
     * 环厚档位：把「环相对钟面多粗」钉在当年某块屏的比例上，或保持原版行为。
     *
     * <p>原版环厚是写死的像素而半径随屏幕走，于是小屏上环很粗、大屏上细得只剩一圈线，
     * 两端都不是谁设定过的观感。三档各挑一块当年的屏复现它的比例，{@link #RING_THICKNESS_SCREEN}
     * 则原样保留那个"随屏幕变"的行为（默认）。
     */
    static final String RING_THICKNESS_SCREEN = "screen";
    static final String RING_THICKNESS_REF480 = "ref480";
    static final String RING_THICKNESS_REF640 = "ref640";
    static final String RING_THICKNESS_REF720 = "ref720";

    // 环厚度与间隙（单位：像素），原本散在 GL 类里。选了档位后会整体乘一个系数。
    static final float SMALL_RING_THICKNESS = 8.0f;
    static final float MEDIUM_RING_THICKNESS = 16.0f;
    static final float LARGE_RING_THICKNESS = 32.0f;
    static final float DEFAULT_RING_THICKNESS = 24.0f;
    static final float SMALL_GAP = 14.0f;
    static final float LARGE_GAP = 38.0f;

    private static final int MAX_RINGS = 5;

    boolean showSeconds = true;
    boolean variableLineWidth = true;
    String paletteId = "";
    String ringThickness = RING_THICKNESS_SCREEN;

    /**
     * 日期字段。GL 侧从 Time/Calendar 取出后填入。
     * month 为 0~11（与 Time/Calendar 一致）。
     */
    static final class DateFields {
        int minute;
        int second;
        int hour;
        int monthDay;
        int maxMonthDay;
        int month;
    }

    /** 一个待绘制的环。数组由 {@link #layout} 复用，**不要跨帧保留**。 */
    static final class Ring {
        int id;
        float radius;
        float thickness;
        float angle;
    }

    private final DateFields mDateFields = new DateFields();
    private final Ring[] mRings = new Ring[MAX_RINGS];
    private int mRingCount;

    PolarClockScene() {
        for (int i = 0; i < MAX_RINGS; i++) {
            mRings[i] = new Ring();
        }
    }

    void setPluginPrefs(SharedPreferences prefs) {
        if (prefs == null) return;
        showSeconds = prefs.getBoolean(PREF_SHOW_SECONDS, true);
        variableLineWidth = prefs.getBoolean(PREF_VARIABLE_LINE_WIDTH, true);
        paletteId = prefs.getString(PREF_PALETTE, "");
        ringThickness = prefs.getString(PREF_RING_THICKNESS, RING_THICKNESS_SCREEN);
    }

    /** 档位对应的参考屏宽；{@code 0} 表示不锁定（随屏）。 */
    static int referenceWidthFor(String value) {
        if (RING_THICKNESS_REF480.equals(value)) return 480;
        if (RING_THICKNESS_REF640.equals(value)) return 640;
        if (RING_THICKNESS_REF720.equals(value)) return 720;
        return 0;
    }

    /**
     * 整份布局的缩放系数：把参考屏那年的布局等比放大到当前屏。
     *
     * <p>取 {@code 当前 min(宽,高) / 参考宽} 而不是按外圈半径之比，是因为外圈半径
     * （{@code min/2 - 24}）本身带着那个 24 的内缩，两个比例并不相等；只有整体等比
     * 缩放，环厚 ÷ 外圈半径才会严格等于参考屏那一档。代入可见：
     * {@code 外圈半径 = k * (参考宽/2 - 24)}，与参考屏只差一个 k。
     *
     * <p>{@link #RING_THICKNESS_SCREEN} 与认不出的取值一律返回 1，即原版行为。
     */
    static float ringScaleFor(String value, float minDimension) {
        int refWidth = referenceWidthFor(value);
        if (refWidth <= 0 || minDimension <= 0.0f) return 1.0f;
        return minDimension / refWidth;
    }

    /** 复用的日期字段容器，GL 侧填完再交给 layout。 */
    DateFields dateFields() {
        return mDateFields;
    }

    int ringCount() {
        return mRingCount;
    }

    Ring ringAt(int i) {
        return mRings[i];
    }

    /**
     * 算出这一帧要画的环（半径、厚度、角度）。
     *
     * <p>顺序与累加方式与原实现逐一对应：第一个环直接用初始半径，
     * 之后每个环先减去「上环厚度 + 间隙」再画；厚度在开启可变线宽时才更新。
     * <b>showSeconds=false 时秒环整块跳过，于是 lastRingThickness 保持默认值</b>，
     * 分环的半径因此与开启时不同 —— 这是原行为，别"顺手修正"。
     *
     * @return 内部复用的数组，长度见 {@link #ringCount()}
     */
    Ring[] layout(float viewWidth, float viewHeight, long timeMs) {
        DateFields d = mDateFields;
        /*
         * 档位只在这里生效：厚度与间隙一起乘 k，整份布局成为参考屏那份的等比副本
         * （间隙跟着缩是必须的，否则环会互相压住）。k=1 时下面七个数与常量逐位相同，
         * 所以默认档不可能带来任何回归。
         */
        float minDimension = Math.min(viewWidth, viewHeight);
        float k = ringScaleFor(ringThickness, minDimension);
        float defaultThickness = DEFAULT_RING_THICKNESS * k;
        float smallThickness = SMALL_RING_THICKNESS * k;
        float mediumThickness = MEDIUM_RING_THICKNESS * k;
        float largeThickness = LARGE_RING_THICKNESS * k;
        float smallGap = SMALL_GAP * k;
        float largeGap = LARGE_GAP * k;

        float size = minDimension * 0.5f - defaultThickness;
        float lastRingThickness = defaultThickness;
        mRingCount = 0;

        if (showSeconds) {
            float angle = secondsAngle(timeMs);
            if (variableLineWidth) lastRingThickness = smallThickness;
            addRing(RING_SECONDS, size, lastRingThickness, angle);
        }

        size -= (smallGap + lastRingThickness);
        float angleMinutes = minutesAngle(d.minute, d.second);
        if (variableLineWidth) lastRingThickness = mediumThickness;
        addRing(RING_MINUTES, size, lastRingThickness, angleMinutes);

        size -= (smallGap + lastRingThickness);
        float angleHours = hoursAngle(d.hour, d.minute);
        if (variableLineWidth) lastRingThickness = largeThickness;
        addRing(RING_HOURS, size, lastRingThickness, angleHours);

        size -= (largeGap + lastRingThickness);
        float angleDays = daysAngle(d.monthDay, d.maxMonthDay);
        if (variableLineWidth) lastRingThickness = mediumThickness;
        addRing(RING_DAYS, size, lastRingThickness, angleDays);

        size -= (smallGap + lastRingThickness);
        float angleMonths = monthsAngle(d.month);
        if (variableLineWidth) lastRingThickness = largeThickness;
        addRing(RING_MONTHS, size, lastRingThickness, angleMonths);

        return mRings;
    }

    private void addRing(int id, float radius, float thickness, float angle) {
        Ring r = mRings[mRingCount++];
        r.id = id;
        r.radius = radius;
        r.thickness = thickness;
        r.angle = angle;
    }

    /** 秒环：一分钟走一圈，直接用毫秒取余，不看日历字段。 */
    static float secondsAngle(long timeMs) {
        return (float) (timeMs % 60000L) / 60000.0f;
    }

    /** 分环：一小时走一圈。 */
    static float minutesAngle(int minute, int second) {
        return ((minute * 60.0f + second) % 3600) / 3600.0f;
    }

    /** 时环：一天走一圈，12 小时制无关（用的是 0~23 的 hour）。 */
    static float hoursAngle(int hour, int minute) {
        return ((hour * 60.0f + minute) % 1440) / 1440.0f;
    }

    /** 日环：当月第几天映射到 0~1。 */
    static float daysAngle(int monthDay, int maxMonthDay) {
        return (monthDay - 1) / (float) (maxMonthDay - 1);
    }

    /** 月环：0~11 映射到 0~1（注意分母是 11，不是 12）。 */
    static float monthsAngle(int month) {
        return (month) / 11.0f;
    }
}
