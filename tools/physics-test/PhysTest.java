/*
 * 物理内核回归测试(纯 JVM,无需 Android 设备):
 *
 *   javac -d /tmp/phystest $(find app/src/main/java/com/reandroid/wallpaper/droid/physics -name "*.java")  *         tools/physics-test/PhysTest.java
 *   java -cp /tmp/phystest PhysTest
 *
 * 覆盖:落地静止高度、不穿透地板/墙体、铰链不松开、多机堆叠不互穿不下陷、
 *      触摸径向冲量方向、长时间静置的关节漂移收敛性。
 */
import com.reandroid.wallpaper.droid.physics.Body;
import com.reandroid.wallpaper.droid.physics.PivotJoint;
import com.reandroid.wallpaper.droid.physics.PolyShape;
import com.reandroid.wallpaper.droid.physics.RotaryLimitJoint;
import com.reandroid.wallpaper.droid.physics.SegmentShape;
import com.reandroid.wallpaper.droid.physics.SimpleMotor;
import com.reandroid.wallpaper.droid.physics.Space;

import java.util.ArrayList;
import java.util.List;

/** 物理内核的桌面验证:跌落 / 关节保持 / 堆叠 / 触摸冲量。 */
public final class PhysTest {

    private static final double DT = 1.0 / 30.0;
    private static final double SCREEN_W = 480.0;
    private static final double SCREEN_H = 800.0;
    private static final double BODY_W = 50.0;
    private static final double BODY_H = 71.5;

    private static final double ARM_POS_X = 0.598802395;
    private static final double ARM_POS_Y = 0.138888889;
    private static final double ARM_HALF_LEN = 0.25;
    private static final double LEG_POS_X = 0.222222222;
    private static final double LEG_POS_Y = 0.571428571;
    private static final double LEG_HALF_LEN = 0.166666667;
    private static final double LEG_PIVOT_Y = 0.444444444;
    private static final double LIMB_BOX_WIDTH = 0.149925037;

    private static int failures = 0;

