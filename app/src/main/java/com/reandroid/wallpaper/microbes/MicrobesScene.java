package com.reandroid.wallpaper.microbes;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.reandroid.gles.GLESWallpaper;

import java.util.Random;

/**
 * Microbes 壁纸场景逻辑层(纯 Java,无 GL 调用)。
 * <p>
 * 忠实移植 AOSP Microbes GL 原生引擎(libmicrobes_jni.so.c,IDA 反编译):
 * 逐函数转写 sub_1674(init)/ sub_1E88(step)/ sub_17A0(漫游)/ sub_1918(食物漂移)/
 * sub_1B4C(互扰)/ sub_1BC8(食物)/ sub_1CF8(motion)/ sub_1D78(积分)/
 * sub_1378(触摸)/ sub_14EC(类型)/ sub_1A90(繁殖)。
 * <p>
 * 与反编译的唯一偏差:生命周期速度/运动活跃度两个设置作为倍率叠加(默认 100% = 原版),
 * 以及配色模式设置(默认 original = 原版 4 类型)。
 */
final class MicrobesScene {

    private SharedPreferences mPluginPrefs;

    // ---- 原版常量(sub_1674 内存布局推导)----
    static final int MICROBE_COUNT = 300;   // 52B/个(默认数量)
    static final int MICROBE_MAX = 500;     // 数量可调上限(数组分配)
    static final int FOOD_COUNT = 600;      // 12B/个
    static final int DEAD_COUNT = 80;       // 16B/个(尸体)
    static final int DECOR_COUNT = 60;      // 12B/个
    static final int MOTION_COUNT = 15;     // 12B/个

    private static final float INVALID_POS = -10000.0f;   // -971227136
    private static final float ACTIVE_MIN_X = -9000.0f;   // x > -9000 视为有效
    // ---- 原版距离常量(480×800 设备、世界 960×800 基准,逐字保留)----
    private static final float SPEED_LIMIT = 80.0f;
    private static final float INTERACT_DIST2 = 900.0f;   // 30² 互扰距离
    private static final float ATTRACT_DIST2 = 6400.0f;   // 80² 食物/motion 吸引距离
    private static final float BOUNDARY_MARGIN = 90.0f;
    private static final float BOUNDARY_FACTOR = 0.1f;
    private static final float WANDER_PUSH = 20.0f;
    private static final float CORPSE_REUSE_Y = -200.0f;  // 深埋出屏(精灵放大后 -10 仍可见)
    private static final float ENERGY_DECAY_RATE = 0.0125f;
    private static final float EAT_ENERGY_GAIN_LOW = 0.375f;   // 能量 ≤ 0.25 时进食
    private static final float EAT_ENERGY_GAIN_HIGH = 0.125f;
    private static final float HIGH_ENERGY_LEVEL = 0.75f;
    private static final float HIGH_ENERGY_EXTRA_DECAY = 0.025f;
    private static final float BREED_ACCUMULATE_RATE = 0.02f;
    private static final float BREED_THRESHOLD = 1.2f;
    private static final float BREED_RESET = 0.7f;
    private static final float FOOD_RESPAWN_INTERVAL = 0.2f;
    private static final float MOTION_VALID_SECONDS = 0.5f;
    private static final float TOUCH_SPAWN_FOOD_COUNT = 5;
    private static final long PREF_POLL_INTERVAL_MS = 1000L;

    static final float TOUCH_DRAG_THRESHOLD_PX = 30.0f;

    private final Random rng = new Random();

