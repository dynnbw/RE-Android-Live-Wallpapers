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
     * <p>参考实现（{@code sun_fragment_shader.glsl}）里这几个是 uniform，
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

    /**
     * 太阳本体（光盘 + plus 辉光）的增益。**默认 1.0 = 参考实现的原值，不作任何缩放。**
     *
     * <p>调这个的前提：太阳本体那两行（{@code circleTex(uSunRamp, …) * vec3(uR,uG,uB) * 1.02}
     * 与那个 {@code glow}）里的系数**全是字面量**，不受下面任何特效强度影响 ——
     * 所以"本体保留原值"本身就是成立的，这里给 1.0。
     *
     * <p>但本体在中心处的合计只是<b>刚好压在饱和线上</b>（≈1.00），紧挨着的一圈（约 0.94）
     * 原来是靠光斑与射线补足才显得白得实。特效减半之后那一圈会没那么"实"——
     * 如果这看着像本体变弱了，把这里调上去（1.1~1.3）比去动特效强度更对症。
     */
    static final float SUN_CORE_GAIN = 1.0f;

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

    // ---- 草叶逆光（第二版）----
    //
    // 与第一版的分水岭：第一版只有「屏幕位置」和「轮廓线」，于是做成了一层与形体无关的
    // 径向覆盖 —— 同一距离上每片叶拿到的值数学上必然相等，整片草一起变金。
    // 这一版每片叶的受光由 GrassBladeLighting 算好，片元里**没有光源位置**，
    // 只消费「横截面位置 / 叶尖位置 / beam」三个标量。
    //
    // 取值是上机的起点，不是标准答案 —— 观感不对就调这里。

    /**
     * 可见度半径（像素）：离光源超过它就不再受光，**半径处恰好为 0**。
     *
     * <p>黄金时刻太阳总在屏幕上缘附近（{@code sunY = height * (1 - alt/90)}，alt=12° 时
     * 已到 y≈2080），所以这个值大致等于"从屏幕底边往上铺多远"。
     */
    static final float GRASS_LIGHT_RADIUS = 1400.0f;
    /** 横截面法线扫过的张角（弧度）。越大，迎光边越"卷"。 */
    static final float GRASS_LIGHT_CROSS_ANGLE = 2.0f;
    /** 叶根（厚）的明暗系数。 */
    static final float GRASS_LIGHT_HEIGHT_DIM = 0.55f;
    /** 叶尖（薄）的明暗系数。 */
    static final float GRASS_LIGHT_HEIGHT_GAIN = 1.15f;
    /**
     * 透过的光的颜色。
     *
     * <p><b>这不是乘数，是"光穿过叶肉之后是什么颜色"。</b> 原先写成乘数
     * （{@code color × 暖金}），那只是给绿草染色 —— 乘数永远保留着叶色，所以再亮也读不出
     * "光是穿透过来的"。物理上透射光是「光的颜色 × 叶肉的透射率」，与叶片自身的反射色无关，
     * 于是改成往这个常量插值。实测这一改让中间档从 +13% 跳到 +50%。
     *
     * <p>数值可以大于 1（那是"比白还亮"的透射光）。红比绿高是刻意的 —— 逆光的草偏琥珀。
     */
    static final float[] GRASS_LIGHT_TRANSMIT = {1.35f, 1.00f, 0.40f};

    // ---- 草叶逆光的颜色（按天上的光源插值，见 GrassLightColor）----
    //
    // 三个锚点：正午（太阳高）、黄金时刻（贴地平线）、月亮。
    // 黄金时刻那一组就是原来那套 —— 保住了既有观感。

    /** 正午的透光色：接近白，只带一点暖。太阳在头顶时"逆光"本来就弱。 */
    static final float[] GRASS_LIGHT_TRANSMIT_DAY = {1.15f, 1.10f, 0.95f};
    /** 月光的透光色：**冷白偏蓝**（B > G > R）。 */
    static final float[] GRASS_LIGHT_TRANSMIT_MOON = {0.70f, 0.85f, 1.20f};
    /** 正午的阴影色：中性偏冷一点。 */
    static final float[] GRASS_LIGHT_COOL_DAY = {0.80f, 0.84f, 0.92f};
    /** 月光的阴影色：更深的冷。 */
    static final float[] GRASS_LIGHT_COOL_MOON = {0.58f, 0.66f, 0.88f};
    /**
     * 太阳色从"黄金时刻"过渡到"正午"的高度角区间（度）。
     *
     * <p>地平线附近是琥珀，太阳升到 {@code 30°} 左右就变成接近白的暖色。
     */
    static final float GRASS_LIGHT_DAY_DEG = 30.0f;
    /**
     * 冷影。**乘**上去 —— 暗部偏冷，而不是把原来的绿调暗。
     *
     * <p>只把亮部染暖、暗部不动，读起来就像"同一种颜料被调亮调暗"，是插画里最典型的塑料感来源。
     */
    static final float[] GRASS_LIGHT_COOL = {0.62f, 0.70f, 0.86f};
    /** 迎光边暖白。是**加**上去的 —— 乘法到不了那个亮度。 */
    static final float[] GRASS_LIGHT_RIM = {1.00f, 0.94f, 0.78f};
    /** 迎光边增益。 */
    static final float GRASS_LIGHT_RIM_GAIN = 1.0f;
    /**
     * 压暗幅度。设 0 就退化成"只加光不压暗"，也就是第一版的行为。
     *
     * <p>逆光本来就有一半是剪影 —— 只加光不减去光，画面就会像罩了一层发光滤镜。
     */
    static final float GRASS_LIGHT_SHADOW_GAIN = 0.45f;

    // ---- HDR + 辉光 ----
    //
    // 阈值定得偏高：只让草叶受光那一档和月亮发光，星星、萤火虫这类小亮点不该发。
    // 取值是上机的起点，不是标准答案 —— 观感不对就调这里。

    /** 亮度阈值。低于它的不发辉光。 */
    static final float GLOW_THRESHOLD = 0.85f;
    /** 阈值之上的过渡宽度。硬阈值会在光晕边缘留下可见的台阶。 */
    static final float GLOW_SOFT_KNEE = 0.25f;
    /** 模糊半径（像素，半分辨率下）。光晕发硬/发窄先调它，不是加遍数。 */
    static final float GLOW_RADIUS = 8.0f;
    /** 辉光叠加强度。 */
    static final float GLOW_STRENGTH = 0.85f;

    /**
     * 月亮那层**假光**的射线强度。
     *
     * <p>做法是复用太阳的着色器：{@code uCoreGain = 0} 精确剥掉太阳本体
     * （光盘 + 本体辉光正好是乘这个系数的两行），**月亮本体一个像素不变**。
     *
     * <p>其余各层按上机观感取舍：
     * <ul>
     *   <li>{@code uQuality = 0} —— 关掉彩色光斑与镜头光环</li>
     *   <li>{@code u22Open = 0} —— 关掉透视环晕</li>
     *   <li>只留静态射线扇 + 动态射线，并把强度降到下面这个值</li>
     * </ul>
     * 那几层"彩环"的几何与配色都是**按屏幕尺度写死**的（{@code uv*3}、{@code uv*4}），
     * 围着只有太阳 1/5 大的月亮就撑成一圈横跨全屏的星芒，而且颜色来自着色器里
     * 写死的暖色 LUT —— 所以整套拿过来并不合适，只留射线。
     */
    static final float MOON_GLOW_RAY_GAIN = 1.5f;
}