    public static void main(String[] args) {
        testSingleDroidFallsAndRests();
        testJointHoldsLimbs();
        testMultipleDroidsStack();
        testTouchImpulse();
        testLongRunStability();
        testRenderInterpolation();
        testAngleInterpolationWraps();
        testTouchDirectionAllSides();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : ("FAILURES: " + failures));
        if (failures != 0) {
            System.exit(1);
        }
    }

    // --- 场景搭建(与原版 addDroid 常量一致) ---

    /** 四肢:记录初始锚点相对偏移,便于校验铰链是否松开。 */
    private static final class Limb {
        Body body;
        double offsetX;
        double offsetY;
        double anchorFx;
        double anchorFy;
    }

    private static final class Droid {
        Body body;
        List<Limb> limbs = new ArrayList<>();
    }

    private static Space newSpace() {
        Space space = new Space();
        space.setGravity(0.0, -300.0);
        double hw = SCREEN_W * 0.5;
        double hh = SCREEN_H * 0.5;
        addWall(space, -hw - 500, hh, hw + 500, hh + 500);
        addWall(space, -hw - 500, -hh - 500, hw + 500, -hh);
        addWall(space, -hw - 500, -hh, -hw, hh);
        addWall(space, hw, -hh, hw + 500, hh);
        return space;
    }

    private static void addWall(Space space, double x0, double y0, double x1, double y1) {
        PolyShape shape = PolyShape.create(space.staticBody(),
                new double[] {x0, y0, x0, y1, x1, y1, x1, y0});
        shape.setElasticity(1.0);
        shape.setFriction(1.0);
        space.addShape(shape);
    }

    private static Droid addDroid(Space space, int index, double x, double y) {
        Droid droid = new Droid();

        Body body = Body.create(1.0, PolyShape.momentForBox(1.0, BODY_W, BODY_H));
        body.setPosition(x, y);
        space.addBody(body);
        PolyShape bodyShape = PolyShape.createBox(body, BODY_W, BODY_H);
        bodyShape.setElasticity(0.0);
        bodyShape.setFriction(0.7);
        bodyShape.setGroup(index);
        space.addShape(bodyShape);
        droid.body = body;

        addLimb(space, droid, index, body, -ARM_POS_X, -ARM_POS_Y, -ARM_POS_X, 0.0,
                ARM_HALF_LEN, false);
        addLimb(space, droid, index, body, ARM_POS_X, -ARM_POS_Y, ARM_POS_X, 0.0,
                ARM_HALF_LEN, false);
        addLimb(space, droid, index, body, -LEG_POS_X, -LEG_POS_Y, -LEG_POS_X, -LEG_PIVOT_Y,
                LEG_HALF_LEN, true);
        addLimb(space, droid, index, body, LEG_POS_X, -LEG_POS_Y, LEG_POS_X, -LEG_PIVOT_Y,
                LEG_HALF_LEN, true);

        SimpleMotor bodyMotor = SimpleMotor.create(space.staticBody(), body, 0.0);
        bodyMotor.setMaxForce(0.0);
        space.addConstraint(bodyMotor);
        return droid;
    }

    private static void addLimb(Space space, Droid droid, int index, Body owner,
            double posX, double posY, double anchorX, double anchorY,
            double halfLen, boolean leg) {
        double limbX = owner.x() + posX * BODY_W;
        double limbY = owner.y() + posY * BODY_H;
        double anchorWorldX = owner.x() + anchorX * BODY_W;
        double anchorWorldY = owner.y() + anchorY * BODY_H;

        Body limb = Body.create(1.0,
                PolyShape.momentForBox(1.0, LIMB_BOX_WIDTH * BODY_W, halfLen * 2.0 * BODY_H));
        limb.setPosition(limbX, limbY);
        space.addBody(limb);

        SegmentShape shape = SegmentShape.create(limb, 0.0, -halfLen * BODY_H, 0.0, halfLen * BODY_H);
        shape.setElasticity(0.0);
        shape.setFriction(0.7);
        shape.setGroup(index);
        space.addShape(shape);

        space.addConstraint(PivotJoint.createAtWorldAnchor(limb, owner, anchorWorldX, anchorWorldY));
        if (leg) {
            space.addConstraint(RotaryLimitJoint.create(owner, limb, -1.0, 1.0));
        }

        Limb record = new Limb();
        record.body = limb;
        record.offsetX = anchorWorldX - limbX;
        record.offsetY = anchorWorldY - limbY;
        record.anchorFx = anchorX;
        record.anchorFy = anchorY;
        droid.limbs.add(record);
    }

    // --- 测试 ---

    private static void testSingleDroidFallsAndRests() {
        Space space = newSpace();
        Droid droid = addDroid(space, 1, 0.0, 300.0);

        for (int i = 0; i < 300; i++) {
            space.step(DT);
            checkFinite("single droid", droid);
        }

        double expectedY = -(SCREEN_H * 0.5 - BODY_H * 0.5);
        report("落在画面底部并静止 (y=" + fmt(droid.body.y()) + ", 期望≈" + fmt(expectedY) + ")",
                Math.abs(droid.body.y() - expectedY) < 8.0);
        report("未穿透地板 (y=" + fmt(droid.body.y()) + ")",
                droid.body.y() > expectedY - 8.0);
        report("未穿出左右墙 (x=" + fmt(droid.body.x()) + ")",
                Math.abs(droid.body.x()) < SCREEN_W * 0.5 + 5.0);
    }

    private static void testJointHoldsLimbs() {
        Space space = newSpace();
        Droid droid = addDroid(space, 1, 0.0, 300.0);

        double maxDrift = 0.0;
        for (int i = 0; i < 300; i++) {
            space.step(DT);
            for (Limb limb : droid.limbs) {
                double anchorX = droid.body.x() + limb.anchorFx * BODY_W;
                double anchorY = droid.body.y() + limb.anchorFy * BODY_H;
                double cos = Math.cos(limb.body.angle());
                double sin = Math.sin(limb.body.angle());
                double limbAnchorX = limb.body.x() + limb.offsetX * cos - limb.offsetY * sin;
                double limbAnchorY = limb.body.y() + limb.offsetX * sin + limb.offsetY * cos;
                double dx = anchorX - limbAnchorX;
                double dy = anchorY - limbAnchorY;
                maxDrift = Math.max(maxDrift, Math.sqrt(dx * dx + dy * dy));
            }
        }
        report("铰链未松开 (最大锚点漂移=" + fmt(maxDrift) + "px)", maxDrift < 6.0);
    }

    private static void testMultipleDroidsStack() {
        Space space = newSpace();
        List<Droid> droids = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            droids.add(addDroid(space, i, (i - 4.5) * 26.0, 250.0 + i * 30.0));
        }
        for (int step = 0; step < 1800; step++) {
            space.step(DT);
            for (Droid droid : droids) {
                checkFinite("multi droid", droid);
            }
        }

        double lowestY = 0.0;
        for (Droid droid : droids) {
            lowestY = Math.min(lowestY, droid.body.y());
        }
        report("堆叠 60s 后未陷入地板 (最低 y=" + fmt(lowestY) + ")",
                lowestY > -(SCREEN_H * 0.5 - BODY_H * 0.5) - 6.0);

        double maxOverlap = 0.0;
        for (int i = 0; i < droids.size(); i++) {
            Body a = droids.get(i).body;
            report("机器人 " + i + " 未飞出场景 (x=" + fmt(a.x()) + ", y=" + fmt(a.y()) + ")",
                    a.y() > -(SCREEN_H * 0.5) - 60.0 && Math.abs(a.x()) < SCREEN_W * 0.5 + 60.0);
            for (int j = i + 1; j < droids.size(); j++) {
                Body b = droids.get(j).body;
                double overlapX = BODY_W - Math.abs(a.x() - b.x());
                double overlapY = BODY_H - Math.abs(a.y() - b.y());
                if (overlapX > 0 && overlapY > 0) {
                    maxOverlap = Math.max(maxOverlap, Math.min(overlapX, overlapY));
                }
            }
        }
        report("堆叠时未深度互穿 (最大重叠=" + fmt(maxOverlap) + "px)", maxOverlap < 20.0);
    }

    private static void testTouchImpulse() {
        Space space = newSpace();
        Droid droid = addDroid(space, 1, 0.0, 350.0);
        // 空中施加:避免地面摩擦吃掉水平速度
        for (int i = 0; i < 15; i++) {
            space.step(DT);
        }

        double xBefore = droid.body.x();
        double touchX = -100.0;
        double touchY = droid.body.y();
        double strength = 50.0;
        double magnitudeBase = (int) (strength / 5.0) * 500.0;
        double dx = droid.body.x() - touchX;
        double dy = droid.body.y() - touchY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        int ax = (int) Math.abs(dx);
        int ay = (int) Math.abs(dy);
        double ix;
        double iy;
        if (ax > ay) {
            ix = dx < 0 ? -1 : 1;
            double ratio = ay / ax;
            iy = dy < 0 ? -ratio : ratio;
        } else {
            iy = dy < 0 ? -1 : 1;
            double ratio = ax / ay;
            ix = dx < 0 ? -ratio : ratio;
        }
        double magnitude = magnitudeBase / dist;
        droid.body.applyImpulse(ix * magnitude, iy * magnitude, 0.0, 0.0);

        for (int i = 0; i < 15; i++) {
            space.step(DT);
        }
        report("触摸冲量把机器人推离触点 (Δx=" + fmt(droid.body.x() - xBefore) + ")",
                droid.body.x() - xBefore > 5.0);
    }

    /** 长跑稳定性:静置后关节漂移不得随时间发散(落地瞬间的甩动不算)。 */
    private static void testLongRunStability() {
        Space space = newSpace();
        Droid droid = addDroid(space, 1, 0.0, 300.0);
        double settled = 0.0;
        double half = 0.0;
        for (int step = 0; step < 3000; step++) {
            space.step(DT);
            if (step == 1499) {
                half = currentMaxDrift(droid);
            }
            if (step >= 1500) {
                settled = Math.max(settled, currentMaxDrift(droid));
            }
        }
        report("静置后关节漂移不发散 (50s=" + fmt(half) + " 100s=" + fmt(settled) + "px)",
                settled < 3.0 && settled < half + 2.0);
    }

    /** 渲染插值:端点为上下两步状态,中点取平均,且随时间单调。 */
    private static void testRenderInterpolation() {
        Space space = newSpace();
        Droid droid = addDroid(space, 1, 0.0, 300.0);
        for (int i = 0; i < 5; i++) {
            space.step(DT);
        }
        double prevY = droid.body.renderY(0.0);
        double curY = droid.body.renderY(1.0);
        double midY = droid.body.renderY(0.5);
        report("插值端点/中点正确 (prev=" + fmt(prevY) + " mid=" + fmt(midY)
                        + " cur=" + fmt(curY) + ")",
                Math.abs(midY - (prevY + curY) * 0.5) < 1e-9 && Math.abs(curY - prevY) > 1e-6);

        boolean monotonic = true;
        double last = Double.MAX_VALUE;
        for (int k = 0; k <= 10; k++) {
            double v = droid.body.renderY(k / 10.0);
            if (v > last + 1e-9) {
                monotonic = false;
            }
            last = v;
        }
        report("插值单调(下落中 y 单调递减)", monotonic);

        double prevX = droid.body.renderX(0.0);
        double curX = droid.body.renderX(1.0);
        double midX = droid.body.renderX(0.5);
        report("X 插值端点/中点正确", Math.abs(midX - (prevX + curX) * 0.5) < 1e-9);
    }

    /** 角度插值需按最短路径回绕:每步转角超过 π 时不能倒转。 */
    private static void testAngleInterpolationWraps() {
        Space space = new Space();
        Body body = Body.create(1.0, 1.0);
        body.setPosition(0.0, 0.0);
        space.addBody(body);
        // 转速 200 rad/s ≈ 6.67 rad/步(> π),不做回绕处理时插值会向反方向跳
        space.addConstraint(SimpleMotor.create(space.staticBody(), body, 200.0));
        for (int i = 0; i < 10; i++) {
            space.step(DT);
        }
        boolean monotonic = true;
        double last = body.renderAngle(0.0);
        for (int k = 1; k <= 10; k++) {
            double v = body.renderAngle(k / 10.0);
            if (v <= last) {
                monotonic = false;
            }
            last = v;
        }
        double sweep = body.renderAngle(1.0) - body.renderAngle(0.0);
        report("快速自旋时角度插值不回绕 (单步插值跨度=" + fmt(sweep) + " rad, 应 < π)",
                monotonic && Math.abs(sweep) < Math.PI);
    }

    /**
     * 触摸链路端到端:屏幕坐标 → 世界坐标(与 DroidGL.onTouchEvent 同式)→ 径向冲量,
     * 验证四个方向都是"推离触点"(与 x86 版原版反编译一致)。
     */
    private static void testTouchDirectionAllSides() {
        // 机器人位于世界原点 = 屏幕中心;参数为触点相对机器人的屏幕偏移
        checkTouchSide("触点在上方 → 机器人向下", 0.0, -120.0, 0.0, -1.0);
        checkTouchSide("触点在下方 → 机器人向上", 0.0, 120.0, 0.0, 1.0);
        checkTouchSide("触点在左方 → 机器人向右", -120.0, 0.0, 1.0, 0.0);
        checkTouchSide("触点在右方 → 机器人向左", 120.0, 0.0, -1.0, 0.0);
    }

    private static void checkTouchSide(String label, double offsetScreenX, double offsetScreenY,
            double expectedDx, double expectedDy) {
        Space space = newSpace();
        space.setGravity(0.0, 0.0);
        Droid droid = addDroid(space, 1, 0.0, 0.0);

        // 与 DroidGL.onTouchEvent 同式:worldX = screenX - w/2,worldY = h/2 - screenY
        double screenX = SCREEN_W * 0.5 + offsetScreenX;
        double screenY = SCREEN_H * 0.5 + offsetScreenY;
        double worldX = screenX - SCREEN_W * 0.5;
        double worldY = SCREEN_H * 0.5 - screenY;
        applyRadialImpulse(worldX, worldY, 50.0, droid);

        for (int i = 0; i < 15; i++) {
            space.step(DT);
        }
        double movedX = droid.body.x();
        double movedY = droid.body.y();
        double alongExpected = movedX * expectedDx + movedY * expectedDy;
        report(label + " (Δ=" + fmt(movedX) + "," + fmt(movedY) + ")", alongExpected > 2.0);
    }

    /** 与 DroidScene.applyRadialImpulse 同式(整数截断归一化 + (r/5)*500/dist)。 */
    private static void applyRadialImpulse(double x, double y, double strength, Droid droid) {
        double magnitudeBase = (int) (strength / 5.0) * 500.0;
        double dx = droid.body.x() - x;
        double dy = droid.body.y() - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        int ax = (int) Math.abs(dx);
        int ay = (int) Math.abs(dy);
        double ix;
        double iy;
        if (ax > ay) {
            ix = dx < 0.0 ? -1.0 : 1.0;
            double ratio = ay / ax;
            iy = dy < 0.0 ? -ratio : ratio;
        } else {
            iy = dy < 0.0 ? -1.0 : 1.0;
            double ratio = ax / ay;
            ix = dx < 0.0 ? -ratio : ratio;
        }
        double magnitude = magnitudeBase / dist;
        droid.body.applyImpulse(ix * magnitude, iy * magnitude, 0.0, 0.0);
    }

    private static double currentMaxDrift(Droid droid) {
        double max = 0.0;
        for (Limb limb : droid.limbs) {
            double anchorX = droid.body.x() + limb.anchorFx * BODY_W;
            double anchorY = droid.body.y() + limb.anchorFy * BODY_H;
            double cos = Math.cos(limb.body.angle());
            double sin = Math.sin(limb.body.angle());
            double limbAnchorX = limb.body.x() + limb.offsetX * cos - limb.offsetY * sin;
            double limbAnchorY = limb.body.y() + limb.offsetX * sin + limb.offsetY * cos;
            double dx = anchorX - limbAnchorX;
            double dy = anchorY - limbAnchorY;
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy));
        }
        return max;
    }

    // --- 断言工具 ---

    private static void checkFinite(String label, Droid droid) {
        if (!isFinite(droid.body.x()) || !isFinite(droid.body.y()) || !isFinite(droid.body.angle())) {
            report(label + " 躯干数值有限", false);
            return;
        }
        for (Limb limb : droid.limbs) {
            if (!isFinite(limb.body.x()) || !isFinite(limb.body.y()) || !isFinite(limb.body.angle())) {
                report(label + " 四肢数值有限", false);
                return;
            }
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static void report(String what, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
