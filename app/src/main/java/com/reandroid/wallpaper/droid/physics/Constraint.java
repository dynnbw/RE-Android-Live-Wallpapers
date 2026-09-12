package com.reandroid.wallpaper.droid.physics;

/**
 * 约束基类。errorBias / maxBias / maxForce 与原始物理库同义:
 * errorBias 为每步的位置误差修正比例(关节可用 0 关闭纠偏),
 * maxBias 限制纠偏速度,maxForce 限制每步冲量(maxForce = 0 即"关闭"该约束)。
 */
public abstract class Constraint {

    /** 原始物理库的默认 errorBias:(1 - 0.1)^60 ≈ 0.001797 */
    static final double DEFAULT_ERROR_BIAS = 0.0017970102999144313;

    final Body a;
    final Body b;

    double maxForce = Double.POSITIVE_INFINITY;
    double errorBias = DEFAULT_ERROR_BIAS;
    double maxBias = Double.POSITIVE_INFINITY;

    Constraint(Body a, Body b) {
        this.a = a;
        this.b = b;
    }

    public void setMaxForce(double maxForce) {
        this.maxForce = maxForce;
    }

    public void setErrorBias(double errorBias) {
        this.errorBias = errorBias;
    }

    public void setMaxBias(double maxBias) {
        this.maxBias = maxBias;
    }

    /** 每步最大冲量。 */
    double maxImpulse(double dt) {
        return maxForce * dt;
    }

    abstract void preStep(double dt);

    abstract void applyImpulse(double dt);

    void postStep() {
    }

    /**
     * 位置求解(分裂冲量):接触位置修正可能把约束拉偏,因此约束同样参与位置修正。
     * 默认无位置误差(如马达约束)。
     */
    boolean solvePosition(double slop, double beta, double maxCorrection) {
        return false;
    }

    /** 约束生效时唤醒两端刚体(静态刚体无需唤醒)。 */
    final void activateBodies() {
        // 本项目未启用睡眠机制,唤醒为空操作,保留接口语义
    }
}
