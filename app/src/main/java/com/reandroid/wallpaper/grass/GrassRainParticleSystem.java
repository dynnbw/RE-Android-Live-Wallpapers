package com.reandroid.wallpaper.grass;

/**
 * 雨粒子的状态机。移植自参考实现
 * {@code assets/particle_rain_line_emitter.comp} 与 {@code assets/particle_rain.comp}。
 *
 * <p><b>为什么是 CPU 实现</b>：原件是 ES 3.1 的 compute shader（{@code #version 310 es}
 * + SSBO）。本分支的目标现在是 ES 3.2，compute 是有的 —— 但**故意没走那条路**：
 * 实测这套粒子每帧只上传约 3 KB、约 50 次三角函数，在整个场景里是噪声量级
 * （光是草叶那一项每帧就有 179 KB），而换成 compute 要付出 SSBO 生命周期、
 * memory barrier，以及**失去全部测试**的代价 —— GLSL 在这个项目里没法单测，
 * 而这台状态机的 12 条测试抓到过两个真 bug（槽位回收策略、{@code preWarmFactor} 未归零）。
 *
 * <p>状态机本身是纯标量运算，grass 原先也是在 Java 里逐帧算雨丝位置的，所以逐条搬过来即可 —— 顺带让整台状态机变成可 JVM 测试的纯逻辑。
 *
 * <p><b>这一层要解决的问题</b>：原先雨丝的速度全在 300-350、三张贴图尺寸与速度毫无关系，
 * 也就是一堵平墙。参考实现里**速度、尺寸、透明度由同一个 {@code scaleFactor}（深度）决定**
 * —— 这才是雨看起来有纵深的原因。深度用 {@link #speedScale}/{@link #alphaScale}/
 * {@link #widthScale} 三个静态方法暴露，方便单独测。
 *
 * <p><b>与参考实现的结构对应</b>：原件把位置和尺寸存在一个 {@code mat4} 里；这里摊平成
 * 标量（{@code x}/{@code y}/{@code quadWidthPx}/{@code quadHeightPx}），因为 grass 的
 * 四边形批只要这两个尺寸。
 *
 * <p><b>坐标</b>：grass 的四边形批是像素坐标、y 向下，所以原件的
 * {@code matrix[3].y += …}（世界空间 +Y）在这里就是"屏幕向下掉"，不需要翻转。
 */
final class GrassRainParticleSystem {

    /** 四态，取值与参考实现一致。 */
    static final float STATE_BORN = 0f;
    static final float STATE_WAIT = 1f;
    static final float STATE_RUN = 2f;
    static final float STATE_DIE = 3f;

    /** 发射器与粒子的参数。默认值是上机的起点，观感不对时改这里。 */
    static final class Config {
        int count = 96;
        float lifeMin = 0.7f;
        float lifeMax = 1.4f;
        /** 每秒发射个数。 */
        float frequency = 20.0f;
        float startSizeMin = 0.35f;
        float startSizeMax = 1.0f;
        /** 像素/秒。还会再乘 {@link #speedScale}，所以这是"最近处"的速度。 */
        float speed = 1100.0f;
        /** > 0 时开局就当作已经下了一阵，避免出画面时从一条干净的线开始。 */
        float preWarmScale = 0.6f;
        float startX = 0.0f;
        float startY = -260.0f;
        float endX = 1080.0f;
        float endY = 60.0f;
        /** 贴图的基础尺寸（像素）。深度只在这之上做倍率。 */
        float baseWidth = 8.0f;
        float baseHeight = 96.0f;
    }

    // ---- 深度 → 表现。三个都只依赖同一个值，这就是"纵深" ----

    /** 深度 → 下落速度倍率。参考 particle_rain.comp:24。 */
    static float speedScale(float depth) {
        return mix(0.12f, 1.0f, clamp01(depth));
    }

    /** 深度 → 透明度倍率。参考 particle_rain_frag_shader.glsl:10。 */
    static float alphaScale(float depth) {
        return mix(0.3f, 2.0f, clamp01(depth));
    }

    /** 深度 → 宽度倍率。参考 particle_rain_line_emitter.comp:145。**远的反而更宽**。 */
    static float widthScale(float depth) {
        return mix(3.0f, 1.0f, clamp01(depth));
    }

    /** 参考 particle_rain_frag_shader.glsl:11 的 0.4 系数：贴图 alpha 的整体缩放。 */
    static final float TEXTURE_ALPHA_SCALE = 0.4f;

    // ---- 状态 ----

