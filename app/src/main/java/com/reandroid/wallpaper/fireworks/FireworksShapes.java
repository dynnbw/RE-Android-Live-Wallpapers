package com.reandroid.wallpaper.fireworks;

import java.util.Random;

/**
 * 烟花形态库（纯 Java，无 GL/Android 依赖，可 JVM 测试）。
 *
 * <p>参考 {@code docs/烟花.html} 的 9 种形态。与原版"均匀随机圆盘"的区别在于：
 * 这里是 {@code f(u)}（{@code u = i/n ∈ [0,1)}）的参数化曲线，因此与每发的粒子数无关 ——
 * 我们固定 74 个爆开粒子，将来提高 STRIDE 只会更细腻。
 *
 * <p><b>单位与缩放</b>：速度是 px/s、重力是 px/s²，均按原版基准屏高 800 标定；
 * 使用前必须乘屏高比例（见 {@code FireworksScene.mScale}）。
 * 阻尼（1/s）与寿命（s）是速率/时间量，<b>不缩放</b>。
 */
final class FireworksShapes {

    static final int CHRYSANTHEMUM = 0;
    static final int RING = 1;
    static final int DOUBLE_RING = 2;
    static final int WILLOW = 3;
    static final int HEART = 4;
    static final int STAR = 5;
    static final int PALM = 6;
    static final int SPIRAL = 7;
    static final int SCATTER = 8;
    static final int COUNT = 9;

    private static final float TAU = 6.28318530718f;
    /** 螺旋形态的圈数（u 走完一圈参数所绕的整圈数）。 */
    private static final float SPIRAL_TURNS = 6.0f;
    /** 黄金比小数部分：给棕榈的每条射线一个确定但不规则的基速。 */
    private static final float GOLDEN_FRAC = 0.6180339887f;

    /** 棕榈的射线数。 */
    private static final int PALM_RAYS = 16;
    /** 双色双环：前 44% 的粒子给内环。 */
    private static final float DOUBLE_RING_SPLIT = 0.44f;
    /** 环形速度固定，保证是一圈清晰的环而不是一团雾。 */
    private static final float RING_SPEED = 260.0f;
    /** 内/外环速度。 */
    private static final float DOUBLE_RING_INNER = 155.0f;
    private static final float DOUBLE_RING_OUTER = 300.0f;
    /** 两环的色相间隔（度）。 */
    static final float DOUBLE_RING_HUE_OFFSET = 65.0f;
    /** 心形/五角星的速度幅值（px/s）。 */
    private static final float HEART_SPEED = 290.0f;
    private static final float STAR_SPEED = 300.0f;
    /** 心形曲线的最大半径，用于归一化（一次性算好）。 */
    private static final float HEART_MAX_R = computeHeartMaxR();
    /** 螺旋的色相彩虹步长（度/粒子）。 */
    static final float SPIRAL_HUE_STEP = 0.75f;

    /** 形态权重：菊花与环形各出现两次（同参考实现）。 */
    private static final int[] PICK = {
            CHRYSANTHEMUM, CHRYSANTHEMUM,
            RING, RING,
            DOUBLE_RING, WILLOW, HEART, STAR, PALM, SPIRAL, SCATTER
    };

    private static final float[] GRAVITY = { 55f, 30f, 28f, 115f, 30f, 32f, 130f, 35f, 70f };
    private static final float[] DAMPING = { 0.85f, 0.75f, 0.78f, 0.45f, 0.80f, 0.80f, 0.55f, 0.80f, 0.95f };
    private static final float[] LIFE_MIN = { 1.2f, 1.2f, 1.2f, 2.0f, 1.3f, 1.2f, 1.4f, 1.2f, 0.7f };
    private static final float[] LIFE_MAX = { 1.9f, 1.65f, 1.7f, 3.0f, 1.85f, 1.75f, 2.2f, 1.8f, 1.75f };
    /** 粒子直径（px，基准屏高下），由参考实现的半径 ×2 得到。 */
    private static final float[] SIZE_MIN = { 2.6f, 4.2f, 4.0f, 3.0f, 4.4f, 4.2f, 3.2f, 4.0f, 2.0f };
    private static final float[] SIZE_MAX = { 5.0f, 4.2f, 4.0f, 5.2f, 4.4f, 4.2f, 5.2f, 4.0f, 5.6f };

