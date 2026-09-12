package com.reandroid.wallpaper.droid.physics;

/**
 * 一对形状之间的接触仲裁器:缓存接触点、法线,以及跨帧累积的法向 / 切向冲量(warm start)。
 * 冲量求解为顺序冲量法(sequential impulse)。
 */
final class Arbiter {

    static final int MAX_CONTACTS = 2;

    final long key;
    final Shape a;
    final Shape b;

    int count;
    /** 接触法线,方向由 a 指向 b。 */
    double nx;
    double ny;

    final double[] px = new double[MAX_CONTACTS];
    final double[] py = new double[MAX_CONTACTS];
    final double[] depth = new double[MAX_CONTACTS];
    final double[] r1x = new double[MAX_CONTACTS];
    final double[] r1y = new double[MAX_CONTACTS];
    final double[] r2x = new double[MAX_CONTACTS];
    final double[] r2y = new double[MAX_CONTACTS];
    final double[] massNormal = new double[MAX_CONTACTS];
    final double[] massTangent = new double[MAX_CONTACTS];
    final double[] bounce = new double[MAX_CONTACTS];
    final double[] jn = new double[MAX_CONTACTS];
    final double[] jt = new double[MAX_CONTACTS];

    /** 接触点在两端刚体局部坐标下的锚点(位置求解时随姿态重算)。 */
    final double[] localAx = new double[MAX_CONTACTS];
    final double[] localAy = new double[MAX_CONTACTS];
    final double[] localBx = new double[MAX_CONTACTS];
    final double[] localBy = new double[MAX_CONTACTS];
    /** preStep 时锚点沿法线的投影间距,位置求解据此推算当前分离量。 */
    final double[] gap0 = new double[MAX_CONTACTS];

    double friction;
    double elasticity;

    boolean seen;

    Arbiter(Shape a, Shape b) {
        this.a = a;
        this.b = b;
        long na = a.id;
        long nb = b.id;
        key = na < nb ? (na << 32) | nb : (nb << 32) | na;
    }

    void begin(Shape shapeA, Shape shapeB) {
        count = 0;
        seen = true;
    }

    /** 保留旧接触的累积冲量用于 warm start(按序号匹配)。 */
    void keepImpulses(double[] oldJn, double[] oldJt, int oldCount) {
        for (int i = 0; i < MAX_CONTACTS; i++) {
            if (i < oldCount) {
                jn[i] = oldJn[i];
                jt[i] = oldJt[i];
            } else {
                jn[i] = 0.0;
                jt[i] = 0.0;
            }
        }
    }

    void preStep(double dt, double slop) {
        friction = a.u * b.u;
        elasticity = a.e * b.e;

        Body ba = a.body;
        Body bb = b.body;

        for (int i = 0; i < count; i++) {
            r1x[i] = px[i] - ba.px;
            r1y[i] = py[i] - ba.py;
            r2x[i] = px[i] - bb.px;
            r2y[i] = py[i] - bb.py;

            // 局部锚点(位置求解用)
            localAx[i] = r1x[i] * ba.cos + r1y[i] * ba.sin;
            localAy[i] = -r1x[i] * ba.sin + r1y[i] * ba.cos;
            localBx[i] = r2x[i] * bb.cos + r2y[i] * bb.sin;
            localBy[i] = -r2x[i] * bb.sin + r2y[i] * bb.cos;
            gap0[i] = ((bb.px + r2x[i]) - (ba.px + r1x[i])) * nx
                    + ((bb.py + r2y[i]) - (ba.py + r1y[i])) * ny;

            double rn1 = r1x[i] * ny - r1y[i] * nx;
            double rn2 = r2x[i] * ny - r2y[i] * nx;
            double kn = ba.invMass + bb.invMass
                    + ba.invMoment * rn1 * rn1 + bb.invMoment * rn2 * rn2;
            massNormal[i] = kn > 0.0 ? 1.0 / kn : 0.0;

            double tx = -ny;
            double ty = nx;
            double rt1 = r1x[i] * ty - r1y[i] * tx;
            double rt2 = r2x[i] * ty - r2y[i] * tx;
            double kt = ba.invMass + bb.invMass
                    + ba.invMoment * rt1 * rt1 + bb.invMoment * rt2 * rt2;
            massTangent[i] = kt > 0.0 ? 1.0 / kt : 0.0;

            // 接触点相对速度 → 反弹速度目标(仅接近速度为负时才反弹)
            double rvx = relativeVelocityX(ba, bb, i);
            double rvy = relativeVelocityY(ba, bb, i);
            double vn = rvx * nx + rvy * ny;
            bounce[i] = vn < 0.0 ? -elasticity * vn : 0.0;

            // warm start:施加上一帧的累积冲量
            applyImpulseToBodies(jn[i], jt[i], i);
        }
    }

