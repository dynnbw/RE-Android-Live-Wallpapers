package com.reandroid.astronomy;

/**
 * 在手填的覆盖与真实定位之间取舍 —— 纯状态机，不碰 Android。
 *
 * <p>{@link DeviceLocation} 只剩下"怎么问系统要位置"，取舍规则全在这里，于是
 * "清除覆盖要立刻生效"这类规则能在 JVM 上直接测。
 *
 * <p>曾经这两件事是搅在一起的，代价是个真实的 bug：清除覆盖之后，
 * 被 5 分钟节流挡住的查询让调用方继续拿到那份手填的经纬度。
 *
 * <p>另一条约定是<b>引用即结果</b>：{@link #resolved()} 在值没变时返回同一个数组实例，
 * 调用方（{@code WeatherStateManager}、{@code GrassDayNightSystem}）靠它判断
 * "位置变没变"，免得每帧去重建太阳计算器。null 也是一次变化，不能当成"没事发生"。
 */
final class LocationArbiter {

    /** 设置页里手填的经纬度；null 表示没有覆盖。 */
    private float[] mOverride;
    /** 最近一次成功读到的真实定位；从没读到就是 null。 */
    private float[] mDevice;
    /** 当前对外发布的结果。 */
    private float[] mResolved;

    /**
     * 更新覆盖位置。null 表示清除。
     *
     * @return 这次是否真的改变了覆盖（设上、改值、清除都算改变）
     */
    boolean setOverride(float[] override) {
        boolean unchanged = (override == null) == (mOverride == null)
                && (override == null || (mOverride[0] == override[0] && mOverride[1] == override[1]));
        if (unchanged) {
            return false;
        }
        mOverride = override == null ? null : new float[]{override[0], override[1]};
        return true;
    }

    /** 更新真实定位。null 表示这次没读到 —— 保留上一次的，不把已有位置丢掉。 */
    void setDevice(float[] device) {
        if (device != null) {
            mDevice = device;
        }
    }

    /** 有覆盖时就不必去问系统了。 */
    boolean hasOverride() {
        return mOverride != null;
    }

    /** 当前有效位置；两个来源都没有就是 null。 */
    float[] resolved() {
        float[] best = mOverride != null ? mOverride : mDevice;
        if (mResolved != null && best != null
                && mResolved[0] == best[0] && mResolved[1] == best[1]) {
            return mResolved;
        }
        mResolved = best;
        return mResolved;
    }
}
