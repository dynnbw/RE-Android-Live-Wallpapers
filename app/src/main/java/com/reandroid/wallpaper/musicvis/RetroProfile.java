package com.reandroid.wallpaper.musicvis;

/**
 * vis3 的冷光外溢参数。
 *
 * <p>只有这一套：数值在这里，{@link com.reandroid.gles.GlowRenderer} 拿它做外溢。
 * 强度按真机调过，不再拆成档位。
 */
public final class RetroProfile {

    public static final int STYLE_NONE = 0;
    public static final int STYLE_COLD_GLOW = 1;

    /** 三个公共着色器：全屏四边形、亮部提取、可分离模糊。 */
    public static final String SHADER_QUAD = "musicvis/shaders/GLES/glow_quad_vs.glsl";

    public static final String SHADER_BRIGHT = "musicvis/shaders/GLES/glow_bright_fs.glsl";
    public static final String SHADER_BLUR = "musicvis/shaders/GLES/glow_blur_fs.glsl";

    private static final RetroProfile NONE = new RetroProfile("", "", 0f, 0f, 0f, 0f);
    private static final RetroProfile COLD_GLOW = new RetroProfile(
            "vis3_retro", "musicvis/shaders/GLES/vis3_bloom_fs.glsl", 0.0f, 0.30f, 4.6f, 4.20f);

    /** 亮部提取与外溢参数。 */
    public final float threshold, softKnee, radius, strength;

    public final String settingsKey;
    public final String compositeShader;

    private RetroProfile(
            String settingsKey,
            String compositeShader,
            float threshold,
            float softKnee,
            float radius,
            float strength) {
        this.settingsKey = settingsKey;
        this.compositeShader = compositeShader;
        this.threshold = threshold;
        this.softKnee = softKnee;
        this.radius = radius;
        this.strength = strength;
    }

    public static RetroProfile of(int style) {
        return style == STYLE_COLD_GLOW ? COLD_GLOW : NONE;
    }

    public boolean isNone() {
        return this == NONE;
    }
}