    private final Config cfg;
    private final float[] state;
    private final float[] costTime;
    private final float[] duration;
    private final float[] lifeTime;
    private final float[] depth;
    private final float[] alpha;
    private final float[] preWarmFactor;
    private final float[] x;
    private final float[] y;
    private final float[] quadWidthPx;
    private final float[] quadHeightPx;

    GrassRainParticleSystem(Config config) {
        this.cfg = config;
        int n = Math.max(0, config.count);
        state = new float[n];
        costTime = new float[n];
        duration = new float[n];
        lifeTime = new float[n];
        depth = new float[n];
        alpha = new float[n];
        preWarmFactor = new float[n];
        x = new float[n];
        y = new float[n];
        quadWidthPx = new float[n];
        quadHeightPx = new float[n];
    }

    int count() { return state.length; }

    /** 重置。{@code preWarm} 为真时直接进入"已经下了一阵"的样子。 */
    void reset(boolean preWarm) {
        for (int i = 0; i < count(); i++) {
            state[i] = STATE_BORN;
            costTime[i] = 0.0f;
            duration[i] = 0.0f;
            lifeTime[i] = 0.0f;
            alpha[i] = 0.0f;
            preWarmFactor[i] = 0.0f;
            depth[i] = 0.0f;
            x[i] = cfg.startX;
            y[i] = cfg.startY;
            quadWidthPx[i] = 0.0f;
            quadHeightPx[i] = 0.0f;
        }
        if (preWarm && cfg.preWarmScale > 0.0f) {
            // 预热：把每个粒子推进到它本该在的位置，再让它继续跑，这样出画面时
            // 已经是"下了一阵"的样子，而不是从一条干净的发射线开始。
            // 参考实现里这件事由发射器和一个 preWarmFactor 共同完成，这里是等价的直接做法：
            // 先正常发射一次（拿到深度/尺寸/寿命），再把它直接推进一段随机时长。
            for (int i = 0; i < count(); i++) {
                emit(i, 0.0f, true);
                float jump = cfg.lifeMax * cfg.preWarmScale * random(i, 0.11f);
                costTime[i] = Math.min(jump, lifeTime[i] * 0.95f);
                alpha[i] = alphaFor(costTime[i], lifeTime[i]);
                y[i] += cfg.speed * jump * speedScale(depth[i]);
                preWarmFactor[i] = 0.0f;
                state[i] = STATE_RUN;
            }
        }
    }

    /**
     * 推进一帧。
     *
     * @param dt   本帧秒数
     * @param time 累计秒数，用作随机种子的时间分量（与参考实现的 {@code time} 一致）
     */
    void advance(float dt, float time) {
        if (dt <= 0.0f) return;
        for (int i = 0; i < count(); i++) {
            switch ((int) state[i]) {
                case (int) STATE_BORN:
                    emit(i, time, false);
                    break;
                case (int) STATE_WAIT:
                    updateWait(i, dt);
                    break;
                case (int) STATE_RUN:
                    updateRun(i, dt);
                    break;
                default:
                    updateDie(i);
                    break;
            }
        }
    }

    // ---- 访问器（渲染侧只读） ----

    float state(int i) { return state[i]; }
    float x(int i) { return x[i]; }
    float y(int i) { return y[i]; }
    float depth(int i) { return depth[i]; }
    float alpha(int i) { return alpha[i]; }
    float lifeTime(int i) { return lifeTime[i]; }
    float scaleFactor(int i) { return depth[i]; }
    float quadWidthPx(int i) { return quadWidthPx[i]; }
    float quadHeightPx(int i) { return quadHeightPx[i]; }

    /** 渲染用的最终 alpha：贴图 alpha × 深度倍率 × 粒子 alpha × 0.4。 */
    float renderAlpha(int i) {
        return alphaScale(depth[i]) * alpha[i] * TEXTURE_ALPHA_SCALE;
    }

    // ---- 状态迁移 ----

