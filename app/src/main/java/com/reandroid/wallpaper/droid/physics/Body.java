package com.reandroid.wallpaper.droid.physics;

/**
 * 2D 刚体(仿 Chipmunk 语义):质量 / 转动惯量、位置、速度,缓存旋转三角函数。
 * 静态刚体以 invMass = invMoment = 0 表示(墙)。
 */
public final class Body {

    private static int sNextId = 0;

    final int id;

    double px;
    double py;
    double vx;
    double vy;
    double angle;
    double angVel;
    double fx;
    double fy;
    double torque;

    double mass;
    double invMass;
    double moment;
    double invMoment;

    /** 缓存的 cos(angle) / sin(angle)。 */
    double cos = 1.0;
    double sin = 0.0;

    /** 上一物理步的位置 / 角度:供渲染插值使用(物理仍为固定步长)。 */
    double prevPx;
    double prevPy;
    double prevAngle;

    Body(double mass, double moment) {
        id = sNextId++;
        this.mass = mass;
        this.invMass = mass > 0.0 ? 1.0 / mass : 0.0;
        this.moment = moment;
        this.invMoment = moment > 0.0 ? 1.0 / moment : 0.0;
    }

    /** 动态刚体,mass > 0,moment 由形状算出。 */
    public static Body create(double mass, double moment) {
        return new Body(Math.max(0.0, mass), Math.max(1e-9, moment));
    }

    /** 静态刚体(墙):不受力、不动。 */
    public static Body createStatic() {
        return new Body(0.0, 0.0);
    }

    public boolean isStatic() {
        return invMass == 0.0 && invMoment == 0.0;
    }

    public void setPosition(double x, double y) {
        px = x;
        py = y;
        prevPx = x;
        prevPy = y;
    }

    public void setAngle(double a) {
        angle = a;
        prevAngle = a;
        cos = Math.cos(a);
        sin = Math.sin(a);
    }

    /** 物理步进前保存上一步状态,供渲染插值。 */
    void savePreviousState() {
        prevPx = px;
        prevPy = py;
        prevAngle = angle;
    }

    /** 插值渲染坐标(alpha:0 = 上一物理步,1 = 当前物理步)。 */
    public double renderX(double alpha) {
        return prevPx + (px - prevPx) * alpha;
    }

    public double renderY(double alpha) {
        return prevPy + (py - prevPy) * alpha;
    }

    /** 插值角度:按最短路径回绕,避免快速自旋时插值绕远。 */
    public double renderAngle(double alpha) {
        double delta = angle - prevAngle;
        while (delta > Math.PI) {
            delta -= Math.PI * 2.0;
        }
        while (delta < -Math.PI) {
            delta += Math.PI * 2.0;
        }
        return prevAngle + delta * alpha;
    }

    public double x() {
        return px;
    }

    public double y() {
        return py;
    }

    public double angle() {
        return angle;
    }

    /** pos + rot(v):把局部坐标 v 转到世界坐标。 */
    double worldX(double lx, double ly) {
        return px + lx * cos - ly * sin;
    }

    double worldY(double lx, double ly) {
        return py + lx * sin + ly * cos;
    }

    void updateVelocity(double dt, double gravityX, double gravityY) {
        if (isStatic()) {
            return;
        }
        vx += (gravityX + fx * invMass) * dt;
        vy += (gravityY + fy * invMass) * dt;
        angVel += torque * invMoment * dt;
        fx = 0.0;
        fy = 0.0;
        torque = 0.0;
    }

    void updatePosition(double dt) {
        if (isStatic()) {
            return;
        }
        px += vx * dt;
        py += vy * dt;
        angle += angVel * dt;
        cos = Math.cos(angle);
        sin = Math.sin(angle);
    }

    /** 在相对质心 r 处施加冲量 j(均为世界坐标)。 */
    public void applyImpulse(double jx, double jy, double rx, double ry) {
        vx += jx * invMass;
        vy += jy * invMass;
        angVel += (rx * jy - ry * jx) * invMoment;
    }

    /**
     * 位置修正冲量(分裂冲量法):只改变位置 / 角度,不改变速度,
     * 因此消除穿透的同时不会注入能量。
     */
    void applyPositionImpulse(double jx, double jy, double rx, double ry) {
        px += jx * invMass;
        py += jy * invMass;
        angle += (rx * jy - ry * jx) * invMoment;
    }

    /** 位置修正后刷新缓存的三角函数。 */
    void refreshRotation() {
        cos = Math.cos(angle);
        sin = Math.sin(angle);
    }

    /** 该点相对质心的位置(世界坐标)。 */
    double offsetX(double wx) {
        return wx - px;
    }

    double offsetY(double wy) {
        return wy - py;
    }
}
