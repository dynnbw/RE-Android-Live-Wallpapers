package com.reandroid.wallpaper.bluesea;

import android.view.MotionEvent;

import java.util.Random;

/**
 * BlueSea physics/logic simulation -- pure Java, no GL dependencies.
 * Holds all jelly / particle state and exposes it to BlueSeaGL via
 * package-private fields and methods.
 */
final class BlueSeaScene {
    private static final int DESIGN_WIDTH = 480;
    private static final int DESIGN_HEIGHT = 800;

    private static final int PANE_COUNT = 5;
    private static final long GLOW_DURATION_MS = 550L;

    private static final int[] JELLY_X = {
        200, 300, 20, 120, 140, 240, 60, 160, 80, 380,
        300, 400, 70, 170, 40, 340, 100, 250, 120, 220
    };

    private static final int[] JELLY_Y = {
        600, 100, 400, 600, 300, 500, 200, 500, 400, 300,
        400, 600, 300, 500, 100, 500, 500, 200, 500, 100
    };

    private static final int[] JELLY_PANE_OFFSET = {
        0, 0, 1, 1, 2, 2, 3, 3, 4, 4,
        0, 0, 1, 1, 2, 2, 0, 0, 1, 1
    };

    private static final JellyConfig[] JELLY_CONFIGS = new JellyConfig[] {
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                180,
                1000,
                5.8f,
                9.1f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                162,
                1300,
                7.7f,
                5.6f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                170,
                1500,
                6.5f,
                7.7f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                200,
                1100,
                8.2f,
                7.4f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                180,
                1200,
                5.2f,
                9.9f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                160,
                1600,
                7.9f,
                5.8f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                200,
                1000,
                5.8f,
                9.1f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                160,
                1300,
                7.7f,
                5.6f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                170,
                1500,
                7.5f,
                6.7f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4.png",
                "bluesea/drawable/bluesea_bubble_press.png",
                190,
                1100,
                8.4f,
                7.2f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                150,
                1200,
                8.2f,
                5.9f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                140,
                1600,
                7.9f,
                8.4f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                140,
                1800,
                6.2f,
                5.4f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                150,
                1600,
                5.7f,
                8.2f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                130,
                1200,
                8.3f,
                6.9f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4_blur1.png",
                "bluesea/drawable/bluesea_bubble_press_blur1.png",
                150,
                1500,
                6.2f,
                7.5f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1_blur2.png",
                "bluesea/drawable/bluesea_bubble_press_blur2.png",
                120,
                1300,
                7.1f,
                5.8f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4_blur2.png",
                "bluesea/drawable/bluesea_bubble_press_blur2.png",
                110,
                1700,
                5.9f,
                7.1f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_1_blur2.png",
                "bluesea/drawable/bluesea_bubble_press_blur2.png",
                100,
                1100,
                8.0f,
                6.7f),
        new JellyConfig(
                "bluesea/drawable/bluesea_bubble_4_blur2.png",
                "bluesea/drawable/bluesea_bubble_press_blur2.png",
                120,
                1000,
                6.8f,
                5.8f)
    };

    private static final long JELLY_TURN_INTERVAL_MIN_MS = 700L;
    private static final long JELLY_TURN_INTERVAL_MAX_MS = 1800L;
    private static final float JELLY_SPEED_MIN = 18.0f;
    private static final float JELLY_SPEED_MAX = 46.0f;

    // --- State ---

    final Random mRandom = new Random();

    /**
     * 水母与气泡。**不能 final**：数量是用户设置，改了要按新长度重建。
     *
     * <p>重建时长度精确等于实际数量 —— 两处渲染循环用的是增强 for（`for (JellyState j : mJellies)`），
     * 数组里留 null 会当场 NPE。
     */
    JellyState[] mJellies;

    Particle[] mParticles;
    Texture mBackground;
    Texture mParticle;

    float mXOffset;
    float mScaleX = 1.0f;
    float mScaleY = 1.0f;
    float mScale = 1.0f;
    int mWidth;
    int mHeight;
    long mLastTimeMs;

    // --- 用户设置（默认值都等于加设置之前的行为，老用户观感不变）---

    static final String PREFS_PARTICLE_COUNT = "bluesea_particle_count";
    static final String PREFS_JELLY_SIZE = "bluesea_jelly_size";
    static final String PREFS_JELLY_DENSITY = "bluesea_jelly_density";

    private static final int DEFAULT_PARTICLE_COUNT = 40;
    private static final int DEFAULT_JELLY_SIZE_PERCENT = 100;
    private static final int DEFAULT_JELLY_DENSITY_PERCENT = 100;

    int mParticleCount = DEFAULT_PARTICLE_COUNT;
    /** 尺寸倍率：乘在每只水母自己的 size 上，所以景深关系不会被压平。 */
    float mJellySizeScale = 1.0f;

    private int mJellyDensityPercent = DEFAULT_JELLY_DENSITY_PERCENT;

    private android.content.SharedPreferences mPluginPrefs;

    BlueSeaScene(int width, int height) {
        resize(width, height);
        initSimulation();
    }

    /** 由引擎/插件反射注入插件作用域的偏好（与其它壁纸同一套契约）。 */
    void setPluginPrefs(android.content.SharedPreferences prefs) {
        mPluginPrefs = prefs;
        applyPrefs();
    }

    private int prefInt(String key, int defValue) {
        return mPluginPrefs == null ? defValue : Math.max(0, mPluginPrefs.getInt(key, defValue));
    }

    private void applyPrefs() {
        int particles = prefInt(PREFS_PARTICLE_COUNT, DEFAULT_PARTICLE_COUNT);
        int density = prefInt(PREFS_JELLY_DENSITY, DEFAULT_JELLY_DENSITY_PERCENT);
        // 大小是乘出来的，不碰模拟状态 —— 改了它不必重建水母
        mJellySizeScale = prefInt(PREFS_JELLY_SIZE, DEFAULT_JELLY_SIZE_PERCENT) / 100.0f;

        // 数量变了才重建：水母有位置与漂移相位，重建等于让它们重新出现
        if (particles != mParticleCount || density != mJellyDensityPercent) {
            mParticleCount = particles;
            mJellyDensityPercent = density;
            initSimulation();
        }
    }

    /**
     * 密度 → 取哪些水母。
     *
     * <p>不能"取前 N 个"：那 20 组是按**景深**排的（0–9 清晰 / 10–15 blur1 / 16–19 blur2），
     * 取前一半会先把最远那层丢光，画面从"有纵深"塌成"全贴脸"。
     * 等距抽取按比例覆盖三层，密度降下去只是变稀疏，层次不变。
     *
     * @return 选中项在 JELLY_* 各表里的下标
     */
    private int[] jellyIndicesForDensity(int densityPercent) {
        int percent = Math.min(100, Math.max(1, densityPercent));
        int step = Math.max(1, Math.round(100.0f / percent));
        int count = (JELLY_CONFIGS.length + step - 1) / step;
        int[] out = new int[count];
        int n = 0;
        for (int i = 0; i < JELLY_CONFIGS.length; i += step) {
            out[n++] = i;
        }
        return out;
    }

    // --- Public / package-private API called by GL ---

    void resize(int width, int height) {
        mWidth = width;
        mHeight = height;
        mScaleX = width / (float) DESIGN_WIDTH;
        mScaleY = height / (float) DESIGN_HEIGHT;
        mScale = (mScaleX + mScaleY) * 0.5f;
    }

    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    void onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // 渲染线程 drawFrame(timeMs) 用 System.currentTimeMillis()(GLESWallpaper/GLESPreviewView),
            // 触摸路径必须用同一时钟,否则 glowStartMs 与绘制时的 elapsed 计算跨时钟导致光晕永远不显示。
            triggerNearestGlow(event.getX(), event.getY(), System.currentTimeMillis());
        }
    }

    void update(long timeMs) {
        if (mLastTimeMs == 0L) {
            mLastTimeMs = timeMs;
        }
        float dt = (timeMs - mLastTimeMs) * 0.001f;
        mLastTimeMs = timeMs;

        updateJellies(timeMs, dt);
        updateParticles(dt);
    }

    // --- Logic helpers called by GL draw methods ---

    /**
     * 景深层 → 由**水母自己的**层字段给出，不再按下标推。
     *
     * <p>按下标推（原来写死的 {@code index >= 16 → 2}）要求"渲染循环的下标"与"命中测试的下标"
     * 永远一致；密度筛选会压缩下标，两层就错位了 —— 水母会被画进别人那一层（错的绘制次序与视差）。
     * 层在 {@link #initSimulation} 里按**原始表下标**定下，之后跟着水母走。
     */
    private static int planeForTableIndex(int index) {
        if (index >= 16) return 2;
        if (index >= 10) return 1;
        return 0;
    }

    float computeSwimScale(JellyState jelly, long timeMs) {
        float period = Math.max(300.0f, jelly.config.swimTimeMs);
        float phase = ((timeMs * 0.001f) / (period * 0.001f)) + jelly.swimPhase;
        return 1.0f + 0.1f * (float) Math.sin(phase * Math.PI * 2.0);
    }

    float computeDrift(float phaseOffset, float durationSeconds, long timeMs) {
        float driftSize = 20.0f * mScale;
        float period = Math.max(2.0f, durationSeconds);
        float phase = ((timeMs * 0.001f) / period) + phaseOffset;
        return (float) Math.sin(phase * Math.PI * 2.0) * driftSize;
    }

    float computeGlowAlpha(JellyState jelly, long timeMs) {
        if (jelly.glowStartMs == 0L) return 0.0f;
        float elapsed = (timeMs - jelly.glowStartMs) / (float) GLOW_DURATION_MS;
        if (elapsed >= 1.0f) return 0.0f;
        float value = 1.0f - Math.abs((elapsed * 2.0f) - 1.0f);
        return value * 0.8f;
    }

    // --- Touch handling ---

    /**
     * 每层视差系数(与 BlueSeaGL 原内联逻辑一致,收进 Scene 供渲染与触摸共用)。
     * planeOffset = -mXOffset·mWidth·factor。
     */
    float planeFactor(int plane) {
        if (mWidth > 600) {
            return plane == 2 ? 0.5f : (plane == 1 ? 0.8f : 1.2f);
        }
        return plane == 2 ? 1.0f : (plane == 1 ? 1.5f : 2.5f);
    }

    /** 水母实际绘制 X(与渲染完全同公式):jelly.x + pane·W + 视差偏移 + 漂移 */
    float jellyDrawX(JellyState jelly, long timeMs) {
        float scrollOffset = -mXOffset * mWidth;
        return jelly.x
                + jelly.pane * mWidth
                + scrollOffset * planeFactor(jelly.plane)
                + computeDrift(jelly.driftPhaseX, jelly.config.driftDurX, timeMs);
    }

    /** 水母实际绘制 Y:jelly.y + 漂移(无滚动偏移) */
    float jellyDrawY(JellyState jelly, long timeMs) {
        return jelly.y + computeDrift(jelly.driftPhaseY, jelly.config.driftDurY, timeMs);
    }

    void triggerNearestGlow(float x, float y, long timeMs) {
        float minDist = Float.MAX_VALUE;
        JellyState nearest = null;
        for (int i = 0; i < mJellies.length; i++) {
            JellyState jelly = mJellies[i];
            // 命中检测用与渲染相同的屏幕坐标(含 pane 页偏移、视差偏移、漂移),
            // 否则桌面滚动/跨页时触摸位置与显示位置错位
            float dx = jellyDrawX(jelly, timeMs) - x;
            float dy = jellyDrawY(jelly, timeMs) - y;
            float dist = dx * dx + dy * dy;
            if (dist < minDist) {
                minDist = dist;
                nearest = jelly;
            }
        }
        if (nearest != null) {
            nearest.glowStartMs = timeMs;
        }
    }

    // --- Simulation init and per-frame logic ---

    private void initSimulation() {
        int[] indices = jellyIndicesForDensity(mJellyDensityPercent);
        // 长度精确等于数量：渲染循环是增强 for，留 null 会 NPE
        mJellies = new JellyState[indices.length];
        for (int n = 0; n < indices.length; n++) {
            int i = indices[n];
            JellyConfig config = JELLY_CONFIGS[i];
            JellyState jelly = new JellyState();
            jelly.config = config;
            jelly.x = JELLY_X[i] * mScaleX;
            jelly.y = JELLY_Y[i] * mScaleY;
            jelly.pane = JELLY_PANE_OFFSET[i];
            jelly.plane = planeForTableIndex(i);
            jelly.swimPhase = mRandom.nextFloat();
            jelly.driftPhaseX = mRandom.nextFloat();
            jelly.driftPhaseY = mRandom.nextFloat();
            resetJellyVelocity(jelly, 0L);
            mJellies[n] = jelly;
        }

        mParticles = new Particle[Math.max(0, mParticleCount)];
        for (int i = 0; i < mParticles.length; i++) {
            mParticles[i] = createParticle();
        }
    }

    private void updateJellies(long timeMs, float dt) {
        for (JellyState jelly : mJellies) {
            if (timeMs >= jelly.nextTurnMs) {
                resetJellyVelocity(jelly, timeMs);
                jelly.glowStartMs = timeMs;
            }

            jelly.x += jelly.vx * dt;
            jelly.y += jelly.vy * dt;

            if (jelly.x < 0.0f) {
                jelly.x = 0.0f;
                jelly.vx = Math.abs(jelly.vx);
            } else if (jelly.x > mWidth) {
                jelly.x = mWidth;
                jelly.vx = -Math.abs(jelly.vx);
            }

            if (jelly.y < 0.0f) {
                jelly.y = 0.0f;
                jelly.vy = Math.abs(jelly.vy);
            } else if (jelly.y > mHeight) {
                jelly.y = mHeight;
                jelly.vy = -Math.abs(jelly.vy);
            }
        }
    }

    private void resetJellyVelocity(JellyState jelly, long timeMs) {
        float angle = mRandom.nextFloat() * (float) (Math.PI * 2.0);
        float speed = (JELLY_SPEED_MIN + mRandom.nextFloat() * (JELLY_SPEED_MAX - JELLY_SPEED_MIN))
                * mScale;
        jelly.vx = (float) Math.cos(angle) * speed;
        jelly.vy = (float) Math.sin(angle) * speed;

        long interval = JELLY_TURN_INTERVAL_MIN_MS
                + mRandom.nextInt(
                        (int) (JELLY_TURN_INTERVAL_MAX_MS - JELLY_TURN_INTERVAL_MIN_MS + 1L));
        jelly.nextTurnMs = timeMs + interval;
    }

    private void updateParticles(float dt) {
        float totalWidth = mWidth * (float) PANE_COUNT;
        for (Particle particle : mParticles) {
            particle.y -= particle.speed * dt;
            if (particle.y < -particle.size) {
                particle.x = mRandom.nextFloat() * totalWidth;
                particle.y = mHeight + particle.size + mRandom.nextFloat() * mHeight;
                particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
                particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
                particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
            }
        }
    }

    private Particle createParticle() {
        Particle particle = new Particle();
        float totalWidth = mWidth * (float) PANE_COUNT;
        particle.x = mRandom.nextFloat() * totalWidth;
        particle.y = mRandom.nextFloat() * mHeight;
        particle.size = 20.0f + mRandom.nextFloat() * 40.0f;
        particle.speed = 15.0f + mRandom.nextFloat() * 25.0f;
        particle.alpha = 0.3f + mRandom.nextFloat() * 0.5f;
        return particle;
    }

    // --- Inner classes ---

    static final class JellyConfig {
        final String imageAsset;
        final String glowAsset;
        final float size;
        final float swimTimeMs;
        final float driftDurX;
        final float driftDurY;

        JellyConfig(
                String imageAsset,
                String glowAsset,
                float size,
                float swimTimeMs,
                float driftDurX,
                float driftDurY) {
            this.imageAsset = imageAsset;
            this.glowAsset = glowAsset;
            this.size = size;
            this.swimTimeMs = swimTimeMs;
            this.driftDurX = driftDurX;
            this.driftDurY = driftDurY;
        }
    }

    static final class JellyState {
        JellyConfig config;
        Texture image;
        Texture glow;
        float x;
        float y;
        float vx;
        float vy;
        /** 横向页偏移（0–4），来自 JELLY_PANE_OFFSET，跟着水母走。 */
        int pane;
        /** 景深层（0 近 → 2 远），来自原始表下标，跟着水母走。 */
        int plane;

        float swimPhase;
        float driftPhaseX;
        float driftPhaseY;
        long nextTurnMs;
        long glowStartMs;
    }

    static final class Particle {
        float x;
        float y;
        float size;
        float speed;
        float alpha;
    }

    static final class Texture {
        int id;

        Texture(int id) {
            this.id = id;
        }
    }
}