    /**
     * 粒子直径的放大系数（唯一的手感旋钮，嫌小/嫌大改这里）。
     *
     * <p>参考实现画的是<b>硬边圆点</b>（4px 就已经很实），而 star.png 是 32×32 的<b>软边光斑</b>：
     * 实测只有约 19% 的宽度处于 90% 以上 Alpha、34% 在 50% 以上。
     * 所以照抄参考实现的直径，亮核只有 1~2px，看上去几乎透明。这里按贴图剖面放大。
     */
    private static final float SIZE_SCALE = 5.0f;
    private static final float[] LIGHT_MIN = { 60f, 68f, 70f, 62f, 70f, 72f, 74f, 68f, 60f };
    private static final float[] LIGHT_MAX = { 74f, 68f, 70f, 78f, 70f, 72f, 74f, 68f, 80f };

    private FireworksShapes() {}

    /** 按权重随机选一种形态。 */
    static int pickShape(Random r) {
        return PICK[r.nextInt(PICK.length)];
    }

    /** 重力加速度 px/s²（基准屏高，需乘 mScale）。 */
    static float gravity(int shape) {
        return GRAVITY[shape];
    }

    /** 指数阻尼系数 1/s（速率量，不缩放）。 */
    static float damping(int shape) {
        return DAMPING[shape];
    }

    /** 寿命（秒，时间量，不缩放）。 */
    static float life(int shape, Random r) {
        return LIFE_MIN[shape] + r.nextFloat() * (LIFE_MAX[shape] - LIFE_MIN[shape]);
    }

    /** 粒子直径 px（基准屏高，需乘 mScale）。 */
    static float size(int shape, Random r) {
        return (SIZE_MIN[shape] + r.nextFloat() * (SIZE_MAX[shape] - SIZE_MIN[shape])) * SIZE_SCALE;
    }

    /** 亮度下限/上限（HSL 的 L，百分比）。 */
    static float light(int shape, Random r) {
        return LIGHT_MIN[shape] + r.nextFloat() * (LIGHT_MAX[shape] - LIGHT_MIN[shape]);
    }

    /** 是否带闪烁（星尘）。 */
    static boolean twinkles(int shape) {
        return shape == SCATTER;
    }

    /** 垂柳恒为金色，与基色无关（同参考实现）。 */
    static boolean fixedGold(int shape) {
        return shape == WILLOW;
    }

