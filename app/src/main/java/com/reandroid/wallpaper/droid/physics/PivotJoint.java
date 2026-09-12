package com.reandroid.wallpaper.droid.physics;

/**
 * 铰链约束:把两个刚体上的局部锚点重合(机器人四肢与躯干)。
 * 采用 2x2 有效质量矩阵求解,errorBias = 0 时不做位置纠偏
 * (与原版关节设置一致)。
 */
public final class PivotJoint extends Constraint {

    private final double anchor1X;
    private final double anchor1Y;
    private final double anchor2X;
    private final double anchor2Y;

    private double r1x;
    private double r1y;
    private double r2x;
    private double r2y;

    private double k11;
    private double k12;
    private double k22;

    private double biasX;
    private double biasY;

    private double jx;
    private double jy;

    private double maxImpulse;

    private PivotJoint(Body a, Body b, double a1x, double a1y, double a2x, double a2y) {
        super(a, b);
        this.anchor1X = a1x;
        this.anchor1Y = a1y;
        this.anchor2X = a2x;
        this.anchor2Y = a2y;
    }

    /** 以世界坐标锚点创建(内部换算为两端刚体的局部锚点)。 */
    public static PivotJoint createAtWorldAnchor(Body a, Body b, double worldX, double worldY) {
        double dx1 = worldX - a.px;
        double dy1 = worldY - a.py;
        double a1x = dx1 * a.cos + dy1 * a.sin;
        double a1y = -dx1 * a.sin + dy1 * a.cos;

        double dx2 = worldX - b.px;
        double dy2 = worldY - b.py;
        double a2x = dx2 * b.cos + dy2 * b.sin;
        double a2y = -dx2 * b.sin + dy2 * b.cos;

        return new PivotJoint(a, b, a1x, a1y, a2x, a2y);
    }

    @Override
    void preStep(double dt) {
        r1x = anchor1X * a.cos - anchor1Y * a.sin;
        r1y = anchor1X * a.sin + anchor1Y * a.cos;
        r2x = anchor2X * b.cos - anchor2Y * b.sin;
        r2y = anchor2X * b.sin + anchor2Y * b.cos;

        double mSum = a.invMass + b.invMass;
        k11 = mSum + a.invMoment * r1y * r1y + b.invMoment * r2y * r2y;
        k12 = -a.invMoment * r1x * r1y - b.invMoment * r2x * r2y;
        k22 = mSum + a.invMoment * r1x * r1x + b.invMoment * r2x * r2x;

        double det = k11 * k22 - k12 * k12;
        if (det != 0.0) {
            double i11 = k22 / det;
            double i12 = -k12 / det;
            double i22 = k11 / det;
            k11 = i11;
            k12 = i12;
            k22 = i22;
        }

        // 锚点位置误差 → 纠偏速度。errorBias 解释为"每 1/60s 修正的比例",
        // 按本次步长换算(纯速度约束会随积分误差缓慢漂移,必须带位置反馈)。
        double dx = (b.px + r2x) - (a.px + r1x);
        double dy = (b.py + r2y) - (a.py + r1y);
        double biasCoef = 1.0 - Math.pow(1.0 - errorBias, dt * 60.0);
        double biasX = -biasCoef * dx / dt;
        double biasY = -biasCoef * dy / dt;
        double biasLen = Math.sqrt(biasX * biasX + biasY * biasY);
        if (biasLen > maxBias) {
            double scale = maxBias / biasLen;
            biasX *= scale;
            biasY *= scale;
        }
        this.biasX = biasX;
        this.biasY = biasY;

        maxImpulse = maxImpulse(dt);
    }

    @Override
    void applyImpulse(double dt) {
        double dvx = (b.vx - b.angVel * r2y) - (a.vx - a.angVel * r1y);
        double dvy = (b.vy + b.angVel * r2x) - (a.vy + a.angVel * r1x);

        double targetX = biasX - dvx;
        double targetY = biasY - dvy;

        double impulseX = k11 * targetX + k12 * targetY;
        double impulseY = k12 * targetX + k22 * targetY;

        double newX = jx + impulseX;
        double newY = jy + impulseY;
        if (maxImpulse < Double.POSITIVE_INFINITY) {
            double len = Math.sqrt(newX * newX + newY * newY);
            if (len > maxImpulse) {
                double scale = maxImpulse / len;
                newX *= scale;
                newY *= scale;
            }
        }
        impulseX = newX - jx;
        impulseY = newY - jy;
        jx = newX;
        jy = newY;

        a.applyImpulse(-impulseX, -impulseY, r1x, r1y);
        b.applyImpulse(impulseX, impulseY, r2x, r2y);
    }

    /** 位置修正:把两个锚点直接拉到一起(按当前姿态重算有效质量)。 */
    @Override
    boolean solvePosition(double slop, double beta, double maxCorrection) {
        double cr1x = anchor1X * a.cos - anchor1Y * a.sin;
        double cr1y = anchor1X * a.sin + anchor1Y * a.cos;
        double cr2x = anchor2X * b.cos - anchor2Y * b.sin;
        double cr2y = anchor2X * b.sin + anchor2Y * b.cos;

        double ex = (b.px + cr2x) - (a.px + cr1x);
        double ey = (b.py + cr2y) - (a.py + cr1y);
        double error = Math.sqrt(ex * ex + ey * ey);
        if (error <= slop) {
            return false;
        }

        double step = Math.min(maxCorrection, error - slop) * beta / error;
        double targetX = -ex * step;
        double targetY = -ey * step;

        double mSum = a.invMass + b.invMass;
        double c11 = mSum + a.invMoment * cr1y * cr1y + b.invMoment * cr2y * cr2y;
        double c12 = -a.invMoment * cr1x * cr1y - b.invMoment * cr2x * cr2y;
        double c22 = mSum + a.invMoment * cr1x * cr1x + b.invMoment * cr2x * cr2x;
        double det = c11 * c22 - c12 * c12;
        if (det == 0.0) {
            return false;
        }
        double i11 = c22 / det;
        double i12 = -c12 / det;
        double i22 = c11 / det;

        double jx = i11 * targetX + i12 * targetY;
        double jy = i12 * targetX + i22 * targetY;

        a.applyPositionImpulse(-jx, -jy, cr1x, cr1y);
        b.applyPositionImpulse(jx, jy, cr2x, cr2y);
        return true;
    }
}
