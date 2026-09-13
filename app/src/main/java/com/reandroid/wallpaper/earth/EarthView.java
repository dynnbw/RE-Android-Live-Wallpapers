package com.reandroid.wallpaper.earth;

import com.reandroid.utils.Mat4;

/**
 * 相机视图矩阵与天空视图矩阵（纯 Java，无 Android/GL 依赖，可 JVM 测试）。
 *
 * <p>两者的差别只有相机平移这一项，而它正是整段天空逻辑的全部内容，所以单独放出来测：
 *
 * <pre>
 *   相机（地球/云层/高光/月球）  P · Rz(黄赤交角) · T_cam · R_cam · M
 *   天空（星空球）               P · Rz(黄赤交角) · R_cam · M
 * </pre>
 *
 * <p><b>去掉的只能是 T_cam，不能连 R_cam 一起去掉。</b>R_cam 是"相机转到哪"，
 * 星空是无穷远的真实天空：相机转向，看到的就该是那一片星。若把 R_cam 也去掉，
 * 星空就变成贴在屏幕上的一张画 —— 拖拽时地球在转、星星纹丝不动，
 * 看着地球昼半球时，挡在地球背后的那片星也就与视线方向无关了（曾如此）。
 *
 * <p>保留黄赤交角 Rz(23.5°)：那是整个场景的姿态，星空跟着倾斜才对。
 */
final class EarthView {

    /** 原版常量：黄赤交角。 */
    static final float UNIVERSE_TILT_DEG = 23.5f;

    private EarthView() {}

    /**
     * 相机视图矩阵，顺序与原版一致：原版在投影矩阵上依次
     * {@code glRotatef(23.5)} 再 {@code camera.apply()}，
     * 而 {@code apply()} 是"先 translate 再依次 rotate"，
     * 所以合成结果是 {@code Rz(23.5) · T · Rx · Ry · Rz}。
     */
    static void buildCamera(float[] out, EarthCamera cam) {
        Mat4.setIdentityM(out);
        Mat4.rotateM(out, UNIVERSE_TILT_DEG, 0.0f, 0.0f, 1.0f);
        Mat4.translateM(out, cam.x, cam.y, cam.z);
        Mat4.rotateM(out, cam.angleX, 1.0f, 0.0f, 0.0f);
        Mat4.rotateM(out, cam.angleY, 0.0f, 1.0f, 0.0f);
        Mat4.rotateM(out, cam.angleZ, 0.0f, 0.0f, 1.0f);
    }

    /**
     * 天空视图矩阵 = 相机视图矩阵去掉平移列。
     *
     * <p>矩阵写成 {@code Rz·T·R_cam}，平移只在第四列，所以"抹掉第四列"恰好等于
     * "只去掉 T_cam、留着 R_cam" —— 不需要再拼一遍旋转，也就不会和
     * {@link #buildCamera} 漂开（两处旋转分别写死早晚会不一致）。
     */
    static void buildSky(float[] out, EarthCamera cam) {
        buildCamera(out, cam);
        out[12] = 0.0f;
        out[13] = 0.0f;
        out[14] = 0.0f;
        out[15] = 1.0f;
    }
}
