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
    /**
     * 太阳各特效层的强度。
     *
     * <p>移植的对象是天气应用**"三日卡片"那个展示场景**的太阳（射线扇 + 镜头光环 +
     * 彩色光斑 + 环晕）。它比 grass 需要的要亮：主页面那颗太阳要收敛得多。
     *
     * <p><b>所以这些强度一律取参考实现的一半，但层数与条数一个不动。</b>
     * 是"调暗"而不是"减少" —— 射线还是那 20 条、光斑还是那 4~8 个，只是每条更淡。
     * 上机觉得还亮就继续往下调这几个数。
     */

    /** 彩色圆斑强度（参考实现是 1.0）。 */
    static final float SUN_CIRCLE_ALPHA = 0.5f;
    static final float SUN_CIRCLE_OFFSET = 0.35f;
    static final float SUN_CIRCLE_OFFSET_RATIO = 0.5f;
    /** 透视环晕强度（参考实现是 0.25，还受 {@link #SUN_22_OPEN} 门控）。 */
    static final float SUN_ANNULUS_ALPHA = 0.125f;
    /** 静态射线扇的整体强度（参考实现是 1.0）。 */
    static final float SUN_RAY_ALPHA = 0.5f;
    /**
     * 动态射线（三相位那三条弱光线）的强度。
     *
     * <p>参考实现里这一项是**硬编码的 1.2**，没有做成 uniform。这里提出来是因为它和射线扇
     * 是两套独立的射线 —— 只调射线扇的话，画面上仍然会剩一层没动的射线。
     */
    static final float SUN_DYNAMIC_RAY_ALPHA = 0.6f;
    /**
     * 成对光环的亮度（参考实现里是硬编码的 0.6）。
     *
     * <p>同理：不把它提出来，"强度减半"就漏掉了这一层。
     */
    static final float SUN_FLARE_BRIGHTNESS = 0.3f;
    /** &gt; 0 才绘制彩色圆斑与镜头光环那一整块。**保持开启** —— 要减的是强度不是数量。 */
    static final float SUN_QUALITY = 1.0f;
    /** &gt; 0.5 才绘制环晕与成对光环。同样保持开启。 */
    static final float SUN_22_OPEN = 1.0f;

    /**
     * 屏幕空间雨丝的调参。
     *
     * <p>参考实现（{@code rain_screen_fragment_shader.glsl}）里这几个是 uniform，
     * 取值在混淆过的 Java 里**没能提取出来**。现值是上机的起点，不是标准答案。
     */
    /** 竖轨道数（参考实现的 SCALE_X）。越大雨丝越细越密。 */
    static final float RAIN_TRACK_COUNT = 20.0f;
    /** 下落速度。 */
    static final float RAIN_SPEED_Y = 1.0f;
    /** 最靠近镜头那一层的透明度与粗细（参考实现里单独处理的那层）。 */
    static final float RAIN_BASE_ALPHA = 0.8f;
    static final float RAIN_BASE_SCALE = 1.0f;

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
