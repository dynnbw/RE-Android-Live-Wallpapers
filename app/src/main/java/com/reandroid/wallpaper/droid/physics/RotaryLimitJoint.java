package com.reandroid.wallpaper.droid.physics;

/**
 * 旋转限位约束:把 b 相对 a 的夹角限制在 [min, max] 内(机器人腿部 ±1 rad)。
 */
public final class RotaryLimitJoint extends Constraint {

    private final double min;
    private final double max;

    private double iSum;
    private double maxImpulse;
    private double jAcc;
    private double bias;

    private RotaryLimitJoint(Body a, Body b, double min, double max) {
        super(a, b);
        this.min = min;
        this.max = max;
    }

    public static RotaryLimitJoint create(Body a, Body b, double min, double max) {
        return new RotaryLimitJoint(a, b, min, max);
    }

    @Override
    void preStep(double dt) {
        double dist = b.angle - a.angle;
        double overshoot = 0.0;
        if (dist > max) {
            overshoot = dist - max;
        } else if (dist < min) {
            overshoot = dist - min;
        }

        // 纠偏速度:按 errorBias 比例逐步拉回限位内,并受 maxBias 限制
        double biasCoef = 1.0 - Math.pow(1.0 - errorBias, dt * 60.0);
        double bias = -biasCoef * overshoot / dt;
        if (bias > maxBias) {
            bias = maxBias;
        } else if (bias < -maxBias) {
            bias = -maxBias;
        }
        this.bias = bias;

        double iSum = a.invMoment + b.invMoment;
        this.iSum = iSum > 0.0 ? 1.0 / iSum : 0.0;
        this.maxImpulse = maxImpulse(dt);
    }

    @Override
    void applyImpulse(double dt) {
        double wr = b.angVel - a.angVel;
        double j = (bias - wr) * iSum;

        double old = jAcc;
        double next = old + j;
        if (next > maxImpulse) {
            next = maxImpulse;
        } else if (next < -maxImpulse) {
            next = -maxImpulse;
        }
        jAcc = next;
        j = next - old;

        a.angVel -= j * a.invMoment;
        b.angVel += j * b.invMoment;
    }

    /** 位置修正:把超出限位的夹角直接拉回范围内。 */
    @Override
    boolean solvePosition(double slop, double beta, double maxCorrection) {
        double dist = b.angle - a.angle;
        double overshoot;
        if (dist > max) {
            overshoot = dist - max;
        } else if (dist < min) {
            overshoot = dist - min;
        } else {
            return false;
        }
        double magnitude = Math.abs(overshoot);
        if (magnitude <= slop) {
            return false;
        }
        double iSum = a.invMoment + b.invMoment;
        if (iSum <= 0.0) {
            return false;
        }
        double correction = Math.min(maxCorrection, magnitude - slop) * beta;
        if (overshoot < 0.0) {
            correction = -correction;
        }
        a.angle += correction * (a.invMoment / iSum);
        b.angle -= correction * (b.invMoment / iSum);
        return true;
    }
}