    private void emit(int i, float time, boolean isInit) {
        float lifeRandom = random(i, fract(time + 0.11f));
        lifeTime[i] = mix(cfg.lifeMin, cfg.lifeMax, lifeRandom);

        // 深度。参考实现这里名字叫 easeInCubic，实际是 progress 的四次方 —— 照抄，别"修好"。
        float raw = random(i, fract(time));
        depth[i] = easeInQuart(0.0f, 1.0f, raw);

        float randomSize = mix(cfg.startSizeMin, cfg.startSizeMax, depth[i]);
        quadWidthPx[i] = randomSize * cfg.baseWidth * widthScale(depth[i]);
        quadHeightPx[i] = randomSize * cfg.baseHeight;

        // 线段发射器：同一个随机值同时当 x 和 y 的插值参数
        float along = random(i, fract(time + 0.33147f) * i);
        x[i] = mix(cfg.startX, cfg.endX, along);
        y[i] = mix(cfg.startY, cfg.endY, along);

        alpha[i] = 1.0f;
        costTime[i] = 0.0f;
        preWarmFactor[i] = 0.0f;

        // 等一个错开的时长再开跑，否则所有粒子会在同一帧一起出现。
        // 参考实现的两个分支（line_emitter.comp:155-182）：
        //   isInit（reset 那一刻）—— 按序号平铺，index/frequency 秒的 0.5~1.5 倍
        //   之后每次重生        —— 寿命比发射间隔长就重新平铺，否则补足到平均间隔
        float average = cfg.frequency > 0.0f ? 1.0f / cfg.frequency : 0.0f;
        float waitRandom = random(i, fract(time + 0.11f));
        float waitTime;
        if (isInit) {
            float base = average * i;
            waitTime = mix(base * 0.5f, base * 1.5f, waitRandom);
        } else if (average > 0.0f && lifeTime[i] / average >= 1.0f) {
            waitTime = mix(0.0f, average * i, waitRandom);
        } else {
            waitTime = Math.max(0.0f, average - lifeTime[i]);
        }
        duration[i] = waitTime;
        state[i] = STATE_WAIT;
    }

    private void updateWait(int i, float dt) {
        costTime[i] += dt;
        if (costTime[i] >= duration[i]) {
            // 等掉的时长要**先存下来**再覆盖 duration —— 它就是下面的预跑距离。
            float waited = duration[i];
            state[i] = STATE_RUN;
            costTime[i] = 0.0f;
            duration[i] = lifeTime[i];
            alpha[i] = 1.0f;
            // 参考实现的 switchToRun(index, 等掉的时长)：这段等待当作"已经跑过"，
            // 于是粒子不是从发射线开始掉，而是已经掉了一段。
            preWarmFactor[i] = waited;
        }
    }

    private void updateRun(int i, float dt) {
        costTime[i] += dt;
        // 参考 particle_rain.comp:25-26：delta 里带上 preWarmFactor，
        // 而它**只在这一帧非零**。忘了归零的话粒子会每帧多掉一个恒定距离 ——
        // 看起来像"越掉越快"，但"y 只增不减"那条测试照样过。
        float delta = dt + preWarmFactor[i];
        y[i] += cfg.speed * delta * speedScale(depth[i]);
        preWarmFactor[i] = 0.0f;

        alpha[i] = alphaFor(costTime[i], duration[i]);
        if (duration[i] > 0.0f && costTime[i] >= duration[i]) {
            state[i] = STATE_DIE;
            costTime[i] = 0.0f;
            duration[i] = 0.0f;
        }
    }

    /**
     * 参考 updateDieState：{@code loop == 1} 且进 DIE 时 duration 被置 0，
     * 于是 {@code costTime >= duration} 恒真 —— **下一帧就重生**。
     * DIE 态在这里只存在一步，让路的是上面那个 WAIT 队列的错开。
     */
    private void updateDie(int i) {
        state[i] = STATE_BORN;
        costTime[i] = 0.0f;
    }

    private static float alphaFor(float cost, float life) {
        if (life <= 0.0f) return 0.0f;
        float progress = cost / life;
        progress *= progress;
        return mix(1.0f, 0.0f, progress);
    }

    // ---- 参考实现里搬过来的数学 ----

    /** 参考 particle_rain_line_emitter.comp:54。名字是 Cubic，实际是四次方。 */
    static float easeInQuart(float start, float end, float progress) {
        float d = end - start;
        return d * progress * progress * progress * progress + start;
    }

    /** 参考 particle_rain_line_emitter.comp:67-71。 */
    static float hash12(float px, float py) {
        float p3x = fract(px * 0.1031f);
        float p3y = fract(py * 0.1031f);
        float p3z = fract(px * 0.1031f);
        float d = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f);
        p3x += d;
        p3y += d;
        p3z += d;
        return fract((p3x + p3y) * p3z);
    }

    /** 参考 particle_rain_line_emitter.comp:72-75。{@code amount} 就是粒子数。 */
    private float random(int index, float seed) {
        int n = count();
        if (n <= 0) return 0.0f;
        return hash12(index / (float) n, seed * 10.87791f);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
