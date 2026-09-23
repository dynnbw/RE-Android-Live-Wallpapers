package com.reandroid.wallpaper.grass;

import com.reandroid.utils.MathUtils;

/**
 * 逆光的颜色：跟着天上的光源走，按光源高度角在三个锚点之间插值。
 *
 * <p>| 场合 | 透光色 | 阴影色 |
 * <p>| 正午（太阳高）| 接近白、微暖 | 中性偏冷 |
 * <p>| 黄金时刻（贴地平线）| 琥珀 | 冷影 |
 * <p>| 夜里（月亮）| 冷白偏蓝 | 更深的冷 |
 *
 * <p>锚点值在 {@link GrassConstants} 里，是上机的起点。
 *
 * <p>纯逻辑，无 GL / Android 依赖 —— 单测没开 {@code returnDefaultValues}，
 * 任何 {@code android.*} 调用都会抛 not mocked。
 */
final class GrassLightColor {

    private GrassLightColor() {
    }

    /**
     * 太阳在高空时用正午那组、贴近地平线时用琥珀那组，中间按高度角插值。
     *
     * <p>两度开始过渡而不是从 0 开始：地平线上下那一小段要保持纯粹的琥珀，
     * 否则太阳刚露头颜色就开始发白，黄金时刻最浓的那一下会被冲淡。
     */
    private static float dayMix(float sunAltitudeDeg) {
        return MathUtils.smoothStep(2.0f, GrassConstants.GRASS_LIGHT_DAY_DEG, sunAltitudeDeg);
    }

    /** 透光色 → {@code out[0..2]}。 */
    static void transmit(float sunAltitudeDeg, boolean isMoon, float[] out) {
        if (isMoon) {
            copy(GrassConstants.GRASS_LIGHT_TRANSMIT_MOON, out);
            return;
        }
        mix(GrassConstants.GRASS_LIGHT_TRANSMIT,          // 黄金时刻（琥珀）
                GrassConstants.GRASS_LIGHT_TRANSMIT_DAY,  // 正午（近白）
                dayMix(sunAltitudeDeg), out);
    }

    /** 阴影色 → {@code out[0..2]}。 */
    static void cool(float sunAltitudeDeg, boolean isMoon, float[] out) {
        if (isMoon) {
            copy(GrassConstants.GRASS_LIGHT_COOL_MOON, out);
            return;
        }
        mix(GrassConstants.GRASS_LIGHT_COOL,
                GrassConstants.GRASS_LIGHT_COOL_DAY,
                dayMix(sunAltitudeDeg), out);
    }

    private static void copy(float[] src, float[] out) {
        if (out.length < 3) {
            throw new IllegalArgumentException("数组至少要 3 个元素");
        }
        out[0] = src[0];
        out[1] = src[1];
        out[2] = src[2];
    }

    private static void mix(float[] from, float[] to, float t, float[] out) {
        if (out.length < 3) {
            throw new IllegalArgumentException("数组至少要 3 个元素");
        }
        float k = MathUtils.clamp(t, 0.0f, 1.0f);
        out[0] = from[0] + (to[0] - from[0]) * k;
        out[1] = from[1] + (to[1] - from[1]) * k;
        out[2] = from[2] + (to[2] - from[2]) * k;
    }
}
