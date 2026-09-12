package com.reandroid.wallpaper.droid;

import android.content.SharedPreferences;

import com.reandroid.wallpaper.droid.physics.Body;
import com.reandroid.wallpaper.droid.physics.PivotJoint;
import com.reandroid.wallpaper.droid.physics.PolyShape;
import com.reandroid.wallpaper.droid.physics.RotaryLimitJoint;
import com.reandroid.wallpaper.droid.physics.SegmentShape;
import com.reandroid.wallpaper.droid.physics.SimpleMotor;
import com.reandroid.wallpaper.droid.physics.Space;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 坠落的安卓机器人(Shake Them All)—— 纯逻辑场景,零 GL 依赖。
 *
 * 物理为自实现的 2D 刚体求解(顺序冲量 + warm start),几何 / 材质 / 约束
 * 均按原版 libshakethemall.so 逆向结果配置:
 * <ul>
 *   <li>躯干:矩形刚体,质量 1,弹性 0,摩擦 0.7,group = 机器人序号(自身部件不互撞)</li>
 *   <li>手臂:两侧 (±0.5988w, -0.1389h),铰接于 (±0.5988w, y),由马达驱动摆动</li>
 *   <li>腿部:中下 (±0.2222w, -0.5714h),铰接于 (±0.2222w, -0.4444h),带 ±1rad 限位</li>
 *   <li>场景四周 500px 厚静态墙,弹性 1.0 / 摩擦 1.0</li>
 *   <li>固定 30Hz 步进</li>
 * </ul>
 */
final class DroidScene {

    private static final double FIXED_DT = 1.0 / 30.0;
    private static final double MAX_ACCUMULATED = 0.25;
    private static final int MAX_STEPS_PER_FRAME = 8;
    private static final long PREF_POLL_INTERVAL_MS = 1000L;

    /** 原版皮肤位图尺寸(未缩放)。 */
    static final int SRC_BODY_W = 100;
    static final int SRC_BODY_H = 143;
    static final int SRC_ARM_W = 30;
    static final int SRC_ARM_H = 69;
    static final int SRC_LEG_W = 30;
    static final int SRC_LEG_H = 48;

    /** 原版几何比例(addDroid 常量)。 */
    private static final double ARM_POS_X = 0.598802395;
    private static final double ARM_POS_Y = 0.138888889;
    private static final double ARM_HALF_LEN = 0.25;
    private static final double ARM_PIVOT_Y = 0.0;

    private static final double LEG_POS_X = 0.222222222;
    private static final double LEG_POS_Y = 0.571428571;
    private static final double LEG_HALF_LEN = 0.166666667;
    private static final double LEG_PIVOT_Y = 0.444444444;

    private static final double LIMB_BOX_WIDTH = 0.149925037;
    private static final double LEG_LIMIT = 1.0;

    private static final double WALL_THICKNESS = 500.0;
    private static final double WALL_ELASTICITY = 1.0;
    private static final double WALL_FRICTION = 1.0;
    private static final double DROID_ELASTICITY = 0.0;
    private static final double DROID_FRICTION = 0.7;

    /** 径向冲量强度系数(原版 applyRadialImpulse 中的 500)。 */
    private static final double RADIAL_IMPULSE_SCALE = 500.0;

    static final String PREF_COUNT = "droid_count";
    static final String PREF_SIZE = "droid_size";
    static final String PREF_GRAVITY = "droid_gravity";
    static final String PREF_SHAKE = "droid_shake";
    static final String PREF_TOUCH = "droid_touch";
    static final String PREF_LIGHT = "droid_light";
    static final String PREF_COLOR = "droid_color";
    static final String PREF_BG_COLOR = "droid_bg_color";

    static final int DEFAULT_COUNT = 10;
    static final int DEFAULT_SIZE = 50;
    static final int DEFAULT_GRAVITY = 50;
    static final int DEFAULT_SHAKE = 0;
    static final int DEFAULT_TOUCH = 50;
    static final int DEFAULT_LIGHT = 0;
    static final int DEFAULT_COLOR = 0xFF97C03D;
    /** 原版默认背景色 rgb(48, 88, 124)。 */
    static final int DEFAULT_BG_COLOR = 0xFF30587C;

    static final int MIN_COUNT = 1;
    static final int MAX_COUNT = 30;
    static final int MIN_SIZE = 20;
    static final int MAX_SIZE = 100;

    /** 单个机器人的刚体与马达(部件可为 null,取决于皮肤)。 */
    static final class Droid {
        Body body;
        Body leftArm;
        Body rightArm;
        Body leftLeg;
        Body rightLeg;
        SimpleMotor bodyMotor;
        SimpleMotor leftArmMotor;
        SimpleMotor rightArmMotor;
    }

