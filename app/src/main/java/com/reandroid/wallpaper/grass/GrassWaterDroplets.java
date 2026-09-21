package com.reandroid.wallpaper.grass;

import java.util.Random;

/**
 * 挂在草叶上的水珠，以及它们溅开 / 坠落的水花。
 *
 * <p><b>为什么不照搬天气应用的做法</b>：那边的水珠是打在**卡片控件**上的屏幕空间后处理，
 * 用圆角 SDF 把水珠裁进形状里。草叶是薄长条、而且每帧在风里摆，屏幕空间的遮罩既不划算
 * 也不对 —— 水珠会相对叶面打滑。
 *
 * <p>这里改成**把水珠锚定在叶片的参数坐标上**：一颗珠记住
 * {@code (叶片下标, 横向 u, 纵向段号 k)}，每帧用 {@link GrassBladeGeometry} 重算屏幕坐标。
 * 而那条行走用的是**当前帧的叶角**，所以"水珠跟着叶子摆"不是要实现的功能，而是结构上
 * 不可能做错的事。
 *
 * <p><b>撞击是统计式的</b>，不是真实碰撞：按雨量强度采样生成，不检测"哪条雨丝打在哪片叶上"。
 * 密雨下两者看不出区别，而真实碰撞要建空间索引、代价高得多。小雨时才会显出差别。
 *
 * <p>纯逻辑，无 GL / Android 依赖，可 JVM 测试。
 */
final class GrassWaterDroplets {

    // ---- 调参。观感不对改这里。 ----

    /** 强度 5 时每秒生成多少颗水珠。 */
    static final float SPAWN_PER_SECOND_AT_MAX = 40.0f;
    /** 长到这么大（像素）开始下滑。 */
    static final float SLIDE_SIZE = 5.0f;
    /** 生长速度（像素/秒）。 */
    static final float GROW_RATE = 3.5f;
    /** 下滑速度基准（段/秒），再按大小放大。 */
    static final float SLIDE_K_PER_SEC = 0.5f;
    /** 汇合的判定窗口（段）。 */
    static final float MERGE_WINDOW = 0.6f;
    /** 水花寿命（秒）。 */
    static final float SPLASH_LIFE = 0.35f;
    /** 水花重力（像素/秒²）。 */
    static final float SPLASH_GRAVITY = 900.0f;
    /** 超越叶尖多远算离开叶片。 */
    static final float DETACH_K = 0.0f;

    /** 一颗挂在叶面上的水珠。 */
    static final class Bead {
        int bladeIndex = -1;
        /** 横向位置，-1 左边缘 / +1 右边缘。一生不变。 */
        float u;
        /** 纵向段号，**小数**，往叶尖方向增。 */
        float k;
        /** 半径（像素）。 */
        float size;
        float growRate;
        boolean sliding;
        boolean active;
        /** 本帧算出的屏幕坐标，渲染直接读。 */
        float x, y;
    }

    /** 一颗水花：撞击溅开，或水珠从叶尖坠落。 */
    static final class Splash {
        float x, y, vx, vy;
        float life, maxLife, size;
        boolean active;

        /** 渲染用的不透明度：越接近消亡越淡。 */
        float alpha() {
            return maxLife <= 0.0f ? 0.0f : Math.max(0.0f, life / maxLife);
        }
    }

    private final Random random;
    private final Bead[] beads;
    private final Splash[] splashes;

    private float spawnAccumulator;
    private int activeBeads;
    private int activeSplashes;

    // 定位用的暂存，按最长的一条叶片增长，避免每帧每颗珠都新建数组
    private float[] scratchXY = new float[0];
    private float[] scratchHW = new float[0];
    private final float[] pos = new float[2];

    GrassWaterDroplets(Random random, int maxBeads, int maxSplashes) {
        this.random = random;
        this.beads = new Bead[Math.max(1, maxBeads)];
        for (int i = 0; i < beads.length; i++) {
            beads[i] = new Bead();
        }
        this.splashes = new Splash[Math.max(1, maxSplashes)];
        for (int i = 0; i < splashes.length; i++) {
            splashes[i] = new Splash();
        }
    }

