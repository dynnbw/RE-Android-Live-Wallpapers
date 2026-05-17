package com.reandroid.wallpaper.fireworks;

import android.os.SystemClock;

import java.util.Random;

/**
 * Fireworks 壁纸场景逻辑层（纯 Java，无 GL 调用）。
 * 负责烟花粒子系统的物理模拟、爆炸逻辑、拖尾生成、触摸触发。
 */
final class FireworksScene {

    // 数学常量：圆周率（简化值）
    static final float PI = 3.14159265358f;
    // 粒子基础尺寸
    static final float PARTICLE_SIZE = 50.0f;

    // 烟花爆炸时的粒子数量
    private static final int EXPLODE_FIREWORKS = 74;
    // 每组烟花的粒子步长（每组总粒子数）
    static final int STRIDE = 75;
    // 常规烟花的最大组数
    static final int MAX_NORMAL = 2;
    // 额外烟花（点击触发）的最大组数
    static final int MAX_EXTRAS = 3;
    // 常规烟花粒子数组长度
    private static final int NORMAL_FIREWORKS = 150;
    // 额外烟花粒子数组长度
    private static final int EXTRAS_FIREWORKS = 225;

    // 粒子基础速度
    private static final float SPEED = 0.4f;
    // 速度放大系数
    private static final float SPEED_MAGNIFY = 2.0f;
    // 速度变化范围
    private static final float SPEED_VARIANCE = 0.2f;
    // 空气阻力系数
    private static final float RESISTANCE = 0.00098f;
    // 重力加速度
    private static final float GRAVITY = 0.0004f;
    // 常规粒子类型
    static final int PARTICLE_NORMAL = 0;
    // 额外粒子类型
    static final int PARTICLE_EXTRAS = 1;
    // 粒子透明度衰减基础值
    private static final float FADE = 0.000333f;
    // 衰减值变化范围
    private static final float FADE_VARIANCE = 0.2f;
    // 衰减放大系数
    private static final float FADE_MAGNIFY = 1.2f;
    // 烟花发射最大延迟时间（毫秒）
    private static final int MAX_DELAY = 10000;
    // 拖尾粒子最大数量
    static final int MAX_TAILS = 500;
    // 拖尾生成比例阈值
    private static final float MAX_RATIO = 0.0f;
    // 闪光效果持续时间
    static final int FLARE_DURATION = 200;

    // 随机数生成器（基于当前时间初始化）
    final Random mRandom = new Random(System.currentTimeMillis());

    // 常规烟花粒子数组
    final FireworkParticle[] mNormal = new FireworkParticle[NORMAL_FIREWORKS];
    // 额外烟花粒子数组（点击触发）
    final FireworkParticle[] mExtras = new FireworkParticle[EXTRAS_FIREWORKS];
    // 拖尾粒子数组
    final TailParticle[] mTails = new TailParticle[MAX_TAILS];

    // 当前系统时间（毫秒）
    int mNow;

    // 基础初始化标记
    boolean mInitialized = false;

    // 场景宽度（用于粒子初始位置计算）
    private int mSceneWidth;
    // 场景高度（用于粒子初始位置计算）
    private int mSceneHeight;

    // 触摸事件待处理标记
    boolean mTapPending = false;
    // 触摸X坐标
    int mTapX;
    // 触摸Y坐标
    int mTapY;

    /**
     * 构造方法
     * @param width 场景宽度
     * @param height 场景高度
     */
    FireworksScene(int width, int height) {
        mSceneWidth = width;
        mSceneHeight = height;
    }

    /**
     * 初始化粒子系统
     * 创建粒子实例并初始化默认状态
     */
    void initialize() {
        mNow = (int) SystemClock.uptimeMillis();

        // 初始化常规烟花粒子
        for (int i = 0; i < NORMAL_FIREWORKS; i++) {
            mNormal[i] = new FireworkParticle();
        }
        // 初始化额外烟花粒子
        for (int i = 0; i < EXTRAS_FIREWORKS; i++) {
            mExtras[i] = new FireworkParticle();
        }
        // 初始化拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            mTails[i] = new TailParticle();
            initTails(mTails[i]);
        }

