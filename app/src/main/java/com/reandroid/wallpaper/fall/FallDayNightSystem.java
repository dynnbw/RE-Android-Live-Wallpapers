package com.reandroid.wallpaper.fall;

import com.reandroid.astronomy.DayNightResolver;
import com.reandroid.astronomy.DeviceLocation;
import com.reandroid.utils.MathUtils;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 落叶壁纸的天空时段权重：清晨 / 白日 / 黄昏 / 夜晚四条色带各占多少。
 *
 * <p>位置、时区、太阳高度角都来自共用的 {@link DayNightResolver}
 * （grass、ocean、windmill 用的是同一个），本类只负责"这四档怎么分"。
 *
 * <p><b>为什么按高度角分档，而不是照抄 grass 的"日出日落时刻 ± 分钟数"。</b>
 * 那套要先算出当天的日出、日落钟点，于是极昼极夜得单独打几个特判分支。
 * 高度角是同一件事的另一面：太阳在哪儿一目了然，极昼自然就是"整天都是白日"，
 * 不需要特判。代价是晨昏带的宽度按角度定，高纬度会拖得更久 —— 对壁纸来说这是对的，
 * 冬天本来就该暗得久一点。
 *
 * <p>权重**恒和为 1**，由水面片元着色器加权求和（见 {@code fall_water_fs.glsl}）。
 * 日夜开关关掉时调用方不喂这套，直接用黄昏那一条，画面与加这套之前逐位相同。
 */
final class FallDayNightSystem {

    /** 太阳升到这个高度角就完全是白日。 */
    private static final float DAY_FULL_DEG = 12.0f;
    /** 太阳落到这个高度角就完全是夜晚。 */
    private static final float NIGHT_FULL_DEG = -10.0f;

    /** 权重的更新间隔。高度角变化本来就慢，没必要每帧算。 */
    private static final long WEIGHT_UPDATE_INTERVAL_MS = 60000L;
    /**
     * 预览下的更新间隔：0 = 每帧都算。
     *
     * <p>预览把一天压进 30 秒，任何节流都会变成肉眼可见的台阶。
     *
     * <p>节流衡量的是 CPU 开销，所以用的是**真实时间**；传入的 {@code nowMs}
     * 是模拟时刻，天文计算该用它。
     */
    private static final long PREVIEW_WEIGHT_UPDATE_INTERVAL_MS = 0L;

    /**
     * 四档权重，顺序与 grass 的 {@code accurateWeights} 一致：
     * {@code [0]=夜, [1]=晨, [2]=昏, [3]=昼}。
     *
     * <p>初值是"只有黄昏"—— 正是关掉日夜变换时的画面。
     */
    private final float[] mWeights = {0.0f, 0.0f, 1.0f, 0.0f};

    private final DayNightResolver mResolver = new DayNightResolver();
    private final DeviceLocation mDeviceLocation = new DeviceLocation();
    /** 复用同一个 Calendar：{@code isSunRising} 只读它（内部自己 clone），不分配。 */
    private final Calendar mCalendar = Calendar.getInstance();

    /** 上一次用过的位置（引用比较，见 {@link #updateLocation()}）。 */
    private float[] mResolvedLocation;

    /**
     * 太阳在不在上升。
     *
     * <p>只在晨昏带里更新 —— 这个判据在**子夜**翻面，而那时晨昏权重恒为 0
     * （太阳在地平线下 10° 以上），所以翻面不会在画面上留下一跳。全天都问一遍是白算。
     */
    private boolean mRising = true;

    private boolean mPreview;
    private long mLastWeightUpdateMs;

    void setPreview(boolean preview) {
        mPreview = preview;
    }

    /** 预览用：把真实时刻压进一天，见 {@link DayNightResolver#compressedClockMs}。 */
    long compressedClockMs(long realMs, long cycleMs) {
        return mResolver.compressedClockMs(realMs, cycleMs);
    }

    /** 回到"只有黄昏"这一档 —— 日夜开关关掉时的画面。 */
    void resetToDusk() {
        mWeights[0] = 0.0f;
        mWeights[1] = 0.0f;
        mWeights[2] = 1.0f;
        mWeights[3] = 0.0f;
    }

    /** 按需重算四档权重：实机 60 秒一次，预览每帧。 */
    void updateWeights(long nowMs) {
        long realNowMs = System.currentTimeMillis();
        long interval = mPreview ? PREVIEW_WEIGHT_UPDATE_INTERVAL_MS : WEIGHT_UPDATE_INTERVAL_MS;
        if (mLastWeightUpdateMs != 0L && (realNowMs - mLastWeightUpdateMs) < interval) {
            return;
        }
        mLastWeightUpdateMs = realNowMs;

        updateLocation();
        mResolver.setTimeZone(TimeZone.getDefault());

        double altitude = mResolver.sunAltitude(nowMs);
        if (altitude > NIGHT_FULL_DEG && altitude < DAY_FULL_DEG) {
            mRising = mResolver.getSunCalculator().isSunRising(calendarAt(nowMs));
        }
        computeWeights(altitude, mRising, mWeights);
    }

    /** 四档权重。长度 4，顺序见 {@link #mWeights}。恒和为 1。 */
    float[] getWeights() {
        return mWeights;
    }

    /**
     * 四档权重的算式本体 —— 不碰位置、时间、IO，所以能在 JVM 上直接测。
     *
     * <p>分档不是二选一，而是两条连续的斜坡：
     * {@code a} 是"太阳爬到地平线"的进度，{@code b} 是"太阳爬到正午高度"的进度。
     * 夜 = {@code 1-a}；两者之间那段 {@code a*(1-b)} 就是晨昏；昼 = {@code a*b}。
     * 三者相加恒为 1，于是四条色带怎么插值都不会整体变亮或变暗。
     *
     * @param out 长度 4，输出 {@code [夜, 晨, 昏, 昼]}
     */
    static void computeWeights(double altitudeDeg, boolean rising, float[] out) {
        float a = MathUtils.smoothStep(NIGHT_FULL_DEG, 0.0f, (float) altitudeDeg);
        float b = MathUtils.smoothStep(0.0f, DAY_FULL_DEG, (float) altitudeDeg);
        float twilight = a * (1.0f - b);
        out[0] = 1.0f - a;
        out[1] = rising ? twilight : 0.0f;
        out[2] = rising ? 0.0f : twilight;
        out[3] = a * b;
    }

    private Calendar calendarAt(long nowMs) {
        mCalendar.setTimeZone(mResolver.getTimeZone());
        mCalendar.setTimeInMillis(nowMs);
        return mCalendar;
    }

    /**
     * 位置每 5 分钟问一次系统（{@link DeviceLocation} 内部节流）。
     *
     * <p>解析成 null 也当成一次变化：那是"调试覆盖被清除了、又没有真实定位"，
     * 该回到按时区反推的兜底，而不是继续用手填的经纬度。
     */
    private void updateLocation() {
        float[] resolved = mDeviceLocation.resolve();
        if (resolved == mResolvedLocation) {
            return;
        }
        mResolvedLocation = resolved;
        if (resolved != null) {
            mResolver.setLocation(resolved[0], resolved[1]);
        } else {
            mResolver.applyFallbackLocation(TimeZone.getDefault());
        }
    }
}