    int beadCapacity() { return beads.length; }

    int splashCapacity() { return splashes.length; }

    int activeBeadCount() { return activeBeads; }

    int activeSplashCount() { return activeSplashes; }

    Bead beadAt(int i) { return beads[i]; }

    Splash splashAt(int i) { return splashes[i]; }

    /** 渲染用的水珠不透明度：越大的珠越实。 */
    static float beadAlpha(float size) {
        float a = size / 8.0f;
        return a < 0.15f ? 0.15f : (a > 0.85f ? 0.85f : a);
    }

    /** 清空（切出降雨、场景停止时用）。 */
    void clear() {
        for (Bead b : beads) {
            b.active = false;
        }
        for (Splash s : splashes) {
            s.active = false;
        }
        spawnAccumulator = 0.0f;
        activeBeads = 0;
        activeSplashes = 0;
    }

    /**
     * 推进一帧。
     *
     * @param rainIntensity 0..5，见 {@link GrassWeatherSystem#rainIntensity}
     */
    void update(float dt, float rainIntensity, Blade[] blades, SceneData sd) {
        if (dt <= 0.0f || blades == null || blades.length == 0) {
            return;
        }
        spawn(dt, rainIntensity, blades, sd);

        for (Bead b : beads) {
            if (!b.active) {
                continue;
            }
            updateBead(b, dt, rainIntensity, blades, sd);
        }
        for (Splash s : splashes) {
            if (!s.active) {
                continue;
            }
            updateSplash(s, dt);
        }
        recount();
    }

    private void recount() {
        int n = 0;
        for (Bead b : beads) {
            if (b.active) n++;
        }
        activeBeads = n;
        n = 0;
        for (Splash s : splashes) {
            if (s.active) n++;
        }
        activeSplashes = n;
    }

    // ---- 生成 ----

    private void spawn(float dt, float rainIntensity, Blade[] blades, SceneData sd) {
        if (rainIntensity <= 0.0f) {
            spawnAccumulator = 0.0f;
            return;
        }
        spawnAccumulator += rainIntensity / 5.0f * SPAWN_PER_SECOND_AT_MAX * dt;
        while (spawnAccumulator >= 1.0f) {
            spawnAccumulator -= 1.0f;
            spawnOne(blades, sd);
        }
    }

    private void spawnOne(Blade[] blades, SceneData sd) {
        Bead b = obtainBead();
        if (b == null) {
            return;
        }
        int bladeIndex = random.nextInt(blades.length);
        Blade blade = blades[bladeIndex];
        if (blade.size < 2) {
            return;   // 太短的叶片挂不住珠
        }
        b.bladeIndex = bladeIndex;
        // 别贴着边缘：贴着叶缘的珠子看起来像浮在叶外
        b.u = (random.nextFloat() * 2.0f - 1.0f) * 0.7f;
        // 叶面上任意位置，但要给下滑留出空间
        b.k = random.nextFloat() * Math.max(0.0f, blade.size - 1.5f);
        b.size = 0.8f + random.nextFloat() * 0.9f;
        b.growRate = GROW_RATE * (0.7f + random.nextFloat() * 0.6f);
        b.sliding = false;
        b.active = true;

        place(b, blades, sd);
        // 撞击溅开：不是每颗都溅，否则叶面上到处都在炸，很吵
        if (random.nextFloat() < 0.45f) {
            spawnSplash(b.x, b.y, false);
        }
    }

    /**
     * 取一个空槽；满了返回 null（调用方会跳过这次生成）。
     *
     * <p><b>这里刻意不做"回收最老的"</b>。曾经写过"满了就换掉 k 最大的那颗",
     * 理由是"它本来也快离开了" —— 结果是**每生成一颗新珠就杀掉一颗即将坠落的珠**,
     * 于是没有任何水珠能滑到叶尖, `k` 永远长不过生成时的上限。而且它不报错,
     * 只是"叶尖从来不滴水"。
     *
     * <p>满了就不生成的话，数量会稳定在容量上并自然更替：有珠离开才腾出槽位。
     * 密雨时生成被丢弃一部分，视觉上完全看不出来。
     */
    private Bead obtainBead() {
        for (Bead b : beads) {
            if (!b.active) {
                return b;
            }
        }
        return null;
    }