    /**
     * 写第 {@code i}/{@code n} 个爆开粒子的初速度（px/s，基准屏高，需乘 mScale）。
     *
     * <p>结构化形态（菊花/环形/双环/心形/五角星/棕榈/螺旋）只依赖 {@code u = i/n}，
     * 因此与粒子数无关；垂柳与星尘按参考实现取随机角，需要 {@code r}。
     *
     * @param out 长度 ≥ 2 的输出数组，写 [vx, vy]
     */
    static void fillVelocity(int shape, int i, int n, Random r, float[] out) {
        float u = (n > 0) ? (float) i / n : 0.0f;
        switch (shape) {
            case CHRYSANTHEMUM: {
                float a = u * TAU + range(r, -0.045f, 0.045f);
                float sp = range(r, 170.0f, 330.0f);
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp;
                break;
            }
            case RING: {
                float a = u * TAU;
                out[0] = (float) Math.cos(a) * RING_SPEED;
                out[1] = (float) Math.sin(a) * RING_SPEED;
                break;
            }
            case DOUBLE_RING: {
                boolean inner = u < DOUBLE_RING_SPLIT;
                float t = inner ? u / DOUBLE_RING_SPLIT : (u - DOUBLE_RING_SPLIT) / (1.0f - DOUBLE_RING_SPLIT);
                float a = t * TAU;
                float sp = inner ? DOUBLE_RING_INNER : DOUBLE_RING_OUTER;
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp;
                break;
            }
            case WILLOW: {
                // 参考实现用随机角:垂柳本就是"一团下垂的火花",不需要规整
                float a = r.nextFloat() * TAU;
                float sp = range(r, 80.0f, 235.0f);
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp - 30.0f;
                break;
            }
            case HEART: {
                float t = u * TAU;
                float sin = (float) Math.sin(t);
                float hx = 16.0f * sin * sin * sin;
                float hy = 13.0f * (float) Math.cos(t)
                        - 5.0f * (float) Math.cos(2.0f * t)
                        - 2.0f * (float) Math.cos(3.0f * t)
                        - (float) Math.cos(4.0f * t);
                float s = HEART_SPEED / HEART_MAX_R;
                out[0] = hx * s;
                out[1] = -hy * s;   // 屏幕坐标 y 向下,取负才是正着的心
                break;
            }
            case STAR: {
                // 沿 10 条边等分采样;u 映射到周长上
                float f = u * 10.0f;
                int edge = (int) f;
                if (edge > 9) edge = 9;
                float t = f - edge;
                float[] v1 = starVertex(edge);
                float[] v2 = starVertex((edge + 1) % 10);
                out[0] = (v1[0] + (v2[0] - v1[0]) * t) * STAR_SPEED;
                out[1] = (v1[1] + (v2[1] - v1[1]) * t) * STAR_SPEED;
                break;
            }
            case PALM: {
                // 每条射线上速度递增 → 长尾;基速按射线序号取确定值,保证同一条射线不散
                float f = u * PALM_RAYS;
                int ray = (int) f;
                if (ray > PALM_RAYS - 1) ray = PALM_RAYS - 1;
                float t = f - ray;
                float frac = ray * GOLDEN_FRAC;
                frac -= (int) frac;
                float base = 280.0f + 130.0f * frac;
                float a = (ray / (float) PALM_RAYS) * TAU;
                float sp = base * (0.45f + 0.55f * t);
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp;
                break;
            }
            case SPIRAL: {
                // 角度取自 u 而不是绝对序号 i:参考实现用 i 是因为它的粒子数固定,
                // 我们用 u 才能在改 STRIDE 时不变形
                float a = u * SPIRAL_TURNS * TAU;
                float sp = 70.0f + u * 260.0f;
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp;
                break;
            }
            default: {   // SCATTER
                float a = r.nextFloat() * TAU;
                float sp = range(r, 50.0f, 355.0f);
                out[0] = (float) Math.cos(a) * sp;
                out[1] = (float) Math.sin(a) * sp;
                break;
            }
        }
    }

    /**
     * 双色双环:外环相对基色的色相偏移;其余形态为 0。
     */
    static float hueOffset(int shape, int i, int n) {
        if (shape == DOUBLE_RING) {
            float u = (n > 0) ? (float) i / n : 0.0f;
            return u < DOUBLE_RING_SPLIT ? 0.0f : DOUBLE_RING_HUE_OFFSET;
        }
        return 0.0f;
    }

    /**
     * 螺旋:逐粒子色相偏移(彩虹);其余形态为 0。
     */
    static float hueStep(int shape, int i) {
        return shape == SPIRAL ? i * SPIRAL_HUE_STEP : 0.0f;
    }

    /** 五角星第 {@code k} 个顶点(外半径 1 / 内半径 0.42,从正上方开始)。 */
    private static float[] starVertex(int k) {
        float rr = (k % 2 == 0) ? 1.0f : 0.42f;
        float a = -3.14159265358f / 2.0f + k * 3.14159265358f / 5.0f;
        return new float[] { (float) Math.cos(a) * rr, (float) Math.sin(a) * rr };
    }

    private static float computeHeartMaxR() {
        float max = 0.001f;
        for (int i = 0; i < 4096; i++) {
            float t = (i / 4096.0f) * TAU;
            float sin = (float) Math.sin(t);
            float hx = 16.0f * sin * sin * sin;
            float hy = 13.0f * (float) Math.cos(t)
                    - 5.0f * (float) Math.cos(2.0f * t)
                    - 2.0f * (float) Math.cos(3.0f * t)
                    - (float) Math.cos(4.0f * t);
            max = Math.max(max, (float) Math.hypot(hx, hy));
        }
        return max;
    }

    private static float range(Random r, float min, float max) {
        return min + r.nextFloat() * (max - min);
    }
}