        // 初始化默认的常规烟花组
        for (int i = 0; i < MAX_NORMAL; i++) {
            int index = i * STRIDE;
            initFireworks(mNormal, index, PARTICLE_NORMAL);
        }
        // 初始化默认的额外烟花组
        for (int i = 0; i < MAX_EXTRAS; i++) {
            int index = i * STRIDE;
            initFireworks(mExtras, index, PARTICLE_EXTRAS);
        }
    }

    /**
     * 检测数值符号是否变化
     * @param last 上一次的值
     * @param cur 当前值
     * @return 1=符号变化，0=未变化
     */
    int signChanged(float last, float cur) {
        if (last >= 0.0f) {
            return cur >= 0.0f ? 0 : 1;
        } else {
            return cur >= 0.0f ? 1 : 0;
        }
    }

    /**
     * 归一化向量（转换为单位向量）
     * @param vec 二维向量[x, y]
     */
    void normalize(float[] vec) {
        float eps = 0.000001f;
        float x = vec[0];
        float y = vec[1];
        float v = (float) Math.sqrt(x * x + y * y);
        if (v < eps) {
            vec[0] = 0.0f;
            vec[1] = 0.0f;
        } else {
            vec[0] = x / v;
            vec[1] = y / v;
        }
    }

    /**
     * 计算空气阻力对速度的影响
     * @param s 速度向量[x, y]
     */
    void resistance(float[] s) {
        normalize(s);
        s[0] = -RESISTANCE * s[0] * (float) Math.sqrt(s[0] * s[0] + s[1] * s[1]);
        s[1] = -RESISTANCE * s[1] * (float) Math.sqrt(s[0] * s[0] + s[1] * s[1]);
    }

    /**
     * 根据生命值计算粒子尺寸
     * @param life 粒子生命值
     * @return 粒子绘制尺寸
     */
    float getSize(float life) {
        return PARTICLE_SIZE * (float) Math.sqrt(Math.abs(life));
    }

    /**
     * 初始化拖尾粒子状态
     * @param p 拖尾粒子实例
     */
    void initTails(TailParticle p) {
        p.root = null;
        p.time = mNow;
        p.type = 0;
        p.life = -1.0f;
        p.posX = 0.0f;
        p.posY = 0.0f;
    }

    /**
     * 生成烟花拖尾
     * @param root 主烟花粒子
     * @return 生成的拖尾粒子索引（-1=失败，MAX_TAILS=满）
     */
    int genTails(FireworkParticle root) {
        if (root == null) return -1;
        float[] vec = new float[]{root.dx, root.dy};
        normalize(vec);
        float s = (float) Math.sqrt(vec[0] * vec[0] + vec[1] * vec[1]);
        float life = root.life * 0.8f * (float) Math.sqrt(s);
        int indexInNormal = indexOf(mNormal, root);
        int indexInExtras = indexOf(mExtras, root);
        // 组首粒子的拖尾生命值降低
        if ((indexInNormal != -1 && indexInNormal % STRIDE == 0)
                || (indexInExtras != -1 && indexInExtras % STRIDE == 0)) {
            life *= 0.3f;
        }
        float size = 0.08f * getSize(life);
        boolean draw = size < 3 || root.ds > size;
        // 不满足条件则不生成拖尾
        if (!root.hasTails || !draw) return -1;

        // 寻找空闲的拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            TailParticle p = mTails[i];
            if (p.life < 0.0f) {
                p.root = root;
                p.time = mNow;
                root.ds = 0;
                p.life = life;
                p.posX = root.posX;
                p.posY = root.posY;
                return i;
            }
        }
        return MAX_TAILS;
    }

    /**
     * 更新拖尾粒子状态
     * @param p 拖尾粒子实例
     */
    void updateTails(TailParticle p) {
        if (p == null) return;
        if (p.root == null || p.life < 0.0f) return;
        if (p.type == 0) {
            // 计算时间差
            int delta = mNow - p.time;
            p.time = mNow;
            FireworkParticle root = p.root;
            // 衰减生命值
            p.life -= FADE_MAGNIFY * root.fade * delta;
            // 生命值耗尽则重置
            if (p.life < 0.0f) {
                initTails(p);
            }
        }
    }

    /**
     * 生成闪光效果
     * @param first 主烟花粒子
     * @return 生成的拖尾粒子索引（-1=失败，MAX_TAILS=满）
     */
    int genFlares(FireworkParticle first) {
        if (first == null) return -1;
        // 随机生成闪光位置（主粒子周围120像素内）
        float x = randf2(first.posX - 120.0f, first.posX + 120.0f);
        float y = randf2(first.posY - 120.0f, first.posY + 120.0f);

        // 寻找空闲的拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            TailParticle p = mTails[i];
            if (p.life < 0.0f) {
                p.root = first;
                p.time = mNow;
                p.type = 1; // 标记为闪光类型
                first.ds = 0;
                p.life = 1.0f;
                p.posX = x;
                p.posY = y;
                return i;
            }
        }
        return MAX_TAILS;
    }

    /**
     * 初始化烟花粒子组
     * @param arr 粒子数组
     * @param index 组起始索引
     * @param type 粒子类型（常规/额外）
     */
    void initFireworks(FireworkParticle[] arr, int index, int type) {
        FireworkParticle p = arr[index];
        // 随机生成粒子颜色（0.5~1.0的亮度）
        int r = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        int g = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        int b = (int) ((randf(0.5f) + 0.5f) * 255.0f);
        // 随机生成衰减速度
        float fade = randf2(1.0f - FADE_VARIANCE, 1.0f + FADE_VARIANCE) * FADE;
        // 计算生成拖尾的粒子数量
        int tailsCount = (int) (STRIDE * randf(MAX_RATIO));
        int c = 0;

        // 初始化组内所有粒子
        for (int i = 0; i < STRIDE; i++) {
            p.time = mNow;
            p.active = false;
            // 设置是否生成拖尾
            if (c < tailsCount) {
                p.hasTails = true;
                c++;
            } else {
                p.hasTails = false;
            }
            p.ds = (int) PARTICLE_SIZE;
            p.life = -1.0f;
            p.fade = fade;
            p.type = type;
            p.r = r;
            p.g = g;
            p.b = b;
            // 随机初始位置（屏幕宽度2倍范围内）
            p.posX = (int) randf(mSceneWidth * 2.0f);
            p.posY = (int) mSceneHeight; // 初始在屏幕底部
            p.dx = 0.0f;
            p.dy = 0.0f;
            // 创建下一个粒子实例（最后一个粒子不需要）
            if (i < STRIDE - 1) {
                arr[index + i + 1] = new FireworkParticle();
                p = arr[index + i + 1];
            }
        }

        // 初始化组首粒子（发射的主粒子）
        FireworkParticle first = arr[index];
        first.dy = -randf2(1.0f, 1.0f + SPEED_VARIANCE) * SPEED_MAGNIFY; // 向上的初始速度
        first.r = 255; // 白色
        first.g = 255;
        first.b = 255;
        first.hasTails = true; // 主粒子强制生成拖尾

        // 常规粒子默认激活，额外粒子默认未激活
        if (type == PARTICLE_NORMAL) {
            first.active = true;
            first.life = 1.0f;
            first.time = mNow + (int) randf(MAX_DELAY); // 随机延迟发射
        } else {
            first.active = false;
            first.life = -1.0f;
        }
    }

    /**
     * 烟花爆炸逻辑
     * @param arr 粒子数组
     * @param index 组起始索引
     */
    void explode(FireworkParticle[] arr, int index) {
        FireworkParticle p = arr[index];
        p.active = false; // 主粒子停止移动
        p.time = mNow;
        p.life = 1.0f;
        p.ds = FLARE_DURATION + 1; // 触发闪光效果

        float theta;
        float threshold;
        // 初始化爆炸后的子粒子
        for (int i = 0; i < EXPLODE_FIREWORKS; i++) {
            FireworkParticle e = arr[index + i + 1];
            e.active = true;
            e.life = 1.0f;
            e.time = mNow;
            e.posX = p.posX; // 与主粒子同位置
            e.posY = p.posY;
            // 随机爆炸方向（0~2π）
            theta = randf(2.0f * PI);
            // 随机爆炸速度（0~1）
            threshold = randf(1.0f);
            e.dx = (float) (Math.cos(theta) * threshold);
            e.dy = (float) (Math.sin(theta) * threshold);
        }
    }

    /**
     * 更新烟花粒子组状态（物理计算）
     * @param arr 粒子数组
     * @param index 组起始索引
     */
    void updateFireworks(FireworkParticle[] arr, int index) {
        FireworkParticle p = arr[index];
        if (p == null) return;

        // 计算时间差
        int delta = mNow - p.time;
        float[] vec = new float[]{p.dx, p.dy};
        // 计算空气阻力
        resistance(vec);

        // 粒子未激活或生命值耗尽则跳过
        if (delta < 0 || p.life < 0.0f) {
            return;
        } else if (p.active) {
            // 主粒子上升阶段
            p.time = mNow;
            p.life = Math.abs(p.dy); // 生命值关联垂直速度

            // 更新位置
            float lastPos = p.posY;
            p.posY = p.posY + (p.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
            float curPos = p.posY;
            p.ds += (int) Math.abs(curPos - lastPos); // 累计移动距离
            genTails(p); // 生成拖尾

            // 更新垂直速度（重力+阻力）
            float lastSpeed = p.dy;
            p.dy = p.dy + (GRAVITY + vec[1]) * delta;
            float curSpeed = p.dy;
            // 速度符号变化（到达最高点）则触发爆炸
            int changed = signChanged(lastSpeed, curSpeed);
            if (changed != 0) {
                explode(arr, index);
            }
        } else {
            // 爆炸后阶段
            p.ds += delta;
            // 满足条件则生成闪光效果
            if (p.ds > FLARE_DURATION && p.life > 0.2f) {
                genFlares(p);
            }
            // 更新主粒子位置
            p.posY = p.posY + (p.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
            // 更新主粒子速度
            p.dy = p.dy + (GRAVITY + vec[1]) * delta;
            p.time = mNow;
            // 衰减生命值
            p.life -= p.fade * delta;
            // 生命值耗尽则重置粒子组
            if (p.life < 0.0f) {
                initFireworks(arr, index, p.type);
                return;
            }

            // 更新爆炸后的子粒子
            for (int i = 0; i < EXPLODE_FIREWORKS; i++) {
                FireworkParticle e = arr[index + i + 1];
                delta = mNow - e.time;
                e.time = mNow;
                vec[0] = e.dx;
                vec[1] = e.dy;
                resistance(vec); // 计算阻力

                // 更新位置
                float lastPos = (float) Math.sqrt(e.posX * e.posX + e.posY * e.posY);
                e.posX = e.posX + (e.dx + vec[0] * delta * SPEED * 0.5f) * delta * SPEED;
                e.posY = e.posY + (e.dy + (GRAVITY + vec[1]) * delta * SPEED * 0.5f) * delta * SPEED;
                float curPos = (float) Math.sqrt(e.posX * e.posX + e.posY * e.posY);
                e.ds += (int) Math.abs(curPos - lastPos);
                genTails(e); // 生成拖尾

                // 更新X方向速度（阻力）
                float lastSpeed = e.dx;
                e.dx = e.dx + vec[0] * delta;
                float curSpeed = e.dx;
                if (signChanged(lastSpeed, curSpeed) == 1) {
                    e.dx = lastSpeed; // 符号变化则回弹
                }
                // 更新Y方向速度（重力+阻力）
                lastSpeed = e.dy;
                curSpeed = e.dy + vec[1] * delta;
                e.dy = e.dy + (GRAVITY + vec[1]) * delta;
                if (signChanged(lastSpeed, curSpeed) == 1) {
                    e.dy = e.dy + GRAVITY * delta; // 符号变化则增加重力
                }
                // 衰减子粒子生命值
                e.life -= e.fade * delta;
            }
        }
    }

    /**
     * 更新所有粒子状态
     * 遍历所有粒子组和拖尾粒子，执行物理计算
     */
    void update() {
        // 更新常规烟花
        for (int i = 0; i < MAX_NORMAL; i++) {
            updateFireworks(mNormal, i * STRIDE);
        }
        // 更新额外烟花
        for (int i = 0; i < MAX_EXTRAS; i++) {
            updateFireworks(mExtras, i * STRIDE);
        }
        // 更新拖尾粒子
        for (int i = 0; i < MAX_TAILS; i++) {
            updateTails(mTails[i]);
        }
    }

    /**
     * 处理触摸事件，生成点击烟花
     * @param x 触摸X坐标
     * @param y 触摸Y坐标
     */
    void addTap(int x, int y) {
        // 寻找空闲的额外烟花组
        for (int i = 0; i < MAX_EXTRAS; i++) {
            int index = i * STRIDE;
            FireworkParticle p = mExtras[index];
            if (p.life < 0.0f) {
                // 初始化烟花组
                initFireworks(mExtras, index, PARTICLE_EXTRAS);
                p = mExtras[index];
                // 设置触摸位置为发射位置
                p.posX = x;
                p.posY = y;
                p.dx = 0.0f;
                p.dy = 0.0f;
                p.life = 1.0f;
                p.active = true;
                // 立即触发爆炸
                explode(mExtras, index);
                break;
            }
        }
    }

    /**
     * 生成0~range的随机浮点数
     * @param range 最大值
     * @return 随机数
     */
    float randf(float range) {
        return mRandom.nextFloat() * range;
    }

    /**
     * 生成min~max的随机浮点数
     * @param min 最小值
     * @param max 最大值
     * @return 随机数
     */
    float randf2(float min, float max) {
        return min + mRandom.nextFloat() * (max - min);
    }

    /**
     * 查找粒子在数组中的索引
     * @param arr 粒子数组
     * @param target 目标粒子
     * @return 索引（-1=未找到）
     */
    int indexOf(FireworkParticle[] arr, FireworkParticle target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return i;
        }
        return -1;
    }
}

/**
 * 烟花粒子实体类
 * 存储单个烟花粒子的状态和物理属性
 */
class FireworkParticle {
    int time;           // 粒子最近一次更新时间
    int ds;             // 粒子移动距离累计
    int type;           // 粒子类型（常规/额外）
    boolean active;     // 粒子是否激活
    boolean hasTails;   // 是否生成拖尾
    int r;              // 颜色R通道值
    int g;              // 颜色G通道值
    int b;              // 颜色B通道值
    float life;         // 粒子生命值（0~1，0为消亡）
    float fade;         // 粒子衰减速度
    float posX;         // X坐标
    float posY;         // Y坐标
    float dx;           // X方向速度
    float dy;           // Y方向速度
}

/**
 * 拖尾粒子实体类
 * 存储烟花粒子的拖尾效果数据
 */
class TailParticle {
    FireworkParticle root;  // 关联的主烟花粒子
    int time;               // 拖尾生成时间
    int type;               // 拖尾类型（普通拖尾/闪光）
    float life;             // 拖尾生命值
    float posX;             // X坐标
    float posY;             // Y坐标
}