    // ---- 微生物(13 字段,对应 C 结构 52B)----
    final float[] microbeX = new float[MICROBE_MAX];       // +0
    final float[] microbeY = new float[MICROBE_MAX];       // +4
    final float[] microbeAngle = new float[MICROBE_MAX];   // +8
    final float[] microbeBreed = new float[MICROBE_MAX];   // +12 繁殖能量
    final float[] microbeEnergy = new float[MICROBE_MAX];  // +16
    final float[] microbePulseTs = new float[MICROBE_MAX]; // +20 脉冲时间戳
    final float[] microbeC0 = new float[MICROBE_MAX];      // +24 aColor.r
    final float[] microbeC1 = new float[MICROBE_MAX];      // +28 aColor.g
    final float[] microbeC2 = new float[MICROBE_MAX];      // +32 aColor.b(类型蓝通道)
    final float[] microbeVx = new float[MICROBE_MAX];      // +36
    final float[] microbeVy = new float[MICROBE_MAX];      // +40
    final float[] microbePhase = new float[MICROBE_MAX];   // +44
    final float[] microbeInterval = new float[MICROBE_MAX];// +48 脉冲刷新间隔

    // ---- 食物(3 floats)----
    final float[] foodX = new float[FOOD_COUNT];
    final float[] foodY = new float[FOOD_COUNT];
    final float[] foodPhase = new float[FOOD_COUNT];

    // ---- 尸体(16B: x, y, angle, breed)----
    final float[] deadX = new float[DEAD_COUNT];
    final float[] deadY = new float[DEAD_COUNT];
    final float[] deadAngle = new float[DEAD_COUNT];
    final float[] deadBreed = new float[DEAD_COUNT];
    final boolean[] deadPendingSpawn = new boolean[DEAD_COUNT];   // 尸体移除后刷出替代微生物

    // ---- 装饰(12B: x, y, z)----
    final float[] decorX = new float[DECOR_COUNT];
    final float[] decorY = new float[DECOR_COUNT];
    final float[] decorZ = new float[DECOR_COUNT];

    // ---- motion(12B: x, y, expiry)----
    final float[] motionX = new float[MOTION_COUNT];
    final float[] motionY = new float[MOTION_COUNT];
    final float[] motionExpiry = new float[MOTION_COUNT];

    // ---- 模拟状态 ----
    float timeSec;           // 全局 t
    float worldWidth;        // scrollInfo(desiredMinWidth)
    float worldHeight;       // dword_4238(desiredMinHeight)
    int viewportWidth;       // dword_423C(surface 宽)
    int viewportHeight;      // dword_4240(surface 高)
    int scrollXPx;           // dword_4244(滚动偏移)
    float foodRespawnTimer;  // 场景末 4B(0.2s 补食周期)
    int microbeCount = MICROBE_COUNT;   // 当前微生物数量(可调,默认原版 300)
    float lifecycleSpeedScale = 1.0f;
    float motionActivityScale = 1.0f;
    boolean originalColorMode = true;
    long lastPrefPollMs = Long.MIN_VALUE;

    // ---- 触摸状态 ----
    boolean touchActive;
    float touchStartX;
    float touchStartY;
    boolean touchMoved;

    void setPluginPrefs(SharedPreferences p) {
        mPluginPrefs = p;
    }

    // ==================== 初始化(sub_1674)====================

