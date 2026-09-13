package com.reandroid.wallpaper.earth;

/**
 * 单个镜头：位置、朝向、拖拽惯性（纯 Java，无 GL/Android 依赖，可 JVM 测试）。
 *
 * <p>对应原版 {@code Camera}。原版把所有镜头共用的惯性写成 {@code private static float force}，
 * 是反编译后遗留的产物（三个镜头共享一份惯性）；这里改为每镜头独立。
 *
 * <p><b>关于 dt 正确性</b>：原版 {@code update()} 是 {@code angleY -= 6·dt + force; force *= 0.98}
 * —— 惯性 {@code force} 是"每帧多少度"，且按帧衰减，只有在原版固定的 30fps 下才成立。
 * 这里把 {@code force} 定为同一个语义（每 1/30 秒多少度），但按 {@code dt} 换算，
 * 于是 dt=1/30 时与原版**逐位等价**，其它帧率下也不再依赖帧率。
 */
final class EarthCamera {

    /** 原版常量：拖拽像素 → 角度的比例。 */
    static final float DRAG_MULTIPLIER = 0.025f;
    /** 原版常量：拖拽叠加到惯性上的比例。 */
    static final float FORCE_MULTIPLIER = 0.3f;
    /** 原版常量：每帧的惯性衰减（30fps 下）。 */
    static final float FRICTION_PER_FRAME = 0.98f;
    /** 原版的空闲自转速度（度/秒）。 */
    static final float IDLE_SPIN_DEG_PER_SEC = 6.0f;
    /** 原版的惯性参考帧率。 */
    static final float REFERENCE_FPS = 30.0f;

    private static final float DEGREES = 360.0f;

    /** 镜头序号，对应 {@link EarthScene} 的 CAMERA_* 常量。 */
    final int id;
    /** 相机位置（原版 {@code glTranslatef} 的参数）。 */
    final float x;
    final float y;
    final float z;

    float angleX;
    float angleY;
    float angleZ;

    /** 拖拽惯性，单位是"每 1/30 秒多少度"（与原版同语义，便于逐步对照）。 */
    float force;

    EarthCamera(int id, float x, float y, float z) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * 拖拽（原版 {@code cameraMoved}）。
     *
     * <p>原版写的是 {@code dx *= 0.025; setYAngle(-dx); force += 0.3 * (-dx)}，
     * 而 {@code setYAngle(f)} 是 {@code angleY -= f % 360} —— 两次取负抵消，净效果是
     * {@code angleY += dx*0.025}、{@code force -= 0.3*dx*0.025}。这里直接写净效果。
     */
    void drag(float dxPixels) {
        float d = dxPixels * DRAG_MULTIPLIER;
        angleY += d;
        force -= FORCE_MULTIPLIER * d;
    }

    /**
     * 推进一帧。
     *
     * @param dt           本帧秒数
     * @param spinDegPerSec 空闲自转速度（度/秒），0 = 静止
     */
    void update(float dt, float spinDegPerSec) {
        // force 的单位是"每 1/30 秒多少度"，换算成这帧实际贡献的角度
        float frames = dt * REFERENCE_FPS;
        angleY -= spinDegPerSec * dt + force * frames;
        force *= (float) Math.pow(FRICTION_PER_FRAME, frames);
        angleY = wrapDegrees(angleY);
    }

    private static float wrapDegrees(float deg) {
        deg %= DEGREES;
        if (deg < 0.0f) deg += DEGREES;
        return deg;
    }
}
