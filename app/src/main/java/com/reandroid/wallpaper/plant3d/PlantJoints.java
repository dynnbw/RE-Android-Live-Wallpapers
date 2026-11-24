package com.reandroid.wallpaper.plant3d;

/**
 * 关节弹簧物理（2.6 版实现）。
 *
 * <p>1.x 的版本把锚点存成“相对父关节的偏移”，短锚点的关节会失去参照、在几十度到
 * 一百八十度之间乱翻（设备上就是整棵树疯狂摇晃）。2.6 把整套模拟搬到了世界空间：
 * 每个关节记自己的世界末端位置、世界旋转和弹簧目标位置，积分只作用在这些绝对量上，
 * 最后再把世界旋转换算成节点的局部旋转。这里逐条照 2.6 的结构写。
 */
final class PlantJoints {

    static final class Vec3 {
        float x, y, z;

        Vec3() {}

        Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void set(Vec3 other) {
            x = other.x;
            y = other.y;
            z = other.z;
        }

        void set(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        float length() {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    static final class Quat {
        float w, x, y, z;

        Quat() {
            w = 1f;
        }

        Quat(float w, float x, float y, float z) {
            set(w, x, y, z);
        }

        void set(float w, float x, float y, float z) {
            this.w = w;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void set(Quat other) {
            set(other.w, other.x, other.y, other.z);
        }

        /** 原实现的角度单位是度；测试和滚动旋转都会用到。 */
        void setAxisAngle(Vec3 axis, float degrees) {
            double half = Math.toRadians(degrees) * 0.5;
            double sin = Math.sin(half);
            w = (float) Math.cos(half);
            x = (float) (axis.x * sin);
            y = (float) (axis.y * sin);
            z = (float) (axis.z * sin);
        }

        void multiply(Quat other) {
            float nw = w * other.w - x * other.x - y * other.y - z * other.z;
            float nx = w * other.x + x * other.w + y * other.z - z * other.y;
            float ny = w * other.y - x * other.z + y * other.w + z * other.x;
            float nz = w * other.z + x * other.y - y * other.x + z * other.w;
            set(nw, nx, ny, nz);
        }

        void normalize() {
            float length = (float) Math.sqrt(w * w + x * x + y * y + z * z);
            if (length <= 0f) {
                set(1f, 0f, 0f, 0f);
                return;
            }
            float inverse = 1f / length;
            w *= inverse;
            x *= inverse;
            y *= inverse;
            z *= inverse;
        }

        void rotate(Vec3 v, Vec3 out) {
            float q0 = -w;
            float f4 = x * v.x + y * v.y + z * v.z;
            out.x = 2f * (((v.x * q0 - (y * v.z - z * v.y)) * q0) + f4 * x) - v.x;
            out.y = 2f * (((v.y * q0 - (z * v.x - x * v.z)) * q0) + f4 * y) - v.y;
            out.z = 2f * (((v.z * q0 - (x * v.y - y * v.x)) * q0) + f4 * z) - v.z;
        }
    }

    static final class Physics {
        final float springLength;
        final float springStrength;
        final float damping;

        Physics(float springLength, float springStrength, float damping) {
            this.springLength = springLength;
            this.springStrength = springStrength;
            this.damping = damping;
        }
    }

    static final class Global {
        float extraDamping = 1f;
        final Vec3 gravity = new Vec3(0f, -2.0E-4f, 0f);
    }

    static final class Joint {
        private static final Vec3 BONE_DIR = new Vec3(1f, 0f, 0f);
        private static final Vec3 BONE_ORTH = new Vec3(0f, 1f, 0f);

        private final Physics physics;
        private final Global global;
        private final Joint prev;
        private final Quat originalRotation = new Quat();
        private final Quat rotation = new Quat();
        private final Quat worldRotation = new Quat();
        private final Quat worldSpringRotation = new Quat();
        private final Vec3 direction = new Vec3();
        private final Vec3 orthogonalTarget = new Vec3();
        private final Vec3 worldPosition = new Vec3();
        private final Vec3 worldEndPosition = new Vec3();
        private final Vec3 worldSpringPosition = new Vec3();
        private final Vec3 velocity = new Vec3();
        private final Vec3 tempVec1 = new Vec3();
        private final Vec3 tempVec2 = new Vec3();
        private final Vec3 tempVec3 = new Vec3();
        private final Vec3 tempVec4 = new Vec3();
        private final Quat tempQuat = new Quat();
        private float rootiness;
        private boolean simulate;

        final PlantScene.Node node;

        Joint(
                PlantScene.Node node,
                Joint prev,
                float x,
                float y,
                float z,
                float qw,
                float qx,
                float qy,
                float qz,
                Physics physics,
                Global global) {
            this.node = node;
            this.prev = prev;
            this.physics = physics;
            this.global = global;
            originalRotation.set(qw, qx, qy, qz);
            rotation.set(qw, qx, qy, qz);
            worldRotation.set(qw, qx, qy, qz);
            direction.set(physics.springLength, 0f, 0f);
            originalRotation.rotate(direction, orthogonalTarget);
            if (prev != null) {
                prev.simulate = true;
                prev.worldEndPosition.set(x, y, z);
                if (new Vec3(x, y, z).length() > 0.001f) {
                    prev.originalRotation.rotate(direction, prev.orthogonalTarget);
                }
            }
            Joint walk = prev;
            while (walk != null) {
                walk.rootiness += 1f;
                walk = walk.prev;
            }
        }

        /** 2.6 的 updateSpringPosition()。 */
        void updateSpringPosition() {
            if (prev == null || !simulate) {
                return;
            }
            worldPosition.set(prev.worldEndPosition);
            worldRotation.set(rotation);
            worldRotation.multiply(prev.worldRotation);
            worldRotation.rotate(direction, tempVec1);
            worldEndPosition.set(
                    prev.worldEndPosition.x + tempVec1.x,
                    prev.worldEndPosition.y + tempVec1.y,
                    prev.worldEndPosition.z + tempVec1.z);
            worldSpringRotation.set(originalRotation);
            worldSpringRotation.multiply(prev.worldRotation);
            worldSpringRotation.rotate(direction, tempVec1);
            worldSpringPosition.set(
                    prev.worldEndPosition.x + tempVec1.x,
                    prev.worldEndPosition.y + tempVec1.y,
                    prev.worldEndPosition.z + tempVec1.z);
        }

        /** 2.6 的 updateNodePosition()。 */
        void updateNodePosition() {
            if (prev == null || !simulate) {
                return;
            }
            tempVec1.set(worldEndPosition);
            float damping = physics.damping * global.extraDamping;
            float spring = physics.springStrength;
            for (int i = 0; i < 4; i++) {
                velocity.x += global.gravity.x;
                velocity.y += global.gravity.y;
                velocity.z += global.gravity.z;
                float strength = spring * ((1f / rootiness) + 0.5f);
                velocity.x += (worldSpringPosition.x - worldEndPosition.x) * strength;
                velocity.y += (worldSpringPosition.y - worldEndPosition.y) * strength;
                velocity.z += (worldSpringPosition.z - worldEndPosition.z) * strength;
                velocity.x *= damping;
                velocity.y *= damping;
                velocity.z *= damping;
                worldEndPosition.x += velocity.x * 0.01f;
                worldEndPosition.y += velocity.y * 0.01f;
                worldEndPosition.z += velocity.z * 0.01f;
                if (worldEndPosition.z < -0.2f) {
                    worldEndPosition.z = -0.2f;
                    velocity.z = 0f;
                }
            }
            tempVec2.set(
                    tempVec1.x - prev.worldEndPosition.x,
                    tempVec1.y - prev.worldEndPosition.y,
                    tempVec1.z - prev.worldEndPosition.z);
            tempVec3.set(
                    worldEndPosition.x - prev.worldEndPosition.x,
                    worldEndPosition.y - prev.worldEndPosition.y,
                    worldEndPosition.z - prev.worldEndPosition.z);
            shortestArc(tempQuat, tempVec2, tempVec3);
            worldRotation.multiply(tempQuat);
        }

        /** 2.6 的 updateNodeRotation()：把世界旋转换算成节点局部旋转。 */
        void updateNodeRotation() {
            if (prev == null || !simulate) {
                return;
            }
            rotation.set(worldRotation);
            // 父关节世界旋转的共轭：world × parentWorld⁻¹ 得到本关节的局部旋转
            tempQuat.set(
                    prev.worldRotation.w,
                    -prev.worldRotation.x,
                    -prev.worldRotation.y,
                    -prev.worldRotation.z);
            rotation.multiply(tempQuat);
            rotation.normalize();
            rotation.rotate(BONE_DIR, tempVec1);
            rotation.rotate(BONE_ORTH, tempVec2);
            float dot = tempVec1.x * orthogonalTarget.x
                    + tempVec1.y * orthogonalTarget.y
                    + tempVec1.z * orthogonalTarget.z;
            tempVec3.set(tempVec1.x * dot, tempVec1.y * dot, tempVec1.z * dot);
            tempVec4.set(
                    orthogonalTarget.x - tempVec3.x,
                    orthogonalTarget.y - tempVec3.y,
                    orthogonalTarget.z - tempVec3.z);
            shortestArc(tempQuat, tempVec2, tempVec4);
            rotation.multiply(tempQuat);
        }

        Quat rotation() {
            return rotation;
        }
    }

    private PlantJoints() {}

    static void shortestArc(Quat out, Vec3 from, Vec3 to) {
        float fromLength = from.length();
        float toLength = to.length();
        if (fromLength == 0f || toLength == 0f) {
            out.set(1f, 0f, 0f, 0f);
            return;
        }
        float fx = from.x / fromLength, fy = from.y / fromLength, fz = from.z / fromLength;
        float tx = to.x / toLength, ty = to.y / toLength, tz = to.z / toLength;
        float sx = tx + fx, sy = ty + fy, sz = tz + fz;
        float sum = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sum < 1.0E-4f) {
            out.set(1f, 0f, 0f, 0f);
            return;
        }
        float inverse = 1f / sum;
        sx *= inverse;
        sy *= inverse;
        sz *= inverse;
        out.set(
                fx * sx + fy * sy + fz * sz,
                fy * sz - fz * sy,
                fz * sx - fx * sz,
                fx * sy - fy * sx);
        out.normalize();
    }
}