    void initScene(int width, int height) {
        worldWidth = Math.max(1.0f, width * 2.0f);
        worldHeight = Math.max(1.0f, height);
        viewportWidth = width;
        viewportHeight = height;
        scrollXPx = 0;
        timeSec = 0.0f;
        foodRespawnTimer = 0.0f;

        // sub_1594:全部槽位(MICROBE_MAX)基础字段;前 30 个激活(其余 INVALID,靠繁殖逐步激活)
        for (int i = 0; i < MICROBE_MAX; i++) {
            microbeX[i] = INVALID_POS;
            microbePhase[i] = rand01();
            microbeEnergy[i] = rand(0.5f, 0.8f);
            microbeBreed[i] = rand(0.9f, 1.1f);
            microbeInterval[i] = rand(4.0f, 5.0f);
            assignMicrobeType(i);
        }
        for (int i = 0; i < 30; i++) {
            microbeX[i] = rand(0.0f, worldWidth);
            microbeY[i] = rand(0.0f, worldHeight);
        }
        microbeCount = MICROBE_COUNT;

        // 食物:600 全 INVALID + phase;前 50 个激活
        for (int i = 0; i < FOOD_COUNT; i++) {
            foodX[i] = INVALID_POS;
            foodPhase[i] = rand01();
        }
        for (int i = 0; i < 50; i++) {
            foodX[i] = rand(0.0f, worldWidth);
            foodY[i] = rand(0.0f, worldHeight);
        }

        // 尸体:x=0, y=INVALID(绘制时 y 出屏)
        for (int i = 0; i < DEAD_COUNT; i++) {
            deadX[i] = 0.0f;
            deadY[i] = INVALID_POS;
        }

        // 装饰(sub_15E0):z=rand(0,1), s=1-0.8z, x=s·W+r·s·W
        for (int i = 0; i < DECOR_COUNT; i++) {
            float z = rand01();
            float s = 1.0f - 0.8f * z;
            decorZ[i] = z;
            decorX[i] = s * worldWidth + rand(-1.0f, 1.0f) * s * worldWidth;
            decorY[i] = s * worldHeight + rand(-1.0f, 1.0f) * s * worldHeight;
        }

        // motion 过期 = 0
        for (int i = 0; i < MOTION_COUNT; i++) {
            motionExpiry[i] = 0.0f;
        }
    }

    void resize(int width, int height) {
        worldWidth = Math.max(worldWidth, width * 2.0f);
        worldHeight = Math.max(1.0f, height);
        viewportWidth = width;
        viewportHeight = height;
    }

    void setScroll(int xPixels, int viewWidth) {
        scrollXPx = xPixels;
        worldWidth = Math.max(worldWidth, viewWidth + Math.abs(xPixels) + viewWidth);
    }

    // ==================== 每帧步进(sub_1E88)====================

    void updateScene(float dt) {
        float moveScale = motionActivityScale;
        float lifeDT = dt * lifecycleSpeedScale;
        float t = timeSec;

        // 1. 漫游(sub_17A0):速度每帧重建 = 边界排斥 + cos/sin 噪声
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            float x = microbeX[i];
            float y = microbeY[i];
            float vx = 0.0f;
            float vy = 0.0f;
            float left = BOUNDARY_MARGIN - x;
            if (left < 0.0f) left = 0.0f;
            float right = (worldWidth - BOUNDARY_MARGIN) - x;
            if (right > 0.0f) right = 0.0f;
            vx += (left + right) * BOUNDARY_FACTOR;
            float bottom = BOUNDARY_MARGIN - y;
            if (bottom < 0.0f) bottom = 0.0f;
            float top = (worldHeight - BOUNDARY_MARGIN) - y;
            if (top > 0.0f) top = 0.0f;
            vy += (bottom + top) * BOUNDARY_FACTOR;
            float phase = microbePhase[i];
            float v11 = (float) Math.cos(t + phase * 30.0f);
            float v12 = microbeAngle[i]
                    + (float) Math.cos(phase * 30.0f + t * 0.3f) * 0.01f
                    + v11 * 0.03f;
            vx += (float) Math.cos(v12) * WANDER_PUSH * moveScale;
            vy += (float) Math.sin(v12) * WANDER_PUSH * moveScale;
            microbeVx[i] = vx;
            microbeVy[i] = vy;
        }

