package com.reandroid.wallpaper.droid.physics;

/**
 * 碰撞形状基类。持有材质(e 弹性 / u 摩擦)与 group:
 * 同 group(非 0)的形状互不碰撞 —— 机器人各部件即用序号作 group,
 * 避免自身部件互相碰撞,同时不同机器人之间仍会碰撞。
 */
public abstract class Shape {

    private static int sNextId = 0;

    final int id;

    Body body;

    /** 弹性系数(0..1)。 */
    double e;
    /** 摩擦系数。 */
    double u;
    /** 碰撞分组,0 表示不分组。 */
    int group;

    /** 世界坐标 AABB。 */
    double bbL;
    double bbB;
    double bbR;
    double bbT;

    Shape(Body body) {
        id = sNextId++;
        this.body = body;
    }

    public void setFriction(double friction) {
        u = friction;
    }

    public void setElasticity(double elasticity) {
        e = elasticity;
    }

    public void setGroup(int group) {
        this.group = group;
    }

    /** 重新计算世界坐标 AABB。 */
    public abstract void cacheBounds();

    boolean boundsOverlap(Shape other) {
        return !(bbR < other.bbL || other.bbR < bbL || bbT < other.bbB || other.bbT < bbB);
    }

    /** 形状面积(用于算转动惯量,退化形状返回 0)。 */
    abstract double area();
}
