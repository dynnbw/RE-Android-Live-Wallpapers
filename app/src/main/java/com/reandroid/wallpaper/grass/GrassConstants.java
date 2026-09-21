package com.reandroid.wallpaper.grass;

final class GrassConstants {
    private GrassConstants() {
    }

    static final int DEFAULT_BLADE_COUNT = 200;
    static final float TESSELATION = 0.5f;
    static final float HALF_TESSELATION = 0.25f;
    static final float MAX_BEND = 0.09f;
    static final float SECONDS_IN_DAY = 86400.0f;
    static final float SOLAR_MEAN_ANGULAR_RADIUS_DEG = 0.2665f;
    static final float LUNAR_MEAN_ANGULAR_RADIUS_DEG = 0.2727f;
    static final float SOLAR_ECLIPSE_MODEL_TOLERANCE_DEG = 0.75f;
    static final float SUN_PHOTOSPHERE_SCALE = 0.88f;

    /**
     * 程序化太阳的调参。
     *
     * <p>参考实现（参考实现 {@code sun_fragment_shader.glsl}）里这几个是 uniform，
     * 取值在混淆过的 Java 里，**没能提取出来**。这里按本项目既有惯例
     * （{@link #SUN_PHOTOSPHERE_SCALE} 也是这么放的）收成常量，上机按肉眼调。
     */
    static final float SUN_CIRCLE_ALPHA = 1.0f;
    static final float SUN_CIRCLE_OFFSET = 0.35f;
    static final float SUN_CIRCLE_OFFSET_RATIO = 0.5f;
    static final float SUN_ANNULUS_ALPHA = 0.25f;
    /** 射线扇的整体强度。参考实现里这一项是常量 1.0（{@code color += obviousLine * fan}）。 */
    static final float SUN_RAY_ALPHA = 1.0f;
    /** > 0 才绘制彩色圆斑。 */
    static final float SUN_QUALITY = 1.0f;
    /** > 0.5 才绘制环晕与成对光环。 */
    static final float SUN_22_OPEN = 1.0f;

    static final int LEGACY_MAX_NORMAL = 10;
    static final int LEGACY_MAX_EXTRAS = 50;
    static final float LEGACY_SPEED = 0.1f;
    static final float LEGACY_SPEED_VARIANCE = 0.3f;
    static final float LEGACY_VERTICAL_MOTION_SCALE = 1.3f;
    static final int LEGACY_VECTOR_MIN_INTERVAL_MS = 800;
    static final int LEGACY_VECTOR_MAX_INTERVAL_MS = 1200;
    static final int LEGACY_MAX_DELAY = 5000;
    static final int LEGACY_MAX_STAY = 5000;
    static final int LEGACY_MAX_FLARE = 1000;
    static final int LEGACY_MAX_INTERVAL = 5000;
    static final float LEGACY_INTERVAL_VARIANCE = 0.3f;
    static final int LEGACY_MAX_BLOW_INTERVAL = 180000;
    static final int LEGACY_TYPE_DANDELION = 0;
    static final int LEGACY_TYPE_FIREFLY = 1;

    static final int DEFAULT_DANDELION_COUNT = 10;
    static final int DEFAULT_FIREFLY_COUNT = 16;
    static final float DANDELION_SIZE_SCALE = 2.2f;
    static final float FIREFLY_SIZE_SCALE = 6.0f;
    static final float DANDELION_SPEED_SCALE = 1.6f;
}
