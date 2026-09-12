package com.reandroid.wallpaper.droid.physics;

/**
 * 马达约束:驱动 b 相对 a 的角速度趋向 rate(b.w - a.w = rate)。
 * maxForce = 0 表示停转(原版初始化即如此,由摇晃 / 光照逻辑再开启)。
 */
public final class SimpleMotor extends Constraint {

    private double rate;

    private double iSum;
    private double maxImpulse;
    private double jAcc;

    private SimpleMotor(Body a, Body b, double rate) {
        super(a, b);
        this.rate = rate;
    }

    public static SimpleMotor create(Body a, Body b, double rate) {
        return new SimpleMotor(a, b, rate);
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    public double rate() {
        return rate;
    }

    @Override
    void preStep(double dt) {
        double iSum = a.invMoment + b.invMoment;
        this.iSum = iSum > 0.0 ? 1.0 / iSum : 0.0;
        this.maxImpulse = maxImpulse(dt);
    }

    @Override
    void applyImpulse(double dt) {
        double wr = (b.angVel - a.angVel) - rate;
        double j = -wr * iSum;

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

    @Override
    void postStep() {
    }
}
