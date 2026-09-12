package com.reandroid.wallpaper.cube;

/**
 * 触摸旋转的视角姿态,用单位四元数维护(pure Java,无 Android 依赖,可单独 JVM 测试)。
 *
 * 这里维护的是"看这个立体的角度",作用在自动旋转与桌面视差之外:
 * 单元四元数时渲染结果与原版逐位一致,拖拽只是额外换了个观察角度。
 * 用四元数而不是两个欧拉角,是因为全向拖拽下欧拉角的组合与顺序有关,
 * 拖到"上下颠倒"附近时会出现万向锁式的姿态跳变。
 */
final class CubeViewRotation {

    private float mW = 1f;
    private float mX;
    private float mY;
    private float mZ;

    void reset() {
        mW = 1f;
        mX = 0f;
        mY = 0f;
        mZ = 0f;
    }

    boolean isIdentity() {
        return mW == 1f && mX == 0f && mY == 0f && mZ == 0f;
    }

    /**
     * 叠加一个屏幕轴角度增量(弧度):yaw 绕屏幕竖直轴,pitch 绕屏幕水平轴,
     * 正 pitch 表示指尖下移(正面跟着往下走)。
     *
     * 左乘(dQ ⊗ mView)= 增量作用在屏幕坐标系里,所以横拖永远绕屏幕竖直轴、
     * 竖拖永远绕屏幕水平轴,不受当前姿态影响。增量本身按原版的 Ry·Rx 次序组合,
     * 单轴拖拽与原版公式完全等价(见 CubeRotationTest)。
     */
    void apply(float yaw, float pitch) {
        if (yaw == 0f && pitch == 0f) {
            return;
        }
        float halfYaw = yaw * 0.5f;
        // 原版的 X 旋转等价于标准 Rx 取负号(见 CubeScene.rotateAndProject 里的公式)
        float halfPitch = -pitch * 0.5f;
        float cosYaw = (float) Math.cos(halfYaw), sinYaw = (float) Math.sin(halfYaw);
        float cosPitch = (float) Math.cos(halfPitch), sinPitch = (float) Math.sin(halfPitch);

        // dQ = Ry(yaw) ⊗ Rx(pitch)
        float dw = cosYaw * cosPitch;
        float dx = cosYaw * sinPitch;
        float dy = sinYaw * cosPitch;
        float dz = -sinYaw * sinPitch;

        // mView = dQ ⊗ mView(Hamilton 积)
        float w = dw * mW - dx * mX - dy * mY - dz * mZ;
        float x = dw * mX + dx * mW + dy * mZ - dz * mY;
        float y = dw * mY - dx * mZ + dy * mW + dz * mX;
        float z = dw * mZ + dx * mY - dy * mX + dz * mW;

        // 反复相乘会积累浮点误差,每步归一化
        float norm = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        if (norm < 1.0E-6f) {
            reset();
            return;
        }
        mW = w / norm;
        mX = x / norm;
        mY = y / norm;
        mZ = z / norm;
    }

    /** 写入 3x3 旋转矩阵(row-major,out[0..8])。 */
    void toMatrix(float[] out) {
        float xx = mX * mX, yy = mY * mY, zz = mZ * mZ;
        float xy = mX * mY, xz = mX * mZ, yz = mY * mZ;
        float wx = mW * mX, wy = mW * mY, wz = mW * mZ;
        out[0] = 1f - 2f * (yy + zz);
        out[1] = 2f * (xy - wz);
        out[2] = 2f * (xz + wy);
        out[3] = 2f * (xy + wz);
        out[4] = 1f - 2f * (xx + zz);
        out[5] = 2f * (yz - wx);
        out[6] = 2f * (xz - wy);
        out[7] = 2f * (yz + wx);
        out[8] = 1f - 2f * (xx + yy);
    }
}
