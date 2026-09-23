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
     * 天空发光体的权重。
     *
     * <p><b>只在"真正的白天"出现，清晨与黄昏都不给。</b> 直接取白日那一档、
     * 并要求它几乎满格才点亮 —— 太阳是白日的太阳，天边刚泛红的时候天上没有它。
     *
     * <p>判据挂在**白日权重**上而不是太阳高度角上，是有意的：这样它跟着
     * {@link #computeWeights} 里那条 {@code DAY_FULL_DEG} 一起走。哪天觉得
     * "白日来得太早"把那条调宽，发光体出现的时刻会自动跟着挪，
     * 不会出现"天空已经是一片白日了、太阳却还没出来"这种两套时钟打架的情况。
     *
     * <p>两端仍然是平滑的（两个阈值之间过渡），不是硬开关 —— 硬切会在日出那一刻整帧跳一下。
     */
    private static final float EMITTER_DAY_LO = 0.72f;
    private static final float EMITTER_DAY_HI = 1.0f;

    float emitterWeight() {
        return computeEmitterWeight(mWeights);
    }

    /**
     * 夜空星星的可见度。
     *
     * <p>夜那一档过半才开始出现，接近全黑才满 —— 天没黑透时星星不该出来。
     * 换算成太阳高度角大约是 −5.6° 到 −8.7°，正是民用暮光快结束的那一段。
     */
    private static final float STAR_NIGHT_LO = 0.55f;
    private static final float STAR_NIGHT_HI = 0.95f;

    float starAmount() {
        return computeStarAmount(mWeights);
    }

    /** {@link #starAmount()} 的算式本体，便于在 JVM 上直接测。 */
    static float computeStarAmount(float[] weights) {
        return MathUtils.smoothStep(STAR_NIGHT_LO, STAR_NIGHT_HI, weights[0]);
    }

    /**
     * 蓝藻生物光的可见度。
     *
     * <p>比星星**早得多**就出现：太阳刚落、天边还亮着的时候，被搅动的水就该有反应，
     * 而不是要等到满天星。这两条曲线刻意分开。
     */
    private static final float ALGAE_NIGHT_LO = 0.18f;
    private static final float ALGAE_NIGHT_HI = 0.70f;

    float algaeAmount() {
        return computeAlgaeAmount(mWeights);
    }

    /** {@link #algaeAmount()} 的算式本体，便于在 JVM 上直接测。 */
    static float computeAlgaeAmount(float[] weights) {
        return MathUtils.smoothStep(ALGAE_NIGHT_LO, ALGAE_NIGHT_HI, weights[0]);
    }

    /** {@link #emitterWeight()} 的算式本体，便于在 JVM 上直接测。 */
    static float computeEmitterWeight(float[] weights) {
        return MathUtils.smoothStep(EMITTER_DAY_LO, EMITTER_DAY_HI, weights[3]);
    }

    /**
     * 落叶的时段染色锚点，顺序同 {@link #mWeights}：{@code [夜, 晨, 昏, 昼]}。
     *
     * <p>颜色是**插值目标**而不是乘数 —— 枫叶是橙红的，蓝通道本来就低，
     * 乘一个偏蓝的颜色只会把它压灰，永远到不了"偏蓝白"（见 {@code fall_fs.glsl}）。
     *
     * <p>晨与昏刻意保持接近中性、量也小：那两个时段枫叶本该是暖色的，
     * 原版观感就是这样，不该被这套染色洗掉。
     */
    private static final float[][] LEAF_TINT = {
            {0.10f, 0.16f, 0.38f},   // 夜：深蓝
            {0.72f, 0.70f, 0.70f},   // 晨：近中性
            {0.88f, 0.74f, 0.58f},   // 昏：暖
            {0.80f, 0.88f, 1.00f},   // 昼：蓝白
    };
    /**
     * 各档染色的强度，与 {@link #LEAF_TINT} 同序。
     *
     * <p>这组数是**上机往回调过两轮**的：第一版 0.50/0.55、第二版 0.26/0.42，
     * 都还是蓝得太多。叶子还是叶子，染色只该是"受当时天光影响"的一层薄薄的偏移。
     *
     * <p>夜里那一档也不靠它变暗 —— 见 {@link #LEAF_VALUE}。
     */
    private static final float[] LEAF_TINT_AMOUNT = {0.25f, 0.06f, 0.08f, 0.10f};

    /**
     * 各档的明度缩放，与 {@link #LEAF_TINT} 同序（1 = 原样）。
     *
     * <p>夜里"叶子变暗"就是这一项：直接乘，压的是明度、不动对比。
     * 想靠往深蓝里混来变暗是行不通的 —— 混得越多，叶子的明暗层次被压得越平，
     * 最后只剩一块糊掉的色斑，而不是"暗下来的叶子"。
     *
     * <p>晨昏接近 1：那两个时段本来就该是暖的、亮的。
     */
    private static final float[] LEAF_VALUE = {0.42f, 0.92f, 0.95f, 1.00f};

    /**
     * 按四档权重混出落叶的染色目标色。权重恒和为 1，所以这是一次凸组合，
     * 结果必定落在四个锚点围成的范围内。
     *
     * @param out 长度 3，写进混好的颜色
     * @return 混好的染色强度，直接拿去设 {@code uTintAmount}
     */
    static float computeLeafTint(float[] weights, float[] out) {
        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        float amount = 0.0f;
        for (int i = 0; i < weights.length; i++) {
            r += weights[i] * LEAF_TINT[i][0];
            g += weights[i] * LEAF_TINT[i][1];
            b += weights[i] * LEAF_TINT[i][2];
            amount += weights[i] * LEAF_TINT_AMOUNT[i];
        }
        out[0] = r;
        out[1] = g;
        out[2] = b;
        return amount;
    }

    /**
     * 按四档权重混出落叶的明度缩放，直接拿去设 {@code uValue}。
     *
     * <p>与染色分开：染色管"偏什么颜色"，这一项管"暗下去多少"（见 {@link #LEAF_VALUE}）。
     */
    static float computeLeafValue(float[] weights) {
        float value = 0.0f;
        for (int i = 0; i < weights.length; i++) {
            value += weights[i] * LEAF_VALUE[i];
        }
        return value;
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
