package com.reandroid.wallpaper.grass;

/**
 * 屏幕空间雨丝的层表。
 *
 * <p>参考实现（{@code rain_screen_vertex_shader.glsl}）把这张表放在**顶点着色器**里算 ——
 * 对全屏四边形来说等于每帧算四次、结果还完全一样。这里提到 Java 侧：一次算好、
 * 当 uniform 数组传过去，顺带变成可 JVM 测试的纯函数。
 *
 * <p>每层是一圈不同粗细/浓淡的雨丝：索引越大越**细**、越**淡**、轨道越密，
 * 叠在一起就有了"近处粗亮、远处细密"的层次。
 */
final class GrassRainStreakLayers {

    /** 与着色器里的 {@code uLayerAlpha[5]} / {@code uLayerScale[5]} 一致。 */
    static final int MAX_LAYERS = 5;

    /**
     * 默认启用几层。
     *
     * <p>参考实现用 {@code uFilter} 控制。每层都是对着全屏逐像素算一遍，
     * 而这是动态壁纸 —— 取一个偏保守的值，上机按观感和帧率调。
     */
    static final int DEFAULT_FILTER = 3;

    private GrassRainStreakLayers() {
    }

    /**
     * 填出各层的透明度与粗细。
     *
     * @param intensity 0..5，见 {@link GrassWeatherSystem#rainIntensity}
     * @param filter    启用前几层；{@code <= 0} 表示一层都不要
     * @param outAlpha  长度至少 {@link #MAX_LAYERS}
     * @param outScale  长度至少 {@link #MAX_LAYERS}；未启用的层写 0，着色器据此跳过
     */
    static void fill(float intensity, int filter, float[] outAlpha, float[] outScale) {
        if (outAlpha.length < MAX_LAYERS || outScale.length < MAX_LAYERS) {
            throw new IllegalArgumentException("数组至少要 " + MAX_LAYERS + " 个元素");
        }
        float t = clamp01(intensity / 5.0f);
        int enabled = Math.min(filter, MAX_LAYERS);
        for (int i = 0; i < MAX_LAYERS; i++) {
            if (i >= enabled) {
                outAlpha[i] = 0.0f;
                outScale[i] = 0.0f;
                continue;
            }
            // 参考实现：alpha = 0.35 - i * mix(0.10, 0.05, intensity/5)
            outAlpha[i] = 0.35f - i * mix(0.10f, 0.05f, t);
            // 参考实现：scale = 2.5 + i * 1.5
            outScale[i] = 2.5f + i * 1.5f;
        }
    }

    /** 整体不透明度：强度 0 时整层不画。 */
    static float opacity(float intensity) {
        return clamp01(intensity / 5.0f);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