    /**
     * 位置求解(分裂冲量):按当前姿态重算接触点分离量,直接修正位置消除穿透。
     * 不改变速度,因此不会像速度纠偏那样注入能量(静止物体不会蠕动)。
     */
    boolean solvePosition(double slop, double beta, double maxCorrection) {
        Body ba = a.body;
        Body bb = b.body;
        boolean moved = false;

        for (int i = 0; i < count; i++) {
            double r1x = localAx[i] * ba.cos - localAy[i] * ba.sin;
            double r1y = localAx[i] * ba.sin + localAy[i] * ba.cos;
            double r2x = localBx[i] * bb.cos - localBy[i] * bb.sin;
            double r2y = localBx[i] * bb.sin + localBy[i] * bb.cos;

            double gapNow = ((bb.px + r2x) - (ba.px + r1x)) * nx
                    + ((bb.py + r2y) - (ba.py + r1y)) * ny;
            double separation = -depth[i] + (gapNow - gap0[i]);
            double error = separation + slop;
            if (error >= 0.0) {
                continue;
            }

            double correction = Math.min(maxCorrection, -error) * beta;
            double rn1 = r1x * ny - r1y * nx;
            double rn2 = r2x * ny - r2y * nx;
            double kn = ba.invMass + bb.invMass
                    + ba.invMoment * rn1 * rn1 + bb.invMoment * rn2 * rn2;
            if (kn <= 0.0) {
                continue;
            }
            double impulse = correction / kn;
            double jx = impulse * nx;
            double jy = impulse * ny;
            ba.applyPositionImpulse(-jx, -jy, r1x, r1y);
            bb.applyPositionImpulse(jx, jy, r2x, r2y);
            moved = true;
        }
        return moved;
    }

    void applyImpulse() {
        Body ba = a.body;
        Body bb = b.body;
        double tx = -ny;
        double ty = nx;
        double maxFriction = friction;

        for (int i = 0; i < count; i++) {
            double rvx = relativeVelocityX(ba, bb, i);
            double rvy = relativeVelocityY(ba, bb, i);

            // 切向(摩擦)
            double vt = rvx * tx + rvy * ty;
            double dJt = massTangent[i] * -vt;
            double maxJt = maxFriction * jn[i];
            double newJt = jt[i] + dJt;
            if (newJt > maxJt) {
                newJt = maxJt;
            } else if (newJt < -maxJt) {
                newJt = -maxJt;
            }
            dJt = newJt - jt[i];
            jt[i] = newJt;
            applyImpulseToBodies(0.0, dJt, i);

            // 法向(含反弹与位置纠偏)
            rvx = relativeVelocityX(ba, bb, i);
            rvy = relativeVelocityY(ba, bb, i);
            double vn = rvx * nx + rvy * ny;
            double dJn = massNormal[i] * (-vn + bounce[i]);
            double newJn = jn[i] + dJn;
            if (newJn < 0.0) {
                newJn = 0.0;
            }
            dJn = newJn - jn[i];
            jn[i] = newJn;
            applyImpulseToBodies(dJn, 0.0, i);
        }
    }

    private void applyImpulseToBodies(double jNormal, double jTangent, int i) {
        double tx = -ny;
        double ty = nx;
        double jx = jNormal * nx + jTangent * tx;
        double jy = jNormal * ny + jTangent * ty;
        b.body.applyImpulse(jx, jy, r2x[i], r2y[i]);
        a.body.applyImpulse(-jx, -jy, r1x[i], r1y[i]);
    }

    private double relativeVelocityX(Body ba, Body bb, int i) {
        double vax = ba.vx - ba.angVel * r1y[i];
        double vbx = bb.vx - bb.angVel * r2y[i];
        return vbx - vax;
    }

    private double relativeVelocityY(Body ba, Body bb, int i) {
        double vay = ba.vy + ba.angVel * r1x[i];
        double vby = bb.vy + bb.angVel * r2x[i];
        return vby - vay;
    }
}
