package com.reandroid.wallpaper.grass;

/**
 * 太阳本体**随高度角变化的外观**：颜色与不透明度。
 *
 * <p>原来的实现是恒定乘 {@code vec3(1.25, 1.61, 1.84)} —— 蓝 &gt; 绿 &gt; 红，所以**永远是白偏蓝**。
 * 那个值来自参考实现，是给"太阳高挂"的卡片场景定的，与高度角无关。
 *
 * <p>但真实的太阳在低空是**橙红**的：阳光斜穿大气时路径变长，瑞利散射把蓝光散掉、
 * 只剩长波透过来。高度角越低越红，到地平线附近就是一轮红日。
 *
 * <p>所以这里按高度角在两个色调之间插值。高空保留参考实现的原值（那颗太阳是对的），
 * 低空换成橙红。
 *
 * <p>纯函数，可 JVM 测试。
 */
final class GrassSunAppearance {

    private GrassSunAppearance() {
    }

    /** 高空色调：参考实现的原值（白偏蓝）。 */
    static final float HIGH_R = 1.25f;
    static final float HIGH_G = 1.61f;
    static final float HIGH_B = 1.84f;

    /**
     * 地平线附近的色调：橙红。
     *
     * <p>绿蓝压得比"看起来够"还要狠一些，是因为旁边那圈 plus 辉光是个**固定的暖色**
     * （{@code 0.851/0.604/0.349}），它会把红冲淡。按中心点实际合成出来的 RGB 反推，
     * 取到 (2.40, 0.45, 0.12) 才能让日落地平线上的太阳落在 (255,130,60) 这一带 ——
     * 偏绿一点就退回琥珀色了。
     */
    static final float LOW_R = 2.40f;
    static final float LOW_G = 0.45f;
    static final float LOW_B = 0.12f;

    /** 低于这个高度角就是全橙红。 */
    static final float LOW_DEG = 0.0f;
    /**
     * 高于这个高度角就是全白偏蓝（也就是参考实现的原值，高空一个像素都不动）。
     *
     * <p>取 25 而不是 18：18 的时候太阳到 15° 就已经基本全白，橙色窗口只剩 0~10°，
     * 而实际上一轮太阳在 20° 上下仍然偏暖。
     */
    static final float HIGH_DEG = 25.0f;

    /** 地平线以上一律实心；淡出只发生在地平线以下这么多度之内。 */
    static final float FADE_BELOW_DEG = 4.0f;

    /**
     * 太阳本体的不透明度。
     *
     * <p>原来的式子（在 {@code GrassScene} 里）是 {@code clamp((alt + 6) / 12, 0, 1)} ——
     * **地平线处只有 0.5**，于是太阳在低空一路是半透明的。加法混合下 alpha 0.5 就是只贡献
     * 一半亮度，半透明的橙盘叠在亮蓝天空上看着像"化开了"，而不是一轮实心的红日。
     *
     * <p>淡出的职责只是"别在沉下去那一瞬硬切"，不是把一颗已经看得见的太阳调暗。
     * 所以地平线（0°）以上一律 1.0，只在地平线以下 {@link #FADE_BELOW_DEG} 度内淡出 ——
     * 那一段太阳本来就已经滑到屏幕下缘、被草叶挡住了。
     *
     * @param altitudeDeg 太阳高度角（度）
     */
    static float opacity(float altitudeDeg) {
        return smoothstep(-FADE_BELOW_DEG, 0.0f, altitudeDeg);
    }

    /**
     * 填出当前高度角对应的 RGB 增益。
     *
     * @param altitudeDeg 太阳高度角（度），见 {@code SceneData.lastSunAltitude}
     * @param out         长度至少 3
     */
    static void fill(float altitudeDeg, float[] out) {
        if (out.length < 3) {
            throw new IllegalArgumentException("数组至少要 3 个元素");
        }
        float t = smoothstep(LOW_DEG, HIGH_DEG, altitudeDeg);
        out[0] = mix(LOW_R, HIGH_R, t);
        out[1] = mix(LOW_G, HIGH_G, t);
        out[2] = mix(LOW_B, HIGH_B, t);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) {
            return x < edge0 ? 0.0f : 1.0f;
        }
        float t = (x - edge0) / (edge1 - edge0);
        t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        return t * t * (3.0f - 2.0f * t);
    }
}
