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

    // 环厚度与间隙（单位：像素），原本散在 GL 类里。
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
        float size = Math.min(viewWidth, viewHeight) * 0.5f - DEFAULT_RING_THICKNESS;
        float lastRingThickness = DEFAULT_RING_THICKNESS;
        mRingCount = 0;

        if (showSeconds) {
            float angle = secondsAngle(timeMs);
            if (variableLineWidth) lastRingThickness = SMALL_RING_THICKNESS;
            addRing(RING_SECONDS, size, lastRingThickness, angle);
        }

        size -= (SMALL_GAP + lastRingThickness);
        float angleMinutes = minutesAngle(d.minute, d.second);
        if (variableLineWidth) lastRingThickness = MEDIUM_RING_THICKNESS;
        addRing(RING_MINUTES, size, lastRingThickness, angleMinutes);

        size -= (SMALL_GAP + lastRingThickness);
        float angleHours = hoursAngle(d.hour, d.minute);
        if (variableLineWidth) lastRingThickness = LARGE_RING_THICKNESS;
        addRing(RING_HOURS, size, lastRingThickness, angleHours);

        size -= (LARGE_GAP + lastRingThickness);
        float angleDays = daysAngle(d.monthDay, d.maxMonthDay);
        if (variableLineWidth) lastRingThickness = MEDIUM_RING_THICKNESS;
        addRing(RING_DAYS, size, lastRingThickness, angleDays);

        size -= (SMALL_GAP + lastRingThickness);
        float angleMonths = monthsAngle(d.month);
        if (variableLineWidth) lastRingThickness = LARGE_RING_THICKNESS;
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