        // 2. 两两互扰(sub_1B4C):dist<30,推力 (30-dist)*0.2,每微生物最多 4 个邻居
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            int count = 0;
            for (int j = i + 1; j < microbeCount && count < 4; j++) {
                if (microbeX[j] <= ACTIVE_MIN_X) continue;
                float dx = microbeX[j] - microbeX[i];
                float dy = microbeY[j] - microbeY[i];
                float dist2 = dx * dx + dy * dy;
                if (dist2 >= INTERACT_DIST2) continue;
                float dist = (float) Math.sqrt(dist2);
                if (dist < 1e-3f) continue;   // 防御:原版在 dist=0 时产生 NaN
                float push = (30.0f - dist) * 0.2f * moveScale;
                float nx = dx / dist;
                float ny = dy / dist;
                microbeVx[i] -= nx * push;
                microbeVy[i] -= ny * push;
                microbeVx[j] += nx * push;
                microbeVy[j] += ny * push;
                count++;
            }
        }

        // 3. 食物吸引/进食(sub_1BC8):dist<80,最多 4 个食物
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            int count = 0;
            for (int f = 0; f < FOOD_COUNT && count < 4; f++) {
                if (foodX[f] <= ACTIVE_MIN_X) continue;
                float dx = foodX[f] - microbeX[i];
                float dy = foodY[f] - microbeY[i];
                float dist2 = dx * dx + dy * dy;
                if (dist2 >= ATTRACT_DIST2) continue;
                count++;
                float dist = (float) Math.sqrt(dist2);
                if (dist < 1e-3f) continue;
                if (microbeEnergy[i] <= 1.0f) {
                    if (dist < 10.0f) {
                        // 进食:能量 ≤0.25 时 +0.375,否则 +0.125
                        microbeEnergy[i] += microbeEnergy[i] <= 0.25f
                                ? EAT_ENERGY_GAIN_LOW : EAT_ENERGY_GAIN_HIGH;
                        foodX[f] = INVALID_POS;
                        microbePulseTs[i] = t;
                    } else {
                        float v6;
                        if (dist < 20.0f && microbeEnergy[i] < 0.8f) v6 = 40.0f;
                        else v6 = dist < 40.0f ? 10.0f : 3.0f;
                        microbeVx[i] += (dx / dist) * v6 * moveScale;
                        microbeVy[i] += (dy / dist) * v6 * moveScale;
                    }
                }
            }
        }

        // 4. motion 吸引(sub_1CF8):dist<80
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            for (int m = 0; m < MOTION_COUNT; m++) {
                if (motionExpiry[m] <= t) continue;
                float dx = motionX[m] - microbeX[i];
                float dy = motionY[m] - microbeY[i];
                float dist2 = dx * dx + dy * dy;
                if (dist2 >= ATTRACT_DIST2) continue;
                float dist = (float) Math.sqrt(dist2);
                if (dist < 1e-3f) continue;
                float v6 = dist < 20.0f ? 100.0f : (dist < 40.0f ? 40.0f : 20.0f);
                microbeVx[i] += (dx / dist) * v6 * moveScale;
                microbeVy[i] += (dy / dist) * v6 * moveScale;
            }
        }

        // 5. 积分(sub_1D78):限速 80,能量衰减,高能量额外衰减 + 繁殖能量累积
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            float vx = microbeVx[i];
            float vy = microbeVy[i];
            float speed = (float) Math.sqrt(vx * vx + vy * vy);
            if (speed > SPEED_LIMIT) {
                float k = SPEED_LIMIT / speed;
                vx *= k;
                vy *= k;
            }
            microbeX[i] += vx * dt;
            microbeY[i] += vy * dt;
            microbeAngle[i] = (float) Math.atan2(vy, vx);
            microbeVx[i] = vx;
            microbeVy[i] = vy;
            float energy = microbeEnergy[i] - ENERGY_DECAY_RATE * lifeDT;
            microbeEnergy[i] = energy;
            if (energy > HIGH_ENERGY_LEVEL) {
                microbeEnergy[i] = energy - HIGH_ENERGY_EXTRA_DECAY * lifeDT;
                microbeBreed[i] += BREED_ACCUMULATE_RATE * lifeDT;
            }
            if ((t - microbePulseTs[i]) > microbeInterval[i]) {
                microbePulseTs[i] = t;
            }
        }

        // 6. 食物漂移(sub_1918):边界左/下 ×1.0、右/上 ×0.1(原版不对称),噪声位移
        for (int f = 0; f < FOOD_COUNT; f++) {
            if (foodX[f] <= ACTIVE_MIN_X) continue;
            float x = foodX[f];
            float y = foodY[f];
            float left = BOUNDARY_MARGIN - x;
            if (left < 0.0f) left = 0.0f;
            float right = (worldWidth - BOUNDARY_MARGIN) - x;
            if (right > 0.0f) right = 0.0f;
            float bottom = BOUNDARY_MARGIN - y;
            if (bottom < 0.0f) bottom = 0.0f;
            float top = (worldHeight - BOUNDARY_MARGIN) - y;
            if (top > 0.0f) top = 0.0f;
            float phase = foodPhase[f];
            float v11 = t + phase * 1000.0f;
            float v14 = (float) Math.sin(v11 * 0.1f);
            float v16 = v14 + v11 * (phase - 0.5f)
                    + (float) Math.sin(phase + v11 * 0.01f);
            x += (left + right * 0.1f + (float) Math.cos(v16) * 3.0f) * dt;
            y += (bottom + top * 0.1f + (float) Math.sin(v16) * 3.0f) * dt;
            foodX[f] = x;
            foodY[f] = y;
        }

        // 7. 尸体下沉(速率按屏高比例,与精灵尺寸缩放一致);沉出屏幕后刷出替代微生物
        float sinkRate = 10.0f * Math.max(1.0f, worldHeight / 800.0f);
        for (int d = 0; d < DEAD_COUNT; d++) {
            if (deadY[d] > CORPSE_REUSE_Y) {
                deadY[d] -= sinkRate * lifeDT;
                if (deadY[d] <= CORPSE_REUSE_Y && deadPendingSpawn[d]) {
                    // 尸体已完全移除 → 刷出替代微生物(种群守恒)
                    deadPendingSpawn[d] = false;
                    spawnReplacementMicrobe();
                }
            }
        }

        // 8. 食物补充:每 0.2s 一个,补到随机位置
        foodRespawnTimer += lifeDT;
        if (foodRespawnTimer >= FOOD_RESPAWN_INTERVAL) {
            foodRespawnTimer -= FOOD_RESPAWN_INTERVAL;
            int slot = findInvalidFoodSlot();
            if (slot >= 0) {
                foodX[slot] = rand(0.0f, worldWidth);
                foodY[slot] = rand(0.0f, worldHeight);
            }
        }

        // 9. 繁殖/死亡(每微生物,先繁殖后死亡)
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) continue;
            if (microbeBreed[i] >= BREED_THRESHOLD) {
                int child = findInvalidMicrobeSlot();
                if (child >= 0) {
                    reproduce(i, child);
                } else {
                    microbeBreed[i] = BREED_THRESHOLD;   // 无空槽:保持待繁殖
                }
            }
            if (microbeEnergy[i] < 0.0f) {
                int corpse = findReusableCorpseSlot();
                if (deadPendingSpawn[corpse]) {
                    // 该槽原有尸体被覆盖移除 → 先结算它的重生
                    spawnReplacementMicrobe();
                }
                deadX[corpse] = microbeX[i];
                deadY[corpse] = microbeY[i];
                deadAngle[corpse] = microbeAngle[i];
                deadBreed[corpse] = microbeBreed[i];
                deadPendingSpawn[corpse] = true;
                microbeX[i] = INVALID_POS;
            }
        }

        // 10. 屏幕外换型:随机索引,NDC 投影出界则重掷类型(无活动性检查,忠实原版)
        int idx = (int) rand(0.0f, 3000.0f);
        if (idx < microbeCount) {
            float sx = 2.0f / viewportWidth;
            float sy = 2.0f / viewportHeight;
            float tx = 2.0f * scrollXPx / viewportWidth - 1.0f;
            float ty = -1.0f;
            float px = tx + microbeX[idx] * sx;
            float py = ty + microbeY[idx] * sy;
            if (px < -1.0f || px > 1.0f || py < -1.0f || py > 1.0f) {
                assignMicrobeType(idx);
            }
        }
    }

    // ==================== 触摸(sub_1378 / motion)====================

    /** 拖动:写一个 motion 槽(0.5s 有效) */
    void motion(float wx, float wy) {
        writeMotionSlot(wx, wy);
    }

    /** 点击:5 个食物撒在 ±35px + 写一个 motion 槽 */
    void touchTap(float wx, float wy) {
        for (int k = 0; k < TOUCH_SPAWN_FOOD_COUNT; k++) {
            int slot = findInvalidFoodSlot();
            if (slot < 0) break;
            foodX[slot] = wx + rand(-1.0f, 1.0f) * 35.0f;
            foodY[slot] = wy + rand(-1.0f, 1.0f) * 35.0f;
        }
        writeMotionSlot(wx, wy);
    }

    private void writeMotionSlot(float wx, float wy) {
        int slot = 0;
        for (int m = 0; m < MOTION_COUNT; m++) {
            if (motionExpiry[m] <= timeSec) {
                slot = m;
                break;
            }
        }
        motionX[slot] = wx;
        motionY[slot] = wy;
        motionExpiry[slot] = timeSec + MOTION_VALID_SECONDS;
    }

    // ==================== 设置 ====================

    void refreshRuntimeSettingsIfNeeded(long nowMs) {
        if (lastPrefPollMs != Long.MIN_VALUE && (nowMs - lastPrefPollMs) < PREF_POLL_INTERVAL_MS) {
            return;
        }
        lastPrefPollMs = nowMs;

        try {
            if (GLESWallpaper.getAppContext() == null) {
                return;
            }
            SharedPreferences prefs = mPluginPrefs != null ? mPluginPrefs
                    : PreferenceManager.getDefaultSharedPreferences(GLESWallpaper.getAppContext());
            int speedPercent = prefs.getInt("microbes_lifecycle_speed", 100);
            speedPercent = Math.max(50, Math.min(200, speedPercent));
            lifecycleSpeedScale = speedPercent / 100.0f;
            int motionPercent = prefs.getInt("microbes_motion_activity", 100);
            motionPercent = Math.max(10, Math.min(200, motionPercent));
            motionActivityScale = motionPercent / 100.0f;
            int count = prefs.getInt("microbes_count", MICROBE_COUNT);
            count = Math.max(50, Math.min(MICROBE_MAX, count));
            if (count != microbeCount) {
                applyMicrobeCount(count);
            }
            String mode = prefs.getString("microbes_color_mode", "original");
            boolean useOriginal = (mode == null) || "original".equals(mode);
            if (useOriginal != originalColorMode) {
                originalColorMode = useOriginal;
                recolorAllMicrobes();
            }
        } catch (Throwable ignored) {
            lifecycleSpeedScale = 1.0f;
            motionActivityScale = 1.0f;
            originalColorMode = true;
        }
    }

    // ==================== 内部逻辑 ====================

    /**
     * 调整微生物数量:增大时立即激活新槽位(随机位置),缩小时置 INVALID(立即隐藏)。
     * 槽位基础字段在 initScene 已全部初始化。
     */
    private void applyMicrobeCount(int newCount) {
        if (newCount > microbeCount) {
            for (int i = microbeCount; i < newCount; i++) {
                microbeX[i] = rand(0.0f, worldWidth);
                microbeY[i] = rand(0.0f, worldHeight);
            }
        } else {
            for (int i = newCount; i < microbeCount; i++) {
                microbeX[i] = INVALID_POS;
            }
        }
        microbeCount = newCount;
    }

    /**
     * sub_14EC:4 类型(rand(0,4) 取整)。aColor = (c0, c1, c2) 三通道颜色,
     * 经典四色(鲑鱼橙/黄/绿/蓝)。尺寸与原版一致由能量驱动(30×energy),
     * 与颜色解耦 —— 每种大小都能出现所有颜色。
     */
    private void assignMicrobeType(int index) {
        if (originalColorMode) {
            switch ((int) rand(0.0f, 4.0f)) {
                case 1:   // 鲑鱼橙
                    microbeC0[index] = 0.83203125f;
                    microbeC1[index] = 0.19531f;
                    microbeC2[index] = 0.14453f;
                    break;
                case 2:   // 黄
                    microbeC0[index] = 0.9296875f;
                    microbeC1[index] = 0.6953125f;
                    microbeC2[index] = 0.066406f;
                    break;
                case 3:   // 绿
                    microbeC0[index] = 0.05078125f;
                    microbeC1[index] = 0.5976525f;
                    microbeC2[index] = 0.22266f;
                    break;
                default:  // 蓝
                    microbeC0[index] = 0.19921875f;
                    microbeC1[index] = 0.41015625f;
                    microbeC2[index] = 0.90625f;
                    break;
            }
        } else {
            // modern:随机配色
            microbeC0[index] = rand(0.2f, 1.0f);
            microbeC1[index] = rand(0.2f, 1.0f);
            microbeC2[index] = rand(0.2f, 1.0f);
        }
    }

    private void recolorAllMicrobes() {
        for (int i = 0; i < microbeCount; i++) {
            assignMicrobeType(i);
        }
    }

    /** sub_1A90:沿轴向 ±2px 分裂,繁殖能量父子均重置 0.7,子能量 rand(0.5,0.8) */
    private void reproduce(int parent, int child) {
        float angle = microbeAngle[parent];
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        microbeX[child] = microbeX[parent] - 2.0f * cosA;
        microbeY[child] = microbeY[parent] - 2.0f * sinA;
        microbeAngle[child] = angle + 3.1416f;
        microbeBreed[child] = BREED_RESET;
        microbeEnergy[child] = rand(0.5f, 0.8f);
        microbeC0[child] = microbeC0[parent];
        microbeC1[child] = microbeC1[parent];
        microbeC2[child] = microbeC2[parent];
        microbeX[parent] += 2.0f * cosA;
        microbeY[parent] += 2.0f * sinA;
        microbeBreed[parent] = BREED_RESET;
    }

    /** 首个 INVALID 微生物槽(x ≤ -9000);无则 -1 */
    private int findInvalidMicrobeSlot() {
        for (int i = 0; i < microbeCount; i++) {
            if (microbeX[i] <= ACTIVE_MIN_X) {
                return i;
            }
        }
        return -1;
    }

    /** 首个 INVALID 食物槽;无则 -1 */
    private int findInvalidFoodSlot() {
        for (int i = 0; i < FOOD_COUNT; i++) {
            if (foodX[i] <= ACTIVE_MIN_X) {
                return i;
            }
        }
        return -1;
    }

    /** 首个可复用尸体槽(y ≤ -10);无则 0(原版回退首槽) */
    private int findReusableCorpseSlot() {
        for (int i = 0; i < DEAD_COUNT; i++) {
            if (deadY[i] <= CORPSE_REUSE_Y) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 刷出一只替代微生物(尸体移除后种群守恒):找 INVALID 槽位,按 sub_1594 初始化。
     * 无空槽(种群已满)则跳过。
     */
    private void spawnReplacementMicrobe() {
        int slot = findInvalidMicrobeSlot();
        if (slot < 0) return;
        microbeX[slot] = rand(0.0f, worldWidth);
        microbeY[slot] = rand(0.0f, worldHeight);
        microbePhase[slot] = rand01();
        microbeEnergy[slot] = rand(0.5f, 0.8f);
        microbeBreed[slot] = rand(0.9f, 1.1f);
        microbeInterval[slot] = rand(4.0f, 5.0f);
        assignMicrobeType(slot);
    }

    // ---- 数学工具 ----

    float rand(float min, float max) {
        return min + rng.nextFloat() * (max - min);
    }

    float rand01() {
        return rng.nextFloat();
    }
}