    private final Space space = new Space();
    private final List<Droid> droids = new ArrayList<>();
    private final Random random = new Random();

    private SharedPreferences prefs;
    private int currentCount = DEFAULT_COUNT;
    private int currentSize = DEFAULT_SIZE;

    private boolean leftArmAvailable = true;
    private boolean rightArmAvailable = true;
    private boolean leftLegAvailable = true;
    private boolean rightLegAvailable = true;

    private int width;
    private int height;

    private double accumulator;
    private long lastTimeMs;
    private long lastPrefPollMs = Long.MIN_VALUE;

    private double gravityFactor = DEFAULT_GRAVITY;
    private double touchFactor = DEFAULT_TOUCH;

    DroidScene(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        rebuild(currentCount, currentSize);
    }

    void setPluginPrefs(SharedPreferences prefs) {
        this.prefs = prefs;
        lastPrefPollMs = Long.MIN_VALUE;
    }

    void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == this.width && height == this.height) {
            return;
        }
        this.width = width;
        this.height = height;
        rebuild(currentCount, currentSize);
    }

    /** 皮肤缺少某部件位图时对应刚体不创建(与原版 hasLeftArm 等参数一致)。 */
    void setAvailableParts(boolean leftArm, boolean rightArm, boolean leftLeg, boolean rightLeg) {
        if (leftArmAvailable == leftArm && rightArmAvailable == rightArm
                && leftLegAvailable == leftLeg && rightLegAvailable == rightLeg) {
            return;
        }
        leftArmAvailable = leftArm;
        rightArmAvailable = rightArm;
        leftLegAvailable = leftLeg;
        rightLegAvailable = rightLeg;
        rebuild(currentCount, currentSize);
    }

    List<Droid> droids() {
        return droids;
    }

    int droidCount() {
        return droids.size();
    }

    /** 机器人缩放比例(0.2 ~ 1.0)。 */
    double sizeScale() {
        return currentSize / 100.0;
    }

    /**
     * 渲染插值系数:0 = 上一物理步状态,1 = 当前物理步状态。
     * 物理仍是固定 30Hz(与原版一致),仅让高刷新率下的画面平滑。
     */
    double interpolationAlpha() {
        double alpha = accumulator / FIXED_DT;
        if (alpha < 0.0) {
            return 0.0;
        }
        return alpha > 1.0 ? 1.0 : alpha;
    }

    double touchFactor() {
        return touchFactor;
    }

    // --- 每帧推进 ---

    void update(long timeMs) {
        if (lastTimeMs == 0L) {
            lastTimeMs = timeMs;
        }
        double frameTime = (timeMs - lastTimeMs) * 0.001;
        lastTimeMs = timeMs;
        if (frameTime < 0.0) {
            frameTime = 0.0;
        } else if (frameTime > MAX_ACCUMULATED) {
            frameTime = MAX_ACCUMULATED;
        }

        pollPrefs(timeMs);

        accumulator += frameTime;
        int steps = 0;
        while (accumulator >= FIXED_DT && steps < MAX_STEPS_PER_FRAME) {
            space.step(FIXED_DT);
            accumulator -= FIXED_DT;
            steps++;
        }
        if (steps >= MAX_STEPS_PER_FRAME) {
            accumulator = 0.0;
        }
    }

    private void pollPrefs(long timeMs) {
        if (prefs == null) {
            return;
        }
        if (lastPrefPollMs != Long.MIN_VALUE && timeMs - lastPrefPollMs < PREF_POLL_INTERVAL_MS) {
            return;
        }
        lastPrefPollMs = timeMs;

        int count = clamp(prefs.getInt(PREF_COUNT, DEFAULT_COUNT), MIN_COUNT, MAX_COUNT);
        int size = clamp(prefs.getInt(PREF_SIZE, DEFAULT_SIZE), MIN_SIZE, MAX_SIZE);
        gravityFactor = clamp(prefs.getInt(PREF_GRAVITY, DEFAULT_GRAVITY), 0, 100);
        touchFactor = clamp(prefs.getInt(PREF_TOUCH, DEFAULT_TOUCH), 0, 100);
        if (count != currentCount || size != currentSize) {
            rebuild(count, size);
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    // --- 场景装配 ---

    private void rebuild(int count, int size) {
        currentCount = clamp(count, MIN_COUNT, MAX_COUNT);
        currentSize = clamp(size, MIN_SIZE, MAX_SIZE);
        space.removeAll();
        droids.clear();
        accumulator = 0.0;

        buildWalls();

        double scale = currentSize / 100.0;
        double bodyW = SRC_BODY_W * scale;
        double bodyH = SRC_BODY_H * scale;
        double spanX = Math.max(1.0, width - bodyW);
        double spanY = Math.max(1.0, height - bodyH);

        for (int i = 1; i <= currentCount; i++) {
            double x = random.nextDouble() * spanX - spanX * 0.5;
            double y = random.nextDouble() * spanY - spanY * 0.5;
            droids.add(addDroid(i, x, y, bodyW, bodyH));
        }
    }

    /** 四面静态墙:内侧面贴合屏幕边界,向外延伸 500px(与原版 init 一致)。 */
    private void buildWalls() {
        double hw = width * 0.5;
        double hh = height * 0.5;
        Body wall = space.staticBody();
        addWallShape(wall, -hw - WALL_THICKNESS, hh, hw + WALL_THICKNESS, hh + WALL_THICKNESS);
        addWallShape(wall, -hw - WALL_THICKNESS, -hh - WALL_THICKNESS, hw + WALL_THICKNESS, -hh);
        addWallShape(wall, -hw - WALL_THICKNESS, -hh, -hw, hh);
        addWallShape(wall, hw, -hh, hw + WALL_THICKNESS, hh);
    }

    private void addWallShape(Body wall, double x0, double y0, double x1, double y1) {
        PolyShape shape = PolyShape.create(wall, new double[] {
                x0, y0,
                x0, y1,
                x1, y1,
                x1, y0
        });
        shape.setElasticity(WALL_ELASTICITY);
        shape.setFriction(WALL_FRICTION);
        space.addShape(shape);
    }

    private Droid addDroid(int index, double x, double y, double w, double h) {
        Droid droid = new Droid();

        Body body = Body.create(1.0, PolyShape.momentForBox(1.0, w, h));
        body.setPosition(x, y);
        space.addBody(body);
        PolyShape bodyShape = PolyShape.createBox(body, w, h);
        bodyShape.setElasticity(DROID_ELASTICITY);
        bodyShape.setFriction(DROID_FRICTION);
        bodyShape.setGroup(index);
        space.addShape(bodyShape);
        droid.body = body;

        double armW = LIMB_BOX_WIDTH * w;
        double armBoxH = ARM_HALF_LEN * 2.0 * h;
        double legBoxH = LEG_HALF_LEN * 2.0 * h;

        if (leftArmAvailable) {
            droid.leftArm = addLimb(droid, index, w, h, -ARM_POS_X, -ARM_POS_Y, -ARM_POS_X, -ARM_PIVOT_Y,
                    ARM_HALF_LEN, armW, armBoxH, false);
        }
        if (rightArmAvailable) {
            droid.rightArm = addLimb(droid, index, w, h, ARM_POS_X, -ARM_POS_Y, ARM_POS_X, -ARM_PIVOT_Y,
                    ARM_HALF_LEN, armW, armBoxH, false);
        }
        if (leftLegAvailable) {
            droid.leftLeg = addLimb(droid, index, w, h, -LEG_POS_X, -LEG_POS_Y, -LEG_POS_X, -LEG_PIVOT_Y,
                    LEG_HALF_LEN, armW, legBoxH, true);
        }
        if (rightLegAvailable) {
            droid.rightLeg = addLimb(droid, index, w, h, LEG_POS_X, -LEG_POS_Y, LEG_POS_X, -LEG_PIVOT_Y,
                    LEG_HALF_LEN, armW, legBoxH, true);
        }

        // 全身马达(初始停转):摇晃时驱动自旋
        droid.bodyMotor = SimpleMotor.create(space.staticBody(), body, 0.0);
        droid.bodyMotor.setMaxForce(0.0);
        space.addConstraint(droid.bodyMotor);

        // 双臂马达(初始停转):光照强度驱动,左右反向
        if (droid.leftArm != null) {
            droid.leftArmMotor = SimpleMotor.create(body, droid.leftArm, 0.0);
            droid.leftArmMotor.setMaxForce(0.0);
            space.addConstraint(droid.leftArmMotor);
        }
        if (droid.rightArm != null) {
            droid.rightArmMotor = SimpleMotor.create(body, droid.rightArm, 0.0);
            droid.rightArmMotor.setMaxForce(0.0);
            space.addConstraint(droid.rightArmMotor);
        }
        return droid;
    }

    /**
     * 创建四肢刚体 + 线段形状 + 铰链(腿部额外加 ±1rad 限位)。
     * 位置与锚点均以躯干中心为原点、按原版比例换算。
     */
    private Body addLimb(Droid droid, int index, double w, double h,
            double posFx, double posFy, double anchorFx, double anchorFy,
            double halfLen, double boxW, double boxH, boolean leg) {
        Body owner = droid.body;
        double limbX = owner.x() + posFx * w;
        double limbY = owner.y() + posFy * h;
        double anchorX = owner.x() + anchorFx * w;
        double anchorY = owner.y() + anchorFy * h;

        Body limb = Body.create(1.0, PolyShape.momentForBox(1.0, boxW, boxH));
        limb.setPosition(limbX, limbY);
        space.addBody(limb);

        SegmentShape shape = SegmentShape.create(limb, 0.0, -halfLen * h, 0.0, halfLen * h);
        shape.setElasticity(DROID_ELASTICITY);
        shape.setFriction(DROID_FRICTION);
        shape.setGroup(index);
        space.addShape(shape);

        PivotJoint joint = PivotJoint.createAtWorldAnchor(limb, owner, anchorX, anchorY);
        // 原版把关节的误差反馈系数写成了 0(即只保留速度约束),但纯速度约束
        // 会随积分误差持续漂移(实测 100s 达 100px 级,四肢会明显脱离躯干)。
        // 原版可见行为是四肢始终贴合,故此处按库默认语义给 10%/帧 的位置反馈。
        joint.setErrorBias(0.1);
        space.addConstraint(joint);
        if (leg) {
            space.addConstraint(RotaryLimitJoint.create(owner, limb, -LEG_LIMIT, LEG_LIMIT));
        }
        return limb;
    }

    // --- 外部作用(传感器 / 触摸) ---

    /** 传感器姿态 → 重力(原版:pitch、roll × 重力系数 × 16)。 */
    void setOrientation(double pitch, double roll) {
        double scale = gravityFactor * 16.0;
        space.setGravity(roll * scale, pitch * scale);
    }

    void setGravity(double gx, double gy) {
        space.setGravity(gx, gy);
    }

    /** 摇晃(加速度计差值)→ 每个躯干质心。 */
    void applyImpulse(double jx, double jy) {
        for (int i = 0; i < droids.size(); i++) {
            droids.get(i).body.applyImpulse(jx, jy, 0.0, 0.0);
        }
    }

    /**
     * 触摸径向冲量。沿用原版的近似归一化(先取整再作比)与 1/距离 衰减,
     * 且不做半径截断 —— 所有机器人都受力。
     */
    void applyRadialImpulse(double x, double y, double strength) {
        double magnitudeBase = (int) (strength / 5.0) * RADIAL_IMPULSE_SCALE;
        if (magnitudeBase == 0.0) {
            return;
        }
        for (int i = 0; i < droids.size(); i++) {
            Body body = droids.get(i).body;
            double dx = body.x() - x;
            double dy = body.y() - y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 1e-6) {
                continue;
            }
            int ax = (int) Math.abs(dx);
            int ay = (int) Math.abs(dy);
            double ix;
            double iy;
            if (ax > ay) {
                // x 分量占主导(与整数截断一致:小数部分用整数除法得出)
                ix = dx < 0.0 ? -1.0 : 1.0;
                double ratio = ay / ax;
                iy = dy < 0.0 ? -ratio : ratio;
            } else {
                iy = dy < 0.0 ? -1.0 : 1.0;
                double ratio = ax / ay;
                ix = dx < 0.0 ? -ratio : ratio;
            }
            double magnitude = magnitudeBase / dist;
            body.applyImpulse(ix * magnitude, iy * magnitude, 0.0, 0.0);
        }
    }

    /**
     * 摇晃冲量:factor < 1 时全身马达停转;否则开启马达并按当前转向取反
     * (与原版 applyShakeImpulse 的符号逻辑一致)。
     */
    void applyShakeImpulse(double factor) {
        for (int i = 0; i < droids.size(); i++) {
            SimpleMotor motor = droids.get(i).bodyMotor;
            if (motor == null) {
                continue;
            }
            if (factor < 1.0) {
                motor.setMaxForce(0.0);
                motor.setRate(0.0);
            } else {
                motor.setMaxForce(Double.POSITIVE_INFINITY);
                motor.setRate(motor.rate() <= 0.0 ? factor : -factor);
            }
        }
    }

    /** 光照强度 → 双臂马达速率(rate < 1 停摆,否则左右反向)。 */
    void updateMotorRate(double rate) {
        for (int i = 0; i < droids.size(); i++) {
            Droid droid = droids.get(i);
            if (droid.leftArmMotor != null) {
                applyMotorRate(droid.leftArmMotor, rate, false);
            }
            if (droid.rightArmMotor != null) {
                applyMotorRate(droid.rightArmMotor, rate, true);
            }
        }
    }

    private void applyMotorRate(SimpleMotor motor, double rate, boolean negative) {
        if (rate < 1.0) {
            motor.setMaxForce(0.0);
            motor.setRate(0.0);
        } else {
            motor.setMaxForce(Double.POSITIVE_INFINITY);
            motor.setRate(negative ? -rate : rate);
        }
    }
}
