package com.reandroid.wallpaper.nexus;

import android.opengl.Matrix;
import android.os.SystemClock;

/**
 * Nexus 壁纸场景逻辑层（纯 Java，仅 Matrix.orthoM 用于投影矩阵）。
 * 负责脉冲状态管理、缩放适配、触摸注入。
 */
final class NexusScene {

    // 可配置参数（由 NexusSettings 提供）
    int MAX_PULSES;
    int MAX_EXTRAS;
    int PULSE_SIZE;
    int HALF_PULSE_SIZE;
    int GLOW_SIZE;
    int HALF_GLOW_SIZE;
    float SPEED;
    float SPEED_DELTA_MIN;
    float SPEED_DELTA_MAX;
    int TRAIL_SIZE;
    int MAX_DELAY;

    // 纹理UV坐标数据（从raw加载）
    float[] mUv0;
    float[] mUv90;
    float[] mUv180;
    float[] mUv270;

    // 初始宽高（创建时的尺寸）
    int mInitialWidth;
    int mInitialHeight;
    // 世界坐标系X/Y轴缩放比例（适配屏幕尺寸）
    float mWorldScaleX = 1.0f;
    float mWorldScaleY = 1.0f;
    // 壁纸横向偏移量（多屏滑动时）
    float mXOffset;
    // 是否旋转（横屏/竖屏判断）
    boolean mRotate;
    // 壁纸显示模式（配色模式）
    int mMode;

    // 投影矩阵（正交投影）
    final float[] mProjectionMatrix = new float[16];

    // 脉冲状态管理器
    final NexusPulseController mPulseController = new NexusPulseController();
    // 默认设置
    private final NexusSettings mDefaultSettings = NexusSettings.load(null);

    /**
     * 构造方法
     * @param width 初始宽度
     * @param height 初始高度
     */
    NexusScene(int width, int height) {
        mInitialWidth = width;
        mInitialHeight = height;
    }

    /**
     * 应用设置
     * @param settings 设置对象
     */
    void applySettings(NexusSettings settings) {
        NexusSettings s = settings != null ? settings : mDefaultSettings;
        MAX_PULSES = s.maxPulses;
        MAX_EXTRAS = s.maxExtras;
        PULSE_SIZE = s.pulseSize;
        HALF_PULSE_SIZE = s.halfPulseSize;
        GLOW_SIZE = s.glowSize;
        HALF_GLOW_SIZE = s.halfGlowSize;
        SPEED = s.speed;
        SPEED_DELTA_MIN = s.speedDeltaMin;
        SPEED_DELTA_MAX = s.speedDeltaMax;
        TRAIL_SIZE = s.trailSize;
        MAX_DELAY = s.maxDelay;
        mMode = s.mode;
        mPulseController.ensureCapacity(MAX_PULSES, MAX_EXTRAS);
    }

    /**
     * 屏幕尺寸变化时更新缩放和投影
     * @param width 当前宽度
     * @param height 当前高度
     */
    void resize(int width, int height) {
        if (width > 0 && height > 0) {
            mWorldScaleX = (float) mInitialWidth / width;
            mWorldScaleY = (float) mInitialHeight / height;
            updateProjection(width, height);
        }
    }

    /**
     * 设置壁纸偏移量
     * @param xOffset 横向偏移比例
     */
    void setOffset(float xOffset) {
        mXOffset = xOffset;
    }

    /**
     * 更新正交投影矩阵
     * @param width 当前宽度
     * @param height 当前高度
     */
    void updateProjection(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        Matrix.orthoM(mProjectionMatrix, 0, 0, width, height, 0, -1f, 1f);
    }

    /**
     * 初始化脉冲数组
     * @param width 当前宽度
     * @param height 当前高度
     * @param nowMs 当前时间
     */
    void initPulses(int width, int height, long nowMs) {
        mPulseController.initPulses(
                MAX_PULSES,
                MAX_EXTRAS,
                width,
                height,
                PULSE_SIZE,
                SPEED_DELTA_MIN,
                SPEED_DELTA_MAX,
                MAX_DELAY,
                nowMs
        );
    }

    /**
     * 处理点击事件，添加额外脉冲
     * @param x 点击X坐标
     * @param y 点击Y坐标
     * @param nowMs 当前时间
     */
    void addTap(int x, int y, long nowMs) {
        mPulseController.addTap(x, y, MAX_EXTRAS, PULSE_SIZE, nowMs);
    }
}
