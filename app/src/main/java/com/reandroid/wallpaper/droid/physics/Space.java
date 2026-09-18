package com.reandroid.wallpaper.droid.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 物理世界:刚体 + 形状 + 约束,固定步长顺序冲量求解。
 * 宽相用 AABB 两两检测(机器人数量有限,规模足够),
 * 窄相交由 {@link Collide} 完成。
 */
public final class Space {

    /** 求解迭代次数(与原版物理库默认值一致)。 */
    public static final int ITERATIONS = 10;

    /** 允许的穿透量(像素),与原始物理库默认 collisionSlop 一致。 */
    private static final double COLLISION_SLOP = 0.1;

    /** 位置求解迭代次数与每步修正比例(分裂冲量法,不注入能量)。 */
    private static final int POSITION_ITERATIONS = 3;
    private static final double POSITION_BETA = 0.2;
    /** 每步单点最大位置修正量(像素),避免深穿透被一步弹开。 */
    private static final double MAX_POSITION_CORRECTION = 4.0;

    private final List<Body> bodies = new ArrayList<>();
    private final List<Shape> shapes = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();
    private final HashMap<Long, Arbiter> arbiterMap = new HashMap<>();
    private final List<Arbiter> arbiters = new ArrayList<>();

    private final Body staticBody = Body.createStatic();

    /** 复用暂存区:碰撞对每步都要保存旧冲量,不能每次都分配数组(避免 GC 抖动)。 */
    private final double[] oldJn = new double[Arbiter.MAX_CONTACTS];
    private final double[] oldJt = new double[Arbiter.MAX_CONTACTS];

    private double gravityX;
    private double gravityY;

    public Body staticBody() {
        return staticBody;
    }

    public void setGravity(double gx, double gy) {
        gravityX = gx;
        gravityY = gy;
    }

    public double gravityX() {
        return gravityX;
    }

    public double gravityY() {
        return gravityY;
    }

    public void addBody(Body body) {
        bodies.add(body);
    }

    public void addShape(Shape shape) {
        shapes.add(shape);
    }

    public void addConstraint(Constraint constraint) {
        constraints.add(constraint);
    }

    public void removeAll() {
        bodies.clear();
        shapes.clear();
        constraints.clear();
        arbiterMap.clear();
        arbiters.clear();
    }

    public List<Shape> shapes() {
        return shapes;
    }

    public void step(double dt) {
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            body.savePreviousState();
            body.updateVelocity(dt, gravityX, gravityY);
        }
        for (int i = 0; i < constraints.size(); i++) {
            constraints.get(i).preStep(dt);
        }

        collide(dt);

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            // 交替迭代方向:顺序冲量法的固定求解顺序会引入微小不对称,
            // 在静止接触上表现为持续单向蠕动,正反交替可将其抵消。
            boolean forward = (iteration & 1) == 0;
            for (int k = 0; k < arbiters.size(); k++) {
                int i = forward ? k : arbiters.size() - 1 - k;
                arbiters.get(i).applyImpulse();
            }
            for (int k = 0; k < constraints.size(); k++) {
                int i = forward ? k : constraints.size() - 1 - k;
                constraints.get(i).applyImpulse(dt);
            }
        }

        for (int i = 0; i < bodies.size(); i++) {
            bodies.get(i).updatePosition(dt);
        }

        solvePositions();

        for (int i = 0; i < shapes.size(); i++) {
            shapes.get(i).cacheBounds();
        }
        for (int i = 0; i < constraints.size(); i++) {
            constraints.get(i).postStep();
        }
    }

    /** 位置求解:直接修正位置消除穿透(速度不变,不注入能量)。 */
    private void solvePositions() {
        boolean moved = false;
        for (int iteration = 0; iteration < POSITION_ITERATIONS; iteration++) {
            boolean any = false;
            for (int i = 0; i < constraints.size(); i++) {
                if (constraints.get(i).solvePosition(COLLISION_SLOP, POSITION_BETA,
                        MAX_POSITION_CORRECTION)) {
                    any = true;
                }
            }
            for (int i = 0; i < arbiters.size(); i++) {
                if (arbiters.get(i).solvePosition(COLLISION_SLOP, POSITION_BETA, MAX_POSITION_CORRECTION)) {
                    any = true;
                }
            }
            if (!any) {
                break;
            }
            moved = true;
        }
        if (!moved) {
            return;
        }
        for (int i = 0; i < bodies.size(); i++) {
            bodies.get(i).refreshRotation();
        }
    }

    private void collide(double dt) {
        for (int i = 0; i < arbiters.size(); i++) {
            arbiters.get(i).seen = false;
        }

        for (int i = 0; i < shapes.size(); i++) {
            Shape sa = shapes.get(i);
            for (int j = i + 1; j < shapes.size(); j++) {
                Shape sb = shapes.get(j);
                if (sa.body == sb.body) {
                    continue;
                }
                if (sa.group != 0 && sa.group == sb.group) {
                    continue;
                }
                if (sa.body.isStatic() && sb.body.isStatic()) {
                    continue;
                }
                if (!sa.boundsOverlap(sb)) {
                    continue;
                }
                collidePair(sa, sb, dt);
            }
        }

        for (int i = arbiters.size() - 1; i >= 0; i--) {
            Arbiter arbiter = arbiters.get(i);
            if (!arbiter.seen || arbiter.count == 0) {
                arbiterMap.remove(arbiter.key);
                arbiters.remove(i);
            }
        }
    }

    private void collidePair(Shape sa, Shape sb, double dt) {
        Shape first = sa;
        Shape second = sb;
        if (first.id > second.id) {
            first = sb;
            second = sa;
        }
        // 上面已保证 first.id <= second.id，两个分支结果相同，取一个即可
        long key = ((long) first.id << 32) | (second.id & 0xffffffffL);

        Arbiter arbiter = arbiterMap.get(key);
        if (arbiter == null) {
            arbiter = new Arbiter(first, second);
            arbiterMap.put(key, arbiter);
            arbiters.add(arbiter);
        }

        int oldCount = arbiter.count;
        System.arraycopy(arbiter.jn, 0, oldJn, 0, Arbiter.MAX_CONTACTS);
        System.arraycopy(arbiter.jt, 0, oldJt, 0, Arbiter.MAX_CONTACTS);

        arbiter.seen = true;
        arbiter.count = 0;
        if (!Collide.collide(first, second, arbiter)) {
            arbiter.count = 0;
            return;
        }
        arbiter.keepImpulses(oldJn, oldJt, oldCount);
        arbiter.preStep(dt, COLLISION_SLOP);
    }
}
