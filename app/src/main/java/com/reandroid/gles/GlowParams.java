package com.reandroid.gles;

/**
 * 辉光里唯一能放在 JVM 里算的部分。
 *
 * <p>挑出来的这两条都是**错了之后症状很难定位**的边界：见 {@link GlowParamsTest}。
 *
 * <p>放在 {@code gles} 包而不是壁纸包 —— 它唯一的消费者是 {@link GlowRenderer}，
 * 让一个通用组件反向依赖某个具体壁纸，方向是错的。
 */
final class GlowParams {

    private GlowParams() {
    }

    /**
     * 半分辨率的边长。**下限 1** —— 窄屏上 {@code w / 2} 可能算出 0，
     * 那会让 FBO 建失败，而症状是"辉光整个不出现"，不是报错。
     */
    static int halfSize(int full) {
        return Math.max(1, full / 2);
    }

    /**
     * 模糊步长（纹理坐标）：半径（像素）除以该遍的边长。
     *
     * <p>分母取 {@code max(1, ·)} 是防 0 除。尺寸为 0 时算出 Infinity，
     * 模糊着色器会采样到 NaN —— 症状是**整片辉光变成黑色**，而且不报任何错。
     *
     * <p>半径为 0 时返回 0，那一遍退化成"原样拷贝"，是良定义的。
     */
    static float blurStep(int size, float radius) {
        return radius / Math.max(1, size);
    }
}