    private Splash obtainSplash() {
        for (Splash s : splashes) {
            if (!s.active) {
                return s;
            }
        }
        return null;
    }

    // ---- 推进 ----

    private void updateBead(Bead b, float dt, float rainIntensity, Blade[] blades, SceneData sd) {
        Blade blade = blades[b.bladeIndex];
        if (!b.sliding) {
            if (rainIntensity > 0.0f) {
                b.size += b.growRate * dt;
                if (b.size >= SLIDE_SIZE) {
                    b.sliding = true;
                }
            } else {
                // 雨停了：还没长够的也让它走，否则会在叶面上永远挂着
                b.sliding = true;
            }
        }
        if (b.sliding) {
            float speed = SLIDE_K_PER_SEC * (0.5f + b.size / SLIDE_SIZE);
            b.k += speed * dt;
            mergeInto(b);
        }

        if (b.k >= blade.size + DETACH_K) {
            // 滑过叶尖 → 坠落
            place(b, blades, sd);
            spawnSplash(b.x, b.y, true);
            b.active = false;
            return;
        }
        place(b, blades, sd);
    }

    /**
     * 下滑的珠吞掉它前方还在生长的珠。
     *
     * <p>按**面积相加**（{@code sqrt(a² + b²)}）而不是半径相加 —— 后者会让两颗小珠一合
     * 就变成一颗大得离谱的珠。同一片叶上的珠天然比邻，所以这里线性扫一遍就够。
     */
    private void mergeInto(Bead a) {
        for (Bead b : beads) {
            if (b == a || !b.active || b.sliding || b.bladeIndex != a.bladeIndex) {
                continue;
            }
            float gap = b.k - a.k;
            if (gap >= 0.0f && gap <= MERGE_WINDOW) {
                a.size = (float) Math.sqrt(a.size * a.size + b.size * b.size);
                b.active = false;
            }
        }
    }

    private void updateSplash(Splash s, float dt) {
        s.life -= dt;
        if (s.life <= 0.0f) {
            s.active = false;
            return;
        }
        s.x += s.vx * dt;
        s.y += s.vy * dt;
        s.vy += SPLASH_GRAVITY * dt;
    }

    private void spawnSplash(float x, float y, boolean falling) {
        Splash s = obtainSplash();
        if (s == null) {
            return;
        }
        s.x = x;
        s.y = y;
        if (falling) {
            s.vx = (random.nextFloat() - 0.5f) * 20.0f;
            s.vy = 40.0f + random.nextFloat() * 40.0f;
            s.maxLife = 0.7f;
            s.size = 3.0f + random.nextFloat() * 3.0f;
        } else {
            float a = random.nextFloat() * (float) Math.PI * 2.0f;
            float sp = 60.0f + random.nextFloat() * 90.0f;
            s.vx = (float) Math.cos(a) * sp;
            s.vy = (float) Math.sin(a) * sp - 40.0f;   // 略微向上溅
            s.maxLife = SPLASH_LIFE;
            s.size = 1.5f + random.nextFloat() * 2.0f;
        }
        s.life = s.maxLife;
        s.active = true;
    }

    /** 用当前帧的叶角把水珠重新定位到叶面上。 */
    private void place(Bead b, Blade[] blades, SceneData sd) {
        Blade blade = blades[b.bladeIndex];
        int need = blade.size + 1;
        if (scratchXY.length < need * 2) {
            scratchXY = new float[need * 2];
            scratchHW = new float[need];
        }
        GrassBladeGeometry.trace(blade, GrassBladeGeometry.originX(blade, sd),
                GrassBladeGeometry.effectiveScale(blade, sd),
                sd.grassHardnessScale, sd.grassHeightScale, scratchXY, scratchHW);
        GrassBladeGeometry.pointAt(scratchXY, scratchHW, b.k, b.u, pos);
        b.x = pos[0];
        b.y = pos[1];
    }
}
